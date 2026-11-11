package org.orgkeep.components.organisation.member.preview;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.orgkeep.api.CatalogService;
import org.orgkeep.api.LoginClientService;
import org.orgkeep.api.OrgMemberService;
import org.orgkeep.api.OrgService;
import org.orgkeep.api.ProductService;
import org.orgkeep.api.RealmProvisioner;
import org.orgkeep.api.TeamService;
import org.orgkeep.api.UserGroupService;
import org.orgkeep.api.model.AddMemberRequest;
import org.orgkeep.api.model.AssignProductRequest;
import org.orgkeep.api.model.CatalogCredentials;
import org.orgkeep.api.model.CreateCatalogRequest;
import org.orgkeep.api.model.CreateOrgRequest;
import org.orgkeep.api.model.CreateProductRequest;
import org.orgkeep.api.model.CreateUserGroupRequest;
import org.orgkeep.api.model.Feature;
import org.orgkeep.api.model.FeatureQuota;
import org.orgkeep.api.model.GrantProductRequest;
import org.orgkeep.api.model.LightweightTokens;
import org.orgkeep.api.model.QuotaCycle;
import org.orgkeep.api.model.UpdateLoginClientRequest;
import org.orgkeep.fixtures.CatalogClaims;
import org.orgkeep.fixtures.TestRealm;
import org.orgkeep.web.CurrentRealm;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.mockito.Mockito;

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

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The token preview of the realm owner, through the REST API, against the Keycloak of Dev Services.
 * <p>
 * The point of the preview is to show what an API really receives: the main test compares it with a real token,
 * asked to Keycloak by password grant on the login client of the realm (as TeamTokenTest does), for the same member
 * and the same catalogs. Comparing with the real thing, rather than with an expected JSON written here, keeps the
 * test right if the token format changes.
 * <p>
 * Setup: catalog "prod" with "billing", a metered product (stats, search metered), bought by acme with stats and search
 * at 200 a day; the team "sales" gives every feature bought; Paul is in sales. Gus is a member of another org, globex.
 * The calls to the preview use @TestSecurity (the caller and its roles, without a token) and a mocked CurrentRealm
 * (the throwaway realm of the test).
 */
@QuarkusTest
class TokenPreviewTest {
    private static final String ORG = "acme";
    private static final String PASSWORD = "password";
    private static final String PREVIEW = "/api/admin/organisations/{org}/members/{userId}/token-preview";

    @Inject Keycloak keycloak;
    @Inject RealmProvisioner realmProvisioner;
    @Inject CatalogService catalogService;
    @Inject LoginClientService loginClientService;
    @Inject ProductService productService;
    @Inject OrgService orgService;
    @Inject OrgMemberService memberService;
    @Inject UserGroupService userGroupService;
    @Inject TeamService teamService;
    @InjectMock CurrentRealm currentRealm;

    @ConfigProperty(name = "quarkus.keycloak.admin-client.server-url")
    String keycloakUrl;

    private TestRealm testRealm;
    private String paulId;
    private String gusId;

    @BeforeEach
    void beforeEach() {
        testRealm = TestRealm.create(keycloak);
        String realm = testRealm.name();
        Mockito.when(currentRealm.name()).thenReturn(realm);
        // the login client of the realm, with the password grant turned on for the real token, as DemoSeeder does
        realmProvisioner.ensureDefaults(realm);
        enablePasswordGrant();

        catalogService.create(realm, new CreateCatalogRequest("prod", "Production", null));
        productService.create(realm, "prod", new CreateProductRequest("billing", "Billing", null,
                List.of(new Feature("stats", null), new Feature("search", null, true)), List.of()));
        orgService.create(realm, new CreateOrgRequest(ORG, Set.of(), null, null));
        orgService.create(realm, new CreateOrgRequest("globex", Set.of(), null, null));
        productService.assign(realm, ORG, "prod", "billing", AssignProductRequest.metered(List.of("stats", "search"),
                Map.of("search", new FeatureQuota(200L, QuotaCycle.DAILY))));

        paulId = member("paul", ORG);
        gusId = member("gus", "globex");
        userGroupService.create(realm, ORG, new CreateUserGroupRequest("sales"));
        teamService.grantProduct(realm, ORG, "sales", "prod", "billing", GrantProductRequest.allFeatures());
        userGroupService.addMember(realm, ORG, "sales", paulId);
    }

