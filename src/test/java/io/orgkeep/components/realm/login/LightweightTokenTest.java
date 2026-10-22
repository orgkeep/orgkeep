package io.orgkeep.components.realm.login;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.orgkeep.api.CatalogService;
import io.orgkeep.api.OrgMemberService;
import io.orgkeep.api.OrgService;
import io.orgkeep.api.ProductService;
import io.orgkeep.api.RealmProvisioner;
import io.orgkeep.api.TeamService;
import io.orgkeep.api.UserGroupService;
import io.orgkeep.api.model.AddMemberRequest;
import io.orgkeep.api.model.AssignProductRequest;
import io.orgkeep.api.model.CreateCatalogRequest;
import io.orgkeep.api.model.CreateOrgRequest;
import io.orgkeep.api.model.CreateProductRequest;
import io.orgkeep.api.model.CreateUserGroupRequest;
import io.orgkeep.api.model.Feature;
import io.orgkeep.api.model.GrantProductRequest;
import io.orgkeep.api.model.ParamDefinition;
import io.orgkeep.api.model.ParamType;
import io.orgkeep.api.model.QuotaCycle;
import io.orgkeep.fixtures.TestRealm;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.CreatedResponseUtil;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.ClientResource;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.ClientScopeRepresentation;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.ProtocolMapperRepresentation;
import org.keycloak.representations.idm.UserRepresentation;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spike of the lightweight access tokens (see "Tokens légers" in ARCHITECTURE.md): what Keycloak really does,
 * on real tokens, before Orgkeep builds anything on it. Each test answers one question of the spike.
 * <p>
 * The toggle, the marker scope and the API clients do not exist in Orgkeep yet: they are set here directly through the
 * admin API, the way Orgkeep will set them. Once they exist, the setup goes through the services of the core and the
 * tests stay as the regression tests of the lightweight mode.
 * <p>
 * Two Keycloak rules found by the spike shape the setup:
 * <ul>
 *   <li>a client may only introspect a token whose audience it is in ("Client 'x' is not in the token audience"):
 *       an API client gets an audience mapper on the login client;</li>
 *   <li>the introspection recomputes the claims from the scopes listed in the "scope" claim: a catalog scope must be
 *       listed there, or its claims are lost. Orgkeep creates them with include.in.token.scope=true (CatalogMapper).</li>
 * </ul>
 * Setup: the model of TeamTokenTest, reduced to one product. "shop-repository" in prod (features scan and flow,
 * parameter region), assigned to acme (1000 a month, scan bought); Paul is in "ops", which gives the product with a
 * quota of team of 300.
 */
@Slf4j
@QuarkusTest
class LightweightTokenTest {
    private static final String ORG = "acme";
    private static final String PRODUCT = "shop-repository";
    private static final String PASSWORD = "password";
    private static final String API_SECRET = "api-secret";
    private static final String LIGHTWEIGHT = "client.use.lightweight.access.token.enabled";
    /** The claim of the marker, and the name of its client scope. */
    private static final String MARKER = "lightweight";

    @Inject Keycloak keycloak;
    @Inject RealmProvisioner realmProvisioner;
    @Inject CatalogService catalogService;
    @Inject ProductService productService;
    @Inject OrgService orgService;
    @Inject OrgMemberService memberService;
    @Inject UserGroupService userGroupService;
    @Inject TeamService teamService;

    @ConfigProperty(name = "quarkus.keycloak.admin-client.server-url")
    String keycloakUrl;

    private final ObjectMapper json = new ObjectMapper();
    private TestRealm testRealm;
    private String paulId;

    @BeforeEach
    void beforeEach() {
        testRealm = TestRealm.create(keycloak);
        String realm = testRealm.name();
        realmProvisioner.ensureDefaults(realm);
        // the reason of a refused introspection is only in the events of the realm
        var events = testRealm.resource().getRealmEventsConfig();
        events.setEventsEnabled(true);
        events.setEnabledEventTypes(List.of("INTROSPECT_TOKEN_ERROR"));
        testRealm.resource().updateRealmEventsConfig(events);
        updateLoginClient(client -> client.setDirectAccessGrantsEnabled(true));

        catalogService.create(realm, new CreateCatalogRequest("prod", "Production", null));
        productService.create(realm, "prod", new CreateProductRequest(PRODUCT, "Shop repository", null,
                List.of(new Feature("scan", null), new Feature("flow", null)),
                List.of(new ParamDefinition("region", ParamType.ENUM, true, "eu", List.of("eu", "us")))));
        orgService.create(realm, new CreateOrgRequest(ORG, Set.of(), null, null));
        productService.assign(realm, ORG, "prod", PRODUCT,
                new AssignProductRequest(1000L, QuotaCycle.MONTHLY, List.of("scan"), Map.of()));

        paulId = member("paul");
        userGroupService.create(realm, ORG, new CreateUserGroupRequest("ops"));
        teamService.grantProduct(realm, ORG, "ops", "prod", PRODUCT, new GrantProductRequest(null, 300L, null));
        userGroupService.addMember(realm, ORG, "ops", paulId);
    }

