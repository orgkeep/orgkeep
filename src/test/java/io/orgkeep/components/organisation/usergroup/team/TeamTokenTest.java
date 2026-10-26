package io.orgkeep.components.organisation.usergroup.team;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.orgkeep.api.CatalogRoleService;
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
import io.orgkeep.api.model.CreateCatalogRoleRequest;
import io.orgkeep.api.model.CreateOrgRequest;
import io.orgkeep.api.model.CreateProductRequest;
import io.orgkeep.api.model.CreateUserGroupRequest;
import io.orgkeep.api.model.Feature;
import io.orgkeep.api.model.FeatureQuota;
import io.orgkeep.api.model.GrantProductRequest;
import io.orgkeep.api.model.ParamDefinition;
import io.orgkeep.api.model.ParamType;
import io.orgkeep.api.model.QuotaCycle;
import io.orgkeep.api.model.TeamFeatureQuota;
import io.orgkeep.fixtures.TestRealm;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.CredentialRepresentation;
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
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real tokens on the whole model (the native engine: rights written ahead on the teams, read by native mappers):
 * everything is set up through the
 * services of the core, as the API would, then tokens are asked to Keycloak. No mock: what is checked is what
 * Keycloak really issues from the role mappings and attributes written on the teams.
 * <p>
 * The login client is the real one (provisioned by RealmProvisioner, fullScopeAllowed=false), with the password grant
 * turned on for the test as DemoSeeder does. Setup, the example with several environments:
 * <ul>
 *   <li>catalogs "prod" and "sandbox", optional scopes of the login client;</li>
 *   <li>"shop-repository" in prod (features scan and flow, parameter region), assigned to acme: 1000 a month, scan bought;</li>
 *   <li>catalog roles "scanner" (grantable) and "anonymous" (reserved to the realm owner);</li>
 *   <li>Paul is in "ops" (the product, every feature bought, quota of team 300, scanner) and "analysts" (the product
 *       without feature, no quota); Claire is in no team giving anything.</li>
 * </ul>
 */
@Slf4j
@QuarkusTest
class TeamTokenTest {
    private static final String ORG = "acme";
    private static final String PRODUCT = "shop-repository";
    private static final String BILLING = "billing";
    private static final String PASSWORD = "password";

    @Inject Keycloak keycloak;
    @Inject RealmProvisioner realmProvisioner;
    @Inject CatalogService catalogService;
    @Inject ProductService productService;
    @Inject CatalogRoleService catalogRoleService;
    @Inject OrgService orgService;
    @Inject OrgMemberService memberService;
    @Inject UserGroupService userGroupService;
    @Inject TeamService teamService;

    @ConfigProperty(name = "quarkus.keycloak.admin-client.server-url")
    String keycloakUrl;

    private TestRealm testRealm;
    private String paulId;

    @BeforeEach
    void beforeEach() {
        testRealm = TestRealm.create(keycloak);
        String realm = testRealm.name();
        realmProvisioner.ensureDefaults(realm);
        enablePasswordGrant();

        catalogService.create(realm, new CreateCatalogRequest("prod", "Production", null));
        catalogService.create(realm, new CreateCatalogRequest("sandbox", "Sandbox", null));
        productService.create(realm, "prod", new CreateProductRequest(PRODUCT, "Shop repository", null,
                List.of(new Feature("scan", null), new Feature("flow", null)),
                List.of(new ParamDefinition("region", ParamType.ENUM, true, "eu", List.of("eu", "us")))));
        catalogRoleService.create(realm, "prod", new CreateCatalogRoleRequest("scanner", null, null, true));
        catalogRoleService.create(realm, "prod", new CreateCatalogRoleRequest("anonymous", null, null, false));

        orgService.create(realm, new CreateOrgRequest(ORG, Set.of(), null, null));
        assign(List.of("scan"));

        paulId = member("paul");
        member("claire");
        userGroupService.create(realm, ORG, new CreateUserGroupRequest("ops"));
        userGroupService.create(realm, ORG, new CreateUserGroupRequest("analysts"));
        teamService.grantProduct(realm, ORG, "ops", "prod", PRODUCT, new GrantProductRequest(null, 300L, null));
        teamService.grantCatalogRole(realm, ORG, "ops", "prod", "scanner");
        teamService.grantProduct(realm, ORG, "analysts", "prod", PRODUCT, new GrantProductRequest(List.of(), null, null));
        userGroupService.addMember(realm, ORG, "ops", paulId);
        userGroupService.addMember(realm, ORG, "analysts", paulId);
    }

    @AfterEach
    void afterEach() {
        testRealm.close();
    }