    @AfterEach
    void afterEach() {
        testRealm.close();
    }

    // the rights and the claim of the catalog are those of a real token; only the claims come back, not a JWT.
    // Without lightweight tokens, the introspection returns the same claims as the token
    @Test
    @TestSecurity(user = "owner", roles = "realm-owner")
    void previewMatchesARealToken() throws Exception {
        JsonNode response = previewResponse(paulId, "prod");
        JsonNode preview = response.path("token");
        JsonNode real = claims(logIn("paul", "openid prod"));

        assertFalse(response.path("lightweight").asBoolean(), response.toString());
        assertEquals(preview, response.path("introspection"));

        assertEquals(paulId, preview.path("sub").asText(), preview.toString());
        assertEquals(Set.of("billing:stats", "billing:search"), strings(preview.at("/resource_access/prod/roles")));
        assertEquals(strings(real.at("/resource_access/prod/roles")), strings(preview.at("/resource_access/prod/roles")));
        // the claim of the catalog, quotas included: {"billing": {"search": {"quotas": [{"pool": "/", ...}]}}}.
        // Checked present first: two missing claims would compare equal
        assertEquals("/", preview.at("/prod.billing.search/quotas/0/pool").asText(), preview.toString());
        assertEquals(CatalogClaims.of(real, "prod"), CatalogClaims.of(preview, "prod"));
        assertTrue(strings(preview.path("aud")).contains("prod"), preview.path("aud").toString());
        // no null claim: a real token has none (the Keycloak representation has one per standard claim)
        assertTrue(preview.path("nonce").isMissingNode(), preview.toString());
        assertTrue(preview.at("/resource_access/prod").has("roles")
                && !preview.at("/resource_access/prod").has("verify_caller"), preview.toString());
    }

    // without a catalog, nothing of prod: like an application that does not ask for its scope
    @Test
    @TestSecurity(user = "owner", roles = "realm-owner")
    void previewWithoutCatalog() throws Exception {
        JsonNode preview = previewResponse(paulId).path("token");

        assertTrue(preview.at("/resource_access/prod").isMissingNode(), preview.toString());
        assertTrue(CatalogClaims.of(preview, "prod").isEmpty(), preview.toString());
    }

    // lightweight tokens: the token is the lightweight one, the introspection is what a real introspection returns,
    // compared with the introspection of a real token by the API of the catalog
    @Test
    @TestSecurity(user = "owner", roles = "realm-owner")
    void lightweightPreviewMatchesARealIntrospection() throws Exception {
        setLightweightTokens(true);

        JsonNode response = previewResponse(paulId, "prod");
        JsonNode token = response.path("token");
        JsonNode introspection = response.path("introspection");
        JsonNode real = introspect("prod", logIn("paul", "openid prod"));

        assertTrue(response.path("lightweight").asBoolean(), response.toString());
        assertTrue(token.path(LightweightTokens.MARKER_CLAIM).asBoolean(), token.toString());
        assertTrue(token.path("resource_access").isMissingNode(), token.toString());
        assertTrue(real.path("active").asBoolean(), real.toString());
        // checked present first: two missing claims would compare equal
        assertEquals(Set.of("billing:stats", "billing:search"), strings(introspection.at("/resource_access/prod/roles")));
        assertEquals(real.path("resource_access"), introspection.path("resource_access"));
        assertEquals(real.path("realm_access"), introspection.path("realm_access"));
        assertEquals(CatalogClaims.of(real, "prod"), CatalogClaims.of(introspection, "prod"));
        assertEquals(strings(real.path("aud")), strings(introspection.path("aud")));
        assertEquals(Set.of(real.path("scope").asText().split(" ")), Set.of(introspection.path("scope").asText().split(" ")));
        assertEquals(testRealm.name(), introspection.path("azp").asText());
        assertTrue(introspection.path(LightweightTokens.MARKER_CLAIM).isMissingNode(), introspection.toString());
    }