    @AfterEach
    void afterEach() {
        testRealm.close();
    }

    // --- the lightweight token itself

    // the standard claims and the marker, nothing of the catalog once the mappers of the products are out of the
    // lightweight token. RFC 9068 expects iss, exp, aud, sub, client_id, iat, jti
    @Test
    void whatALightweightTokenCarries() throws Exception {
        log.info("Keycloak {}", keycloak.serverInfo().getInfo().getSystemInfo().getVersion());
        String full = logIn("paul", "openid prod");
        enableLightweightMode();

        String token = logIn("paul", "openid prod");
        JsonNode claims = claims(token);
        log.info("Lightweight token of paul ({} bytes, full one {} bytes): {}", token.length(), full.length(),
                claims.toPrettyString());

        assertTrue(claims.path(MARKER).asBoolean(), claims.toString());
        assertTrue(claims.path("resource_access").isMissingNode(), claims.toString());
        assertTrue(claims.path("realm_access").isMissingNode(), claims.toString());
        assertTrue(claims.path("prod").isMissingNode(), claims.toString());
        assertTrue(claims.path("email").isMissingNode(), claims.toString());
        // the scope of the marker is not listed in the scope claim (white label)
        assertFalse(claims.path("scope").asText().contains(MARKER), claims.path("scope").asText());
        log.info("RFC 9068 claims present: {}", List.of("iss", "exp", "aud", "sub", "client_id", "iat", "jti").stream()
                .collect(Collectors.toMap(name -> name, name -> !claims.path(name).isMissingNode())));
    }

    // today the mappers of the products are in the lightweight token: quotas without resource_access
    @Test
    void theMappersOfTheProductsAreInTheLightweightTokenToday() throws Exception {
        updateLoginClient(client -> setAttribute(client, LIGHTWEIGHT, "true"));

        JsonNode claims = claims(logIn("paul", "openid prod"));

        assertTrue(claims.path("resource_access").isMissingNode(), claims.toString());
        assertFalse(claims.at("/prod/shop-repository/quotas").isMissingNode(), claims.toString());
    }

    // --- who may introspect

    // a confidential client of the realm, but not in the audience of the token: refused
    @Test
    void anApiClientOutsideTheAudienceIsRefused() throws Exception {
        enableLightweightMode();
        createClient(apiClient("shop-api"));

        assertFalse(introspect("shop-api", logIn("paul", "openid prod")).path("active").asBoolean());
    }

    // a confidential client without any flow, in the audience of the login client: everything the full token had
    @Test
    void anApiClientIntrospectsALightweightToken() throws Exception {
        enableLightweightMode();
        createApiClient("shop-api");

        JsonNode introspection = introspect("shop-api", logIn("paul", "openid prod"));
        log.info("Introspection of the lightweight token by shop-api: {}", introspection.toPrettyString());

        assertTrue(introspection.path("active").asBoolean(), introspection.toString());
        assertEquals(Set.of(PRODUCT, PRODUCT + ":scan"), strings(introspection.at("/resource_access/prod/roles")));
        assertEquals(Set.of("acme", "acme/ops"), pools(introspection.at("/prod/shop-repository/quotas")));
        assertEquals("eu", introspection.at("/prod/shop-repository/params/region").asText());
        assertTrue(strings(introspection.path("aud")).containsAll(Set.of("shop-api", "prod")), introspection.toString());
    }