    // the full picture: roles in resource_access, catalog in aud, two pools (the org one only once), parameters
    @Test
    void withTheScopeOfTheCatalog() throws Exception {
        JsonNode claims = logIn("paul", "openid prod");
        log.info("Token of paul with scope=prod: {}", claims.toPrettyString());

        assertEquals(Set.of(PRODUCT, PRODUCT + ":scan", "scanner"), strings(claims.at("/resource_access/prod/roles")));
        assertTrue(strings(claims.path("aud")).contains("prod"), claims.path("aud").toString());
        JsonNode quotas = claims.at("/prod/shop-repository/quotas");
        // ops and analysts both carry the pool of the org: Keycloak keeps it once
        assertEquals(2, quotas.size(), quotas.toString());
        assertEquals(Set.of("acme", "acme/ops"), pools(quotas));
        assertEquals("eu", claims.at("/prod/shop-repository/params/region").asText());
    }

    // without the scope, nothing of the catalog; the roles of the org stay (read by the API of the core)
    @Test
    void withoutTheScopeOfTheCatalog() throws Exception {
        JsonNode claims = logIn("paul", "openid");

        assertTrue(claims.at("/resource_access/prod").isMissingNode(), claims.toString());
        assertTrue(claims.path("prod").isMissingNode(), claims.toString());
        assertFalse(claims.at("/resource_access/org-acme").isMissingNode(), claims.toString());
    }

    // a member in no team giving a product: no catalog in the token, even with its scope
    @Test
    void aMemberWithoutProduct() throws Exception {
        JsonNode claims = logIn("claire", "openid prod");

        assertTrue(claims.at("/resource_access/prod").isMissingNode(), claims.toString());
        assertTrue(claims.path("prod").isMissingNode(), claims.toString());
    }

    // flow is not bought: absent. Bought later, it reaches the token through ops ("every feature bought")
    @Test
    void aFeatureBoughtLaterReachesTheToken() throws Exception {
        assertFalse(strings(logIn("paul", "openid prod").at("/resource_access/prod/roles")).contains(PRODUCT + ":flow"));

        assign(List.of("scan", "flow"));

        assertTrue(strings(logIn("paul", "openid prod").at("/resource_access/prod/roles")).contains(PRODUCT + ":flow"));
    }

    // the scope of a catalog where the org has nothing: Keycloak gives a token without it, no error
    @Test
    void theScopeOfACatalogWithoutAssignment() throws Exception {
        JsonNode claims = logIn("paul", "openid sandbox");

        assertTrue(claims.at("/resource_access/sandbox").isMissingNode(), claims.toString());
        assertTrue(claims.path("sandbox").isMissingNode(), claims.toString());
    }

    // a reserved catalog role, given directly by the realm owner
    @Test
    void aReservedRoleGivenByTheRealmOwner() throws Exception {
        catalogRoleService.grant(testRealm.name(), "prod", "anonymous", paulId);

        assertTrue(strings(logIn("paul", "openid prod").at("/resource_access/prod/roles")).contains("anonymous"));
    }

    // --- a metered product: "billing" in prod (read, stats, scan and flow metered), acme bought stats and scan (200 a day).
    // accounting gives every feature bought, sales gives stats and scan, readers gives stats only.
    // Emma is in accounting and sales, Stan in readers

    // no access role, the features as roles, one pool per metered feature with its feature, the org pool once
    @Test
    void aMeteredProduct() throws Exception {
        setUpBilling();

        JsonNode claims = logIn("emma", "openid prod");
        log.info("Token of emma with scope=prod: {}", claims.toPrettyString());

        Set<String> roles = strings(claims.at("/resource_access/prod/roles"));
        assertEquals(Set.of("billing:stats", "billing:scan"), roles);
        assertTrue(strings(claims.path("aud")).contains("prod"), claims.path("aud").toString());
        // accounting and sales both carry the pool of the org for scan: Keycloak keeps it once
        JsonNode quotas = claims.at("/prod/billing/quotas");
        assertEquals(1, quotas.size(), quotas.toString());
        assertEquals("scan", quotas.get(0).path("feature").asText());
        assertEquals("acme", quotas.get(0).path("pool").asText());
        assertEquals(200, quotas.get(0).path("limit").asInt());
        assertEquals("daily", quotas.get(0).path("cycle").asText());
    }

    // a quota of team for scan: the pool of the team next to the pool of the org, both with the feature
    @Test
    void aQuotaOfTeamForAMeteredFeature() throws Exception {
        setUpBilling();
        teamService.grantProduct(testRealm.name(), ORG, "sales", "prod", BILLING, new GrantProductRequest(
                List.of("stats", "scan"), null, null, Map.of("scan", new TeamFeatureQuota(50L, null))));

        JsonNode quotas = logIn("emma", "openid prod").at("/prod/billing/quotas");

        assertEquals(2, quotas.size(), quotas.toString());
        Map<String, Integer> limits = new HashMap<>();
        quotas.forEach(quota -> {
            assertEquals("scan", quota.path("feature").asText());
            assertEquals("daily", quota.path("cycle").asText());
            limits.put(quota.path("pool").asText(), quota.path("limit").asInt());
        });
        assertEquals(Map.of("acme", 200, "acme/sales", 50), limits);
    }

