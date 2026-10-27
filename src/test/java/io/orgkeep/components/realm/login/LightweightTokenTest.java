package io.orgkeep.components.realm.login;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.orgkeep.api.CatalogService;
import io.orgkeep.api.LoginClientService;
import io.orgkeep.api.OrgMemberService;
import io.orgkeep.api.OrgService;
import io.orgkeep.api.ProductService;
import io.orgkeep.api.RealmProvisioner;
import io.orgkeep.api.TeamService;
import io.orgkeep.api.UserGroupService;
import io.orgkeep.api.model.AddMemberRequest;
import io.orgkeep.api.model.AssignProductRequest;
import io.orgkeep.api.model.CatalogCredentials;
import io.orgkeep.api.model.CreateCatalogRequest;
import io.orgkeep.api.model.CreateOrgRequest;
import io.orgkeep.api.model.CreateProductRequest;
import io.orgkeep.api.model.CreateUserGroupRequest;
import io.orgkeep.api.model.Feature;
import io.orgkeep.api.model.GrantProductRequest;
import io.orgkeep.api.model.LightweightTokens;
import io.orgkeep.api.model.ParamDefinition;
import io.orgkeep.api.model.ParamType;
import io.orgkeep.api.model.QuotaCycle;
import io.orgkeep.api.model.UpdateLoginClientRequest;
import io.orgkeep.fixtures.CatalogClaims;
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
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.ClientScopeRepresentation;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.UserRepresentation;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real lightweight tokens, and their introspection by the APIs of a catalog (see "Tokens légers" in ARCHITECTURE.md).
 * Started as the spike of the lightweight mode, it checks what Keycloak really does with what Orgkeep sets up:
 * the toggle of the login client (LoginClientService), the marker, the mappers of the products kept out of the
 * lightweight token, and the credentials of the catalog client (CatalogService.regenerateSecret).
 * <p>
 * Two Keycloak rules found by the spike shape the setup:
 * <ul>
 *   <li>a client may only introspect a token whose audience it is in ("Client 'x' is not in the token audience"):
 *       the catalog client is, through "audience resolve", as soon as the token carries one of its roles;</li>
 *   <li>the introspection recomputes the claims from the scopes listed in the "scope" claim: a catalog scope must be
 *       listed there, or its claims are lost. Orgkeep creates them with include.in.token.scope=true (CatalogMapper).</li>
 * </ul>
 * Setup: the model of TeamTokenTest, reduced to one product. "shop-repository" in prod (features scan and flow,
 * parameter region), assigned to acme (1000 a month, scan bought); Paul is in "ops", which gives the product with a
 * quota of team of 300. A second catalog, sandbox, where acme has nothing.
 */
@Slf4j
@QuarkusTest
class LightweightTokenTest {
    private static final String ORG = "acme";
    private static final String PRODUCT = "shop-repository";
    private static final String PASSWORD = "password";
    private static final String MARKER = LightweightTokens.MARKER_CLAIM;

    @Inject Keycloak keycloak;
    @Inject RealmProvisioner realmProvisioner;
    @Inject LoginClientService loginClientService;
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
        // the password grant, for the test only (as DemoSeeder does)
        var loginClient = testRealm.resource().clients().get(loginClientUuid());
        ClientRepresentation client = loginClient.toRepresentation();
        client.setDirectAccessGrantsEnabled(true);
        loginClient.update(client);

        catalogService.create(realm, new CreateCatalogRequest("prod", "Production", null));
        catalogService.create(realm, new CreateCatalogRequest("sandbox", "Sandbox", null));
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

    // the standard claims and the marker, nothing of the catalog: the mappers of the products are out of the
    // lightweight token. RFC 9068 expects iss, exp, aud, sub, client_id, iat, jti
    @Test
    void whatALightweightTokenCarries() throws Exception {
        String full = logIn("paul", "openid prod");
        setLightweightTokens(true);

        String token = logIn("paul", "openid prod");
        JsonNode claims = claims(token);
        log.info("Lightweight token of paul ({} bytes, full one {} bytes): {}", token.length(), full.length(),
                claims.toPrettyString());

        assertTrue(claims.path(MARKER).asBoolean(), claims.toString());
        assertTrue(claims.path("resource_access").isMissingNode(), claims.toString());
        assertTrue(claims.path("realm_access").isMissingNode(), claims.toString());
        assertTrue(CatalogClaims.of(claims, "prod").isEmpty(), claims.toString());
        assertTrue(claims.path("email").isMissingNode(), claims.toString());
        // the scope of the marker is not listed in the scope claim (white label), the catalog is
        assertEquals(Set.of("openid", "profile", "email", "prod"), scopes(claims));
        log.info("RFC 9068 claims present: {}", List.of("iss", "exp", "aud", "sub", "client_id", "iat", "jti").stream()
                .collect(Collectors.toMap(name -> name, name -> !claims.path(name).isMissingNode())));
    }