    // the catalog client is in aud (audience resolve) when its scope is in the token: made confidential, it introspects
    // the tokens of its catalog only
    @Test
    void theCatalogClientIntrospectsTheTokensOfItsCatalog() throws Exception {
        enableLightweightMode();
        ClientResource prod = testRealm.resource().clients().get(clientUuid("prod"));
        ClientRepresentation client = prod.toRepresentation();
        client.setPublicClient(false);
        client.setSecret(API_SECRET);
        prod.update(client);

        assertTrue(introspect("prod", logIn("paul", "openid prod")).path("active").asBoolean());
        assertFalse(introspect("prod", logIn("paul", "openid")).path("active").asBoolean());
    }

    // two API clients, one of them restricted (fullScopeAllowed=false): the answer does not depend on who introspects
    @Test
    void theIntrospectingClientDoesNotChangeTheAnswer() throws Exception {
        enableLightweightMode();
        createApiClient("shop-api");
        String restricted = createApiClient("billing-api");
        ClientResource billingApi = testRealm.resource().clients().get(restricted);
        ClientRepresentation client = billingApi.toRepresentation();
        client.setFullScopeAllowed(false);
        billingApi.update(client);
        String token = logIn("paul", "openid prod");

        JsonNode byShop = introspect("shop-api", token);
        JsonNode byBilling = introspect("billing-api", token);

        assertTrue(byShop.path("active").asBoolean(), byShop.toString());
        assertEquals(byShop, byBilling);
    }

    // --- what the introspection returns

    // the introspection recomputes the claims from the scopes of the "scope" claim: the catalog must be listed there
    @Test
    void theCatalogIsListedInTheScopeClaim() throws Exception {
        assertTrue(Set.of(claims(logIn("paul", "openid prod")).path("scope").asText().split(" ")).contains("prod"));
    }

    // before the switch, the APIs introspect full tokens: the same claims as the token itself
    @Test
    void anApiClientIntrospectsAFullToken() throws Exception {
        createApiClient("shop-api");
        String token = logIn("paul", "openid prod");

        JsonNode introspection = introspect("shop-api", token);

        assertTrue(introspection.path("active").asBoolean(), introspection.toString());
        JsonNode claims = claims(token);
        assertEquals(claims.path("resource_access"), introspection.path("resource_access"));
        assertEquals(claims.path("prod"), introspection.path("prod"));
    }

    // rights changed after the token was issued: the introspection gives the current ones
    @Test
    void theIntrospectionGivesTheCurrentRights() throws Exception {
        enableLightweightMode();
        createApiClient("shop-api");
        String token = logIn("paul", "openid prod");
        assertFalse(introspect("shop-api", token).at("/resource_access/prod").isMissingNode());

        userGroupService.removeMember(testRealm.name(), ORG, "ops", paulId);

        JsonNode introspection = introspect("shop-api", token);
        assertTrue(introspection.path("active").asBoolean(), introspection.toString());
        assertTrue(introspection.at("/resource_access/prod").isMissingNode(), introspection.toString());
    }

    // the user session ended (logout): the token is no longer active, before its exp
    @Test
    void theIntrospectionAfterLogout() throws Exception {
        enableLightweightMode();
        createApiClient("shop-api");
        String token = logIn("paul", "openid prod");
        assertTrue(introspect("shop-api", token).path("active").asBoolean());

        testRealm.resource().users().get(paulId).logout();

        assertFalse(introspect("shop-api", token).path("active").asBoolean());
    }

    // --- the token preview and the service accounts

    // the "evaluate" endpoint of the token preview applies the lightweight mode of the client
    @Test
    void theEvaluateEndpointAppliesTheLightweightMode() throws Exception {
        enableLightweightMode();

        JsonNode evaluated = json.copy()
                .setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL)
                .valueToTree(testRealm.resource().clients().get(loginClientUuid()).clientScopesEvaluate()
                        .generateAccessToken("openid prod", paulId, null));
        log.info("Evaluated token of a lightweight client: {}", evaluated.toPrettyString());