    // only a feature without quota: the role, and no entry for the product in the claim of the catalog
    @Test
    void onlyAFeatureWithoutQuota() throws Exception {
        setUpBilling();

        JsonNode claims = logIn("stan", "openid prod");

        assertEquals(Set.of("billing:stats"), strings(claims.at("/resource_access/prod/roles")));
        assertTrue(claims.at("/prod/billing").isMissingNode(), claims.toString());
    }

    // a metered feature bought later reaches the token, with its pool, through a team giving every feature bought
    @Test
    void aMeteredFeatureBoughtLaterReachesTheToken() throws Exception {
        setUpBilling();

        productService.assign(testRealm.name(), ORG, "prod", BILLING, AssignProductRequest.metered(List.of("stats", "scan", "flow"),
                Map.of("scan", new FeatureQuota(200L, QuotaCycle.DAILY), "flow", new FeatureQuota(100L, QuotaCycle.MONTHLY))));

        JsonNode claims = logIn("emma", "openid prod");
        assertTrue(strings(claims.at("/resource_access/prod/roles")).contains("billing:flow"), claims.toString());
        assertEquals(Set.of("scan", "flow"), claims.at("/prod/billing/quotas").findValues("feature").stream()
                .map(JsonNode::asText).collect(Collectors.toSet()));
        // a team that listed its features does not get it: readers gives stats only
        assertFalse(strings(logIn("stan", "openid prod").at("/resource_access/prod/roles")).contains("billing:flow"));
    }

    private void setUpBilling() {
        String realm = testRealm.name();
        productService.create(realm, "prod", new CreateProductRequest(BILLING, "Billing", null, List.of(
                new Feature("read", null), new Feature("stats", null),
                new Feature("scan", null, true), new Feature("flow", null, true)), List.of()));
        productService.assign(realm, ORG, "prod", BILLING, AssignProductRequest.metered(List.of("stats", "scan"),
                Map.of("scan", new FeatureQuota(200L, QuotaCycle.DAILY))));

        String emmaId = member("emma");
        String stanId = member("stan");
        for (String team : List.of("accounting", "sales", "readers")) {
            userGroupService.create(realm, ORG, new CreateUserGroupRequest(team));
        }
        teamService.grantProduct(realm, ORG, "accounting", "prod", BILLING, GrantProductRequest.allFeatures());
        teamService.grantProduct(realm, ORG, "sales", "prod", BILLING, new GrantProductRequest(List.of("stats", "scan"), null, null));
        teamService.grantProduct(realm, ORG, "readers", "prod", BILLING, new GrantProductRequest(List.of("stats"), null, null));
        userGroupService.addMember(realm, ORG, "accounting", emmaId);
        userGroupService.addMember(realm, ORG, "sales", emmaId);
        userGroupService.addMember(realm, ORG, "readers", stanId);
    }

    private void assign(List<String> boughtFeatures) {
        productService.assign(testRealm.name(), ORG, "prod", PRODUCT,
                new AssignProductRequest(1000L, QuotaCycle.MONTHLY, boughtFeatures, Map.of()));
    }

    /** The login client of the realm (clientId = realm name) accepts the password grant for the test. */
    private void enablePasswordGrant() {
        var clients = testRealm.resource().clients();
        String uuid = clients.findByClientId(testRealm.name()).get(0).getId();
        ClientRepresentation client = clients.get(uuid).toRepresentation();
        client.setDirectAccessGrantsEnabled(true);
        clients.get(uuid).update(client);
    }

    /**
     * A member of acme who can log in by password grant right away (complete profile, no required action): created
     * first with a password, then added to the org, which reuses the user.
     */
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

    private JsonNode logIn(String username, String scope) throws Exception {
        String form = Map.of(
                        "grant_type", "password",
                        "client_id", testRealm.name(),
                        "username", username,
                        "password", PASSWORD,
                        "scope", scope)
                .entrySet().stream()
                .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(keycloakUrl + "/realms/" + testRealm.name() + "/protocol/openid-connect/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        HttpResponse<String> response;
        try (HttpClient http = HttpClient.newHttpClient()) {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        }
        assertEquals(200, response.statusCode(), response.body());
        String token = new ObjectMapper().readTree(response.body()).path("access_token").asText();
        // header.payload.signature: the payload is base64url encoded JSON
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

    private static Set<String> pools(JsonNode quotas) {
        return quotas.findValues("pool").stream().map(JsonNode::asText).collect(Collectors.toSet());
    }
}