    // turned off again: full tokens, without the marker
    @Test
    void turnedOffAgain() throws Exception {
        setLightweightTokens(true);
        setLightweightTokens(false);

        JsonNode claims = claims(logIn("paul", "openid prod"));

        assertTrue(claims.path(MARKER).isMissingNode(), claims.toString());
        assertFalse(claims.at("/resource_access/prod").isMissingNode(), claims.toString());
        assertFalse(claims.at("/prod.shop-repository/quotas").isMissingNode(), claims.toString());
    }

    // --- who may introspect: the client of the catalog, for the tokens of its catalog

    // everything the full token had comes back, except the marker
    @Test
    void theCatalogIntrospectsALightweightToken() throws Exception {
        setLightweightTokens(true);
        CatalogCredentials prod = catalogService.regenerateSecret(testRealm.name(), "prod");

        JsonNode introspection = introspect(prod, logIn("paul", "openid prod"));
        log.info("Introspection of the lightweight token by prod: {}", introspection.toPrettyString());

        assertTrue(introspection.path("active").asBoolean(), introspection.toString());
        assertEquals(Set.of(PRODUCT, PRODUCT + ":scan"), strings(introspection.at("/resource_access/prod/roles")));
        assertEquals(Set.of("/", "/ops"), pools(introspection.at("/prod.shop-repository/quotas")));
        assertEquals("eu", introspection.at("/prod.shop-repository/params/region").asText());
        assertFalse(introspection.at("/resource_access/org-acme").isMissingNode(), introspection.toString());
        assertTrue(introspection.path(MARKER).isMissingNode(), introspection.toString());
    }

    // before the switch, the APIs introspect full tokens: the same claims as the token itself
    @Test
    void theCatalogIntrospectsAFullToken() throws Exception {
        CatalogCredentials prod = catalogService.regenerateSecret(testRealm.name(), "prod");
        String token = logIn("paul", "openid prod");

        JsonNode introspection = introspect(prod, token);

        assertTrue(introspection.path("active").asBoolean(), introspection.toString());
        JsonNode claims = claims(token);
        assertEquals(claims.path("resource_access"), introspection.path("resource_access"));
        assertEquals(CatalogClaims.of(claims, "prod"), CatalogClaims.of(introspection, "prod"));
    }

    // a token without the catalog: its client is not in the audience, the API of the catalog gets active: false
    @Test
    void theCatalogDoesNotIntrospectATokenWithoutIt() throws Exception {
        setLightweightTokens(true);
        CatalogCredentials prod = catalogService.regenerateSecret(testRealm.name(), "prod");

        assertFalse(introspect(prod, logIn("paul", "openid")).path("active").asBoolean());
    }

    // the credentials of another catalog (another environment) do not introspect the tokens of prod
    @Test
    void anotherCatalogDoesNotIntrospect() throws Exception {
        setLightweightTokens(true);
        CatalogCredentials sandbox = catalogService.regenerateSecret(testRealm.name(), "sandbox");

        assertFalse(introspect(sandbox, logIn("paul", "openid prod")).path("active").asBoolean());
    }

    // --- what the introspection returns

    // rights changed after the token was issued: the introspection gives the current ones. Here Paul has no role of
    // prod anymore, so prod is no longer in the audience either
    @Test
    void theIntrospectionGivesTheCurrentRights() throws Exception {
        setLightweightTokens(true);
        CatalogCredentials prod = catalogService.regenerateSecret(testRealm.name(), "prod");
        String token = logIn("paul", "openid prod");
        assertTrue(introspect(prod, token).path("active").asBoolean());

        userGroupService.removeMember(testRealm.name(), ORG, "ops", paulId);

        assertFalse(introspect(prod, token).path("active").asBoolean());
    }

    // the user session ended (logout): the token is no longer active, before its exp
    @Test
    void theIntrospectionAfterLogout() throws Exception {
        setLightweightTokens(true);
        CatalogCredentials prod = catalogService.regenerateSecret(testRealm.name(), "prod");
        String token = logIn("paul", "openid prod");
        assertTrue(introspect(prod, token).path("active").asBoolean());

        testRealm.resource().users().get(paulId).logout();

        assertFalse(introspect(prod, token).path("active").asBoolean());
    }

    // a regenerated secret replaces the previous one at once
    @Test
    void theOldSecretStopsWorking() throws Exception {
        CatalogCredentials old = catalogService.regenerateSecret(testRealm.name(), "prod");
        catalogService.regenerateSecret(testRealm.name(), "prod");

        HttpResponse<String> response = post("/protocol/openid-connect/token/introspect",
                Map.of("token", logIn("paul", "openid prod")), basic(old));
        assertEquals(401, response.statusCode(), response.body());
    }