        assertTrue(evaluated.path("resource_access").isMissingNode(), evaluated.toString());
        assertTrue(evaluated.path(MARKER).asBoolean(), evaluated.toString());
    }

    // a service account (client_credentials): its lightweight token is introspectable like a user's
    @Test
    void theIntrospectionOfAServiceAccountToken() throws Exception {
        createMarkerScope();
        createClient(apiClient("shop-api"));
        ClientRepresentation batch = new ClientRepresentation();
        batch.setClientId("batch");
        batch.setPublicClient(false);
        batch.setServiceAccountsEnabled(true);
        batch.setStandardFlowEnabled(false);
        batch.setSecret(API_SECRET);
        batch.setAttributes(Map.of(LIGHTWEIGHT, "true"));
        String batchUuid = createClient(batch);
        testRealm.resource().clients().get(batchUuid).addDefaultClientScope(markerScopeId());
        addAudience(batchUuid, "shop-api");

        String token = tokenResponse(Map.of("grant_type", "client_credentials", "client_id", "batch",
                "client_secret", API_SECRET)).path("access_token").asText();
        JsonNode introspection = introspect("shop-api", token);
        log.info("Lightweight token of a service account: {}\nIntrospection: {}", claims(token).toPrettyString(),
                introspection.toPrettyString());

        assertTrue(claims(token).path(MARKER).asBoolean());
        assertTrue(introspection.path("active").asBoolean(), introspection.toString());
    }

    // --- what Orgkeep will do: the toggle on the login client, the marker scope, the mappers out of the light token,
    // the API clients in the audience

    private void enableLightweightMode() {
        updateLoginClient(client -> setAttribute(client, LIGHTWEIGHT, "true"));
        createMarkerScope();
        testRealm.resource().clients().get(loginClientUuid()).addDefaultClientScope(markerScopeId());
        excludeProductMappersFromLightweightToken();
    }

    /** A client scope with a hardcoded claim "lightweight": true, in the lightweight token only. */
    private void createMarkerScope() {
        ClientScopeRepresentation scope = new ClientScopeRepresentation();
        scope.setName(MARKER);
        scope.setProtocol("openid-connect");
        scope.setAttributes(Map.of("include.in.token.scope", "false", "display.on.consent.screen", "false"));
        ProtocolMapperRepresentation mapper = new ProtocolMapperRepresentation();
        mapper.setName(MARKER);
        mapper.setProtocol("openid-connect");
        mapper.setProtocolMapper("oidc-hardcoded-claim-mapper");
        mapper.setConfig(Map.of(
                "claim.name", MARKER,
                "claim.value", "true",
                "jsonType.label", "boolean",
                "access.token.claim", "true",
                "lightweight.claim", "true",
                "introspection.token.claim", "false",
                "id.token.claim", "false",
                "userinfo.token.claim", "false"));
        scope.setProtocolMappers(List.of(mapper));
        try (Response response = testRealm.resource().clientScopes().create(scope)) {
            CreatedResponseUtil.getCreatedId(response);
        }
    }

    private String markerScopeId() {
        return scope(MARKER).getId();
    }

    /** "Add to lightweight access token" unchecked on the quotas and params mappers of the catalog. */
    private void excludeProductMappersFromLightweightToken() {
        var mappers = testRealm.resource().clientScopes().get(scope("prod").getId()).getProtocolMappers();
        mappers.getMappers().forEach(mapper -> {
            Map<String, String> config = new HashMap<>(mapper.getConfig());
            config.put("lightweight.claim", "false");
            mapper.setConfig(config);
            mappers.update(mapper.getId(), mapper);
        });
    }

    /** An API client, in the audience of the tokens of the login client. */
    private String createApiClient(String clientId) {
        String uuid = createClient(apiClient(clientId));
        addAudience(loginClientUuid(), clientId);
        return uuid;
    }

    /** A confidential client without any flow: it can only authenticate itself, here to introspect. */
    private static ClientRepresentation apiClient(String clientId) {
        ClientRepresentation client = new ClientRepresentation();
        client.setClientId(clientId);
        client.setPublicClient(false);
        client.setSecret(API_SECRET);
        client.setStandardFlowEnabled(false);
        client.setImplicitFlowEnabled(false);
        client.setDirectAccessGrantsEnabled(false);
        client.setServiceAccountsEnabled(false);
        return client;
    }

    /** An audience mapper on a requesting client: Keycloak lets a client introspect only the tokens it is the audience of. */
    private void addAudience(String clientUuid, String apiClientId) {
        ProtocolMapperRepresentation mapper = new ProtocolMapperRepresentation();
        mapper.setName("aud-" + apiClientId);
        mapper.setProtocol("openid-connect");
        mapper.setProtocolMapper("oidc-audience-mapper");
        mapper.setConfig(Map.of("included.client.audience", apiClientId, "access.token.claim", "true",
                "introspection.token.claim", "true", "lightweight.claim", "false", "id.token.claim", "false"));
        try (Response response = testRealm.resource().clients().get(clientUuid).getProtocolMappers().createMapper(mapper)) {
            assertEquals(201, response.getStatus());
        }
    }

    private String createClient(ClientRepresentation client) {
        try (Response response = testRealm.resource().clients().create(client)) {
            return CreatedResponseUtil.getCreatedId(response);
        }
    }

    private void updateLoginClient(Consumer<ClientRepresentation> change) {
        ClientResource loginClient = testRealm.resource().clients().get(loginClientUuid());
        ClientRepresentation client = loginClient.toRepresentation();
        change.accept(client);
        loginClient.update(client);
    }

    private static void setAttribute(ClientRepresentation client, String name, String value) {
        Map<String, String> attributes = client.getAttributes() == null ? new HashMap<>() : new HashMap<>(client.getAttributes());
        attributes.put(name, value);
        client.setAttributes(attributes);
    }

    private ClientScopeRepresentation scope(String name) {
        return testRealm.resource().clientScopes().findAll().stream()
                .filter(scope -> scope.getName().equals(name))
                .findFirst().orElseThrow();
    }

    private String clientUuid(String clientId) {
        return testRealm.resource().clients().findByClientId(clientId).get(0).getId();
    }

    private String loginClientUuid() {
        return clientUuid(testRealm.name());
    }

    /** See TeamTokenTest.member. */
    private String member(String username) {
        UserRepresentation user = new UserRepresentation();
        user.setUsername(username);
        user.setEmail(username + "@acme.test");
        user.setEmailVerified(true);
        user.setFirstName(username);
        user.setLastName("Test");
        user.setEnabled(true);
        CredentialRepresentation credential = new CredentialRepresentation();
        credential.setType(CredentialRepresentation.PASSWORD);
        credential.setValue(PASSWORD);
        credential.setTemporary(false);
        user.setCredentials(List.of(credential));
        testRealm.createUser(user);
        return memberService.add(testRealm.name(), ORG, new AddMemberRequest(user.getEmail(), user.getFirstName(), user.getLastName())).id();
    }

    // --- HTTP calls to Keycloak, as an application and an API would make them

    /** The raw access token of the user, by password grant on the login client. */
    private String logIn(String username, String scope) throws Exception {
        return tokenResponse(Map.of("grant_type", "password", "client_id", testRealm.name(),
                "username", username, "password", PASSWORD, "scope", scope)).path("access_token").asText();
    }

    private JsonNode tokenResponse(Map<String, String> form) throws Exception {
        HttpResponse<String> response = post("/protocol/openid-connect/token", form, null);
        assertEquals(200, response.statusCode(), response.body());
        return json.readTree(response.body());
    }

    /** RFC 7662: the API authenticates with its own client credentials (HTTP Basic) and sends the token. */
    private JsonNode introspect(String clientId, String token) throws Exception {
        String basic = Base64.getEncoder().encodeToString((clientId + ":" + API_SECRET).getBytes(StandardCharsets.UTF_8));
        HttpResponse<String> response = post("/protocol/openid-connect/token/introspect", Map.of("token", token), basic);
        assertEquals(200, response.statusCode(), response.body());
        JsonNode introspection = json.readTree(response.body());
        if (!introspection.path("active").asBoolean()) {
            testRealm.resource().getEvents(List.of("INTROSPECT_TOKEN_ERROR"), clientId, null, null, null, null, 0, 1)
                    .forEach(event -> log.info("Introspection refused: {}", event.getDetails().get("reason")));
        }
        return introspection;
    }

    private HttpResponse<String> post(String path, Map<String, String> form, String basicAuth) throws Exception {
        String body = form.entrySet().stream()
                .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(keycloakUrl + "/realms/" + testRealm.name() + path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (basicAuth != null) {
            request.header("Authorization", "Basic " + basicAuth);
        }
        try (HttpClient http = HttpClient.newHttpClient()) {
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    /** header.payload.signature: the payload is base64url encoded JSON. */
    private JsonNode claims(String token) throws Exception {
        return json.readTree(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
    }

    private static Set<String> strings(JsonNode node) {
        Set<String> values = new HashSet<>();
        if (node.isArray()) {
            node.forEach(element -> values.add(element.asText()));
        } else if (!node.isMissingNode()) {
            values.add(node.asText());
        }
        return values;
    }

    private static Set<String> pools(JsonNode quotas) {
        return quotas.findValues("pool").stream().map(JsonNode::asText).collect(Collectors.toSet());
    }
}