    // the twin of the login client is aligned at each preview: a change of the login client after the first preview
    // (here prod becomes a default catalog) shows at the next one
    @Test
    @TestSecurity(user = "owner", roles = "realm-owner")
    void lightweightPreviewFollowsTheLoginClient() throws Exception {
        setLightweightTokens(true);
        assertTrue(previewResponse(paulId).at("/introspection/resource_access/prod").isMissingNode());

        loginClientService.update(testRealm.name(),
                new UpdateLoginClientRequest(List.of(), List.of(), List.of(), List.of("prod")));

        JsonNode introspection = previewResponse(paulId).path("introspection");
        assertEquals(Set.of("billing:stats", "billing:search"), strings(introspection.at("/resource_access/prod/roles")));
    }

    @Test
    @TestSecurity(user = "owner", roles = "realm-owner")
    void previewErrors() {
        // not a catalog: the names go into the scope parameter, only catalogs are accepted
        given().queryParam("catalog", "profile")
                .when().get(PREVIEW, ORG, paulId)
                .then().statusCode(400);
        // a member of another org, or an unknown org
        given().when().get(PREVIEW, ORG, gusId)
                .then().statusCode(404);
        given().when().get(PREVIEW, "unknown", paulId)
                .then().statusCode(404);
    }

    // the realm owner only: an org admin, even of this org, does not see the shape of the tokens
    @Test
    @TestSecurity(user = "bob", roles = "org-acme:org-admin")
    void orgAdminGets403() {
        given().queryParam("catalog", "prod")
                .when().get(PREVIEW, ORG, paulId)
                .then().statusCode(403);
    }

    private void setLightweightTokens(boolean on) {
        loginClientService.update(testRealm.name(), new UpdateLoginClientRequest(List.of(), List.of(), List.of(), null, on));
    }

    /** {lightweight, token, introspection}, as the REST API answers. */
    private JsonNode previewResponse(String userId, String... catalogs) throws Exception {
        String body = given().queryParam("catalog", (Object[]) catalogs)
                .when().get(PREVIEW, ORG, userId)
                .then().statusCode(200)
                .extract().asString();
        return new ObjectMapper().readTree(body);
    }

    /** The login client of the realm (clientId = realm name) accepts the password grant for the test. */
    private void enablePasswordGrant() {
        var clients = testRealm.resource().clients();
        String uuid = clients.findByClientId(testRealm.name()).get(0).getId();
        ClientRepresentation client = clients.get(uuid).toRepresentation();
        client.setDirectAccessGrantsEnabled(true);
        clients.get(uuid).update(client);
    }

    /** A member who can log in by password grant right away: created first with a password, then added to the org. */
    private String member(String username, String org) {
        UserRepresentation user = new UserRepresentation();
        user.setUsername(username);
        user.setEmail(username + "@" + org + ".test");
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
        return memberService.add(testRealm.name(), org,
                new AddMemberRequest(user.getEmail(), user.getFirstName(), user.getLastName())).id();
    }

    /** A real access token of the login client, as an API of the realm owner receives it. */
    private String logIn(String username, String scope) throws Exception {
        HttpResponse<String> response = post("/protocol/openid-connect/token", Map.of(
                "grant_type", "password",
                "client_id", testRealm.name(),
                "username", username,
                "password", PASSWORD,
                "scope", scope), null);
        assertEquals(200, response.statusCode(), response.body());
        return new ObjectMapper().readTree(response.body()).path("access_token").asText();
    }

    /** The introspection of the token by the API of the catalog, with the credentials of the catalog. */
    private JsonNode introspect(String catalog, String token) throws Exception {
        CatalogCredentials credentials = catalogService.regenerateSecret(testRealm.name(), catalog);
        String basic = Base64.getEncoder().encodeToString((credentials.clientId() + ":" + credentials.clientSecret())
                .getBytes(StandardCharsets.UTF_8));
        HttpResponse<String> response = post("/protocol/openid-connect/token/introspect", Map.of("token", token), basic);
        assertEquals(200, response.statusCode(), response.body());
        return new ObjectMapper().readTree(response.body());
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
    private static JsonNode claims(String token) throws Exception {
        return new ObjectMapper().readTree(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
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
}