    // --- the token preview and the service accounts

    // the "evaluate" endpoint of the token preview applies the lightweight mode of the client
    @Test
    void theEvaluateEndpointAppliesTheLightweightMode() {
        setLightweightTokens(true);

        JsonNode evaluated = json.copy()
                .setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL)
                .valueToTree(testRealm.resource().clients().get(loginClientUuid()).clientScopesEvaluate()
                        .generateAccessToken("openid prod", paulId, null));

        assertTrue(evaluated.path("resource_access").isMissingNode(), evaluated.toString());
        assertTrue(evaluated.path(MARKER).asBoolean(), evaluated.toString());
    }

    // a service account (client_credentials) with a role of prod: its lightweight token is introspected by prod like a
    // user's. Service accounts do not exist in Orgkeep yet: its client is set up by hand, the way Orgkeep will
    @Test
    void theIntrospectionOfAServiceAccountToken() throws Exception {
        setLightweightTokens(true);
        CatalogCredentials prod = catalogService.regenerateSecret(testRealm.name(), "prod");
        ClientRepresentation batch = new ClientRepresentation();
        batch.setClientId("batch");
        batch.setPublicClient(false);
        batch.setServiceAccountsEnabled(true);
        batch.setStandardFlowEnabled(false);
        batch.setSecret("batch-secret");
        batch.setAttributes(Map.of(LightweightTokens.CLIENT_ATTRIBUTE, "true"));
        String batchUuid;
        try (Response response = testRealm.resource().clients().create(batch)) {
            batchUuid = CreatedResponseUtil.getCreatedId(response);
        }
        var batchClient = testRealm.resource().clients().get(batchUuid);
        batchClient.addDefaultClientScope(scope(MARKER).getId());
        batchClient.addDefaultClientScope(scope("prod").getId());
        String prodUuid = testRealm.resource().clients().findByClientId("prod").get(0).getId();
        String serviceAccountId = batchClient.getServiceAccountUser().getId();
        testRealm.resource().users().get(serviceAccountId).roles().clientLevel(prodUuid)
                .add(List.of(testRealm.resource().clients().get(prodUuid).roles().get(PRODUCT).toRepresentation()));

        String token = tokenResponse(Map.of("grant_type", "client_credentials", "client_id", "batch",
                "client_secret", "batch-secret")).path("access_token").asText();
        JsonNode introspection = introspect(prod, token);

        assertTrue(claims(token).path(MARKER).asBoolean());
        assertTrue(introspection.path("active").asBoolean(), introspection.toString());
        assertEquals(Set.of(PRODUCT), strings(introspection.at("/resource_access/prod/roles")));
    }

    // --- setup helpers

    /** The toggle of the realm owner, through the service: the other settings of the login client are kept. */
    private void setLightweightTokens(boolean on) {
        loginClientService.update(testRealm.name(), new UpdateLoginClientRequest(List.of(), List.of(), List.of(), null, on));
    }

    private ClientScopeRepresentation scope(String name) {
        return testRealm.resource().clientScopes().findAll().stream()
                .filter(scope -> scope.getName().equals(name))
                .findFirst().orElseThrow();
    }

    private String loginClientUuid() {
        return testRealm.resource().clients().findByClientId(testRealm.name()).get(0).getId();
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

    /** RFC 7662: the API authenticates with the credentials of its catalog (HTTP Basic) and sends the token. */
    private JsonNode introspect(CatalogCredentials credentials, String token) throws Exception {
        // the URL given to the realm owner: the Keycloak of Dev Services in test
        assertEquals(keycloakUrl + "/realms/" + testRealm.name() + "/protocol/openid-connect/token/introspect",
                credentials.introspectionUrl());
        HttpResponse<String> response = post("/protocol/openid-connect/token/introspect", Map.of("token", token),
                basic(credentials));
        assertEquals(200, response.statusCode(), response.body());
        JsonNode introspection = json.readTree(response.body());
        if (!introspection.path("active").asBoolean()) {
            testRealm.resource().getEvents(List.of("INTROSPECT_TOKEN_ERROR"), credentials.clientId(), null, null, null,
                            null, 0, 1)
                    .forEach(event -> log.info("Introspection refused: {}", event.getDetails().get("reason")));
        }
        return introspection;
    }

    private static String basic(CatalogCredentials credentials) {
        return Base64.getEncoder().encodeToString((credentials.clientId() + ":" + credentials.clientSecret())
                .getBytes(StandardCharsets.UTF_8));
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

    private static Set<String> scopes(JsonNode claims) {
        return Set.of(claims.path("scope").asText().split(" "));
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
