package org.orgkeep.components.organisation.serviceaccount;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.keycloak.representations.AccessToken;
import org.keycloak.representations.idm.ClientRepresentation;
import org.orgkeep.api.CatalogRoleService;
import org.orgkeep.api.CatalogService;
import org.orgkeep.api.OrgMemberService;
import org.orgkeep.api.OrgService;
import org.orgkeep.api.ProductService;
import org.orgkeep.api.RealmProvisioner;
import org.orgkeep.api.ServiceAccountService;
import org.orgkeep.api.TeamService;
import org.orgkeep.api.UserGroupService;
import org.orgkeep.api.exceptions.OperationNotAllowedException;
import org.orgkeep.api.exceptions.OrgNotFoundException;
import org.orgkeep.api.model.AddMemberRequest;
import org.orgkeep.api.model.AssignProductRequest;
import org.orgkeep.api.model.CreateCatalogRequest;
import org.orgkeep.api.model.CreateCatalogRoleRequest;
import org.orgkeep.api.model.CreateOrgRequest;
import org.orgkeep.api.model.CreateProductRequest;
import org.orgkeep.api.model.CreateServiceAccountRequest;
import org.orgkeep.api.model.CreateUserGroupRequest;
import org.orgkeep.api.model.Feature;
import org.orgkeep.api.model.FeatureQuota;
import org.orgkeep.api.model.GrantProductRequest;
import org.orgkeep.api.model.QuotaCycle;
import org.orgkeep.api.model.RoleHolder;
import org.orgkeep.api.model.ServiceAccountCreated;
import org.orgkeep.api.model.UserGroup;
import org.orgkeep.fixtures.CatalogClaims;
import org.orgkeep.fixtures.TestRealm;
import org.orgkeep.repository.keycloak.KeycloakClients;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Service accounts in teams, on real tokens: a service account in a team gets what a person of that team gets, and a
 * reserved catalog role can be given to it directly.
 * <p>
 * Setup: catalog "prod", a simple product "shop" (org quota 1000 a month) and a metered one "billing" (search bought,
 * 200 a day), assigned to acme. The team "ops" gives shop with a team quota of 300, the team "accounting" gives billing.
 * Paul, a person, is in both, the service account "batch" too: their tokens must carry the same catalog claims.
 * <p>
 * One realm for the whole class (@BeforeAll); the fixture is rebuilt before every test (@BeforeEach) and torn
 * down after it (@AfterEach), since some tests move the service account out of its teams.
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ServiceAccountTeamsTest {
    private static final String ORG = "acme";

    @Inject RealmProvisioner realmProvisioner;
    @Inject CatalogService catalogService;
    @Inject CatalogRoleService catalogRoleService;
    @Inject ProductService productService;
    @Inject OrgService orgService;
    @Inject OrgMemberService memberService;
    @Inject UserGroupService userGroupService;
    @Inject TeamService teamService;
    @Inject ServiceAccountService serviceAccountService;
    @Inject KeycloakClients clients;

    @ConfigProperty(name = "quarkus.keycloak.admin-client.server-url")
    String keycloakUrl;

    private final ObjectMapper json = new ObjectMapper();
    private TestRealm testRealm;
    private String paulId;
    private ServiceAccountCreated batch;

    @BeforeAll
    void createRealm() {
        testRealm = TestRealm.create();
    }

    @BeforeEach
    void setUpFixture() {
        String realm = testRealm.name();
        realmProvisioner.ensureDefaults(realm);
        catalogService.create(realm, new CreateCatalogRequest("prod", "Production", null));
        productService.create(realm, "prod", new CreateProductRequest("shop", "Shop", null));
        productService.create(realm, "prod", new CreateProductRequest("billing", "Billing", null, List.of(
                new Feature("search", null, true), new Feature("dig", null, true)), List.of()));
        orgService.create(realm, new CreateOrgRequest(ORG, Set.of(), null, null));
        productService.assign(realm, ORG, "prod", "shop", new AssignProductRequest(1000L, QuotaCycle.MONTHLY));
        productService.assign(realm, ORG, "prod", "billing", AssignProductRequest.metered(List.of("search"),
                Map.of("search", new FeatureQuota(200L, QuotaCycle.DAILY))));

        paulId = memberService.add(realm, ORG, new AddMemberRequest("paul@acme.test", "Paul", "Test")).id();
        userGroupService.create(realm, ORG, new CreateUserGroupRequest("ops"));
        userGroupService.create(realm, ORG, new CreateUserGroupRequest("accounting"));
        teamService.grantProduct(realm, ORG, "ops", "prod", "shop", new GrantProductRequest(null, 300L, null));
        teamService.grantProduct(realm, ORG, "accounting", "prod", "billing", GrantProductRequest.allFeatures());
        batch = serviceAccountService.create(realm, ORG, new CreateServiceAccountRequest("batch", null, null));
        for (String team : List.of("ops", "accounting")) {
            userGroupService.addMember(realm, ORG, team, paulId);
            userGroupService.addServiceAccount(realm, ORG, team, "batch");
        }
    }

    @AfterEach
    void cleanUp() {
        try {
            orgService.delete(realm(), ORG);
        } catch (OrgNotFoundException ignored) {
            // not created by this test
        }
        testRealm.resource().clients().findByClientId("prod")
                .forEach(client -> testRealm.resource().clients().get(client.getId()).remove());
        testRealm.resource().clientScopes().findAll().stream()
                .filter(scope -> "prod".equals(scope.getName()))
                .forEach(scope -> testRealm.resource().clientScopes().get(scope.getId()).remove());
        testRealm.resource().clients().findByClientId("console-client")
                .forEach(client -> testRealm.resource().clients().get(client.getId()).remove());
        testRealm.resource().users().list(0, 1000)
                .forEach(user -> testRealm.resource().users().get(user.getId()).remove());
    }

    @AfterAll
    void deleteRealm() {
        testRealm.close();
    }

    // a simple product with a team quota and a metered product: the claims of the catalog are those of a person of the
    // same teams, the pool of the org and the pool of the team included
    @Test
    void aServiceAccountGetsWhatAPersonOfTheTeamsGets() throws Exception {
        JsonNode sa = serviceAccountToken("prod");
        AccessToken paul = clients.exampleAccessToken(realm(), loginClientUuid(), paulId, "openid prod");

        assertEquals(Set.of("shop", "billing:search"), strings(sa.at("/resource_access/prod/roles")));
        assertEquals(Set.of("shop", "billing:search"), paul.getResourceAccess("prod").getRoles());
        JsonNode shopQuotas = sa.at("/prod.shop/quotas");
        assertEquals(Set.of("/", "/ops"), pools(shopQuotas));
        assertEquals(Set.of("prod.shop", "prod.billing.search"), catalogClaimNames(sa));
        assertEquals("/", sa.at("/prod.billing.search/quotas/0/pool").asText());
        assertEquals(200, sa.at("/prod.billing.search/quotas/0/limit").asInt());
        // the very same claims, for the person and for the service account
        JsonNode paulClaims = json.valueToTree(paul.getOtherClaims());
        assertEquals(CatalogClaims.of(paulClaims, "prod"), CatalogClaims.of(sa, "prod"));
        assertEquals("[\"acme\"]", sa.path("organization").toString());
    }

    // out of the teams, nothing of the catalog is left
    @Test
    void leavingTheTeamsTakesTheProductsBack() throws Exception {
        userGroupService.removeServiceAccount(realm(), ORG, "ops", "batch");
        userGroupService.removeServiceAccount(realm(), ORG, "accounting", "batch");

        JsonNode sa = serviceAccountToken("prod");

        assertTrue(sa.at("/resource_access/prod").isMissingNode(), sa.toString());
        assertTrue(CatalogClaims.of(sa, "prod").isEmpty(), sa.toString());
    }

    // the list of the teams shows the service accounts apart from the people, and the service account its teams
    @Test
    void teamsAndServiceAccountShowTheLink() {
        UserGroup ops = userGroupService.list(realm(), ORG).stream().filter(team -> team.name().equals("ops")).findFirst().orElseThrow();

        assertEquals(Set.of(paulId), ops.memberIds());
        assertEquals(List.of("batch"), ops.serviceAccounts());
        assertEquals(List.of("accounting", "ops"), serviceAccountService.get(realm(), ORG, "batch").teams());
        assertEquals(List.of("accounting", "ops"), serviceAccountService.list(realm(), ORG).getFirst().teams());
        // the predefined teams hold people only
        assertEquals(List.of(), userGroupService.list(realm(), ORG).stream()
                .filter(team -> team.name().equals("users")).findFirst().orElseThrow().serviceAccounts());
    }

    // a service account never holds an org role: the groups admin and users are refused, and nothing was written
    @Test
    void adminAndUsersAreRefused() {
        for (String team : List.of("admin", "users")) {
            assertThrowsExactly(OperationNotAllowedException.class,
                    () -> userGroupService.addServiceAccount(realm(), ORG, team, "batch"));
            assertThrowsExactly(OperationNotAllowedException.class,
                    () -> userGroupService.removeServiceAccount(realm(), ORG, team, "batch"));
        }
        assertEquals(Set.of("/acme/service-accounts", "/acme/user-groups/ops", "/acme/user-groups/accounting"),
                testRealm.groupPaths(batch.serviceAccount().userId()));
    }

    // --- a reserved catalog role, granted directly

    @Test
    void reservedRoleGrantedToAServiceAccountReachesItsTokenAndShowsInTheHolders() throws Exception {
        catalogRoleService.create(realm(), "prod", new CreateCatalogRoleRequest("anonymous", null, null, false));
        String userId = batch.serviceAccount().userId();

        catalogRoleService.grant(realm(), "prod", "anonymous", userId);

        assertTrue(strings(serviceAccountToken("prod").at("/resource_access/prod/roles")).contains("anonymous"));
        List<RoleHolder> holders = catalogRoleService.holders(realm(), "prod", "anonymous");
        assertEquals(1, holders.size());
        RoleHolder holder = holders.getFirst();
        assertEquals(userId, holder.userId());
        assertEquals("org-acme.batch", holder.serviceAccount());
        assertEquals(ORG, holder.org());
        assertEquals(null, holder.email());

        catalogRoleService.revoke(realm(), "prod", "anonymous", userId);
        assertFalse(strings(serviceAccountToken("prod").at("/resource_access/prod/roles")).contains("anonymous"));
    }

    // the user of a client of the console, put in an organisation by hand, is no service account of Orgkeep: refused,
    // without the group of the org and the attribute of its client
    @Test
    void userOfAConsoleClientAddedToAnOrgIsRefused() {
        catalogRoleService.create(realm(), "prod", new CreateCatalogRoleRequest("anonymous", null, null, false));
        String uuid = testRealm.createClient("console-client");
        ClientRepresentation rep = testRealm.resource().clients().get(uuid).toRepresentation();
        rep.setServiceAccountsEnabled(true);
        testRealm.resource().clients().get(uuid).update(rep);
        String userId = testRealm.resource().clients().get(uuid).getServiceAccountUser().getId();
        String orgId = testRealm.resource().organizations().search(ORG, true, 0, 1).getFirst().getId();
        testRealm.resource().organizations().get(orgId).members().addMember(userId).close();

        assertThrowsExactly(OperationNotAllowedException.class,
                () -> catalogRoleService.grant(realm(), "prod", "anonymous", userId));

        // with the attribute only, still not: the group of the org is the other half of the proof
        rep = testRealm.resource().clients().get(uuid).toRepresentation();
        rep.setAttributes(Map.of("org", ORG));
        testRealm.resource().clients().get(uuid).update(rep);
        assertThrowsExactly(OperationNotAllowedException.class,
                () -> catalogRoleService.grant(realm(), "prod", "anonymous", userId));
    }

    // a person is still a person: even with an email that looks like a service account
    @Test
    void reservedRoleStillGoesToAPerson() {
        catalogRoleService.create(realm(), "prod", new CreateCatalogRoleRequest("anonymous", null, null, false));
        String ciId = memberService.add(realm(), ORG, new AddMemberRequest("service-account-ci@acme.test", "Ci", "Test")).id();

        catalogRoleService.grant(realm(), "prod", "anonymous", ciId);

        assertEquals(List.of(ciId), catalogRoleService.holders(realm(), "prod", "anonymous").stream().map(RoleHolder::userId).toList());
        assertEquals(null, catalogRoleService.holders(realm(), "prod", "anonymous").getFirst().serviceAccount());
    }

    // --- helpers

    private JsonNode serviceAccountToken(String scope) throws Exception {
        String clientId = "org-acme.batch";
        String form = "grant_type=client_credentials&scope=" + URLEncoder.encode(scope, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create(
                        keycloakUrl + "/realms/" + realm() + "/protocol/openid-connect/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                        (clientId + ":" + batch.credentials().clientSecret()).getBytes(StandardCharsets.UTF_8)))
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        try (HttpClient http = HttpClient.newHttpClient()) {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), response.body());
            String token = json.readTree(response.body()).path("access_token").asText();
            return json.readTree(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
        }
    }

    private String loginClientUuid() {
        return testRealm.resource().clients().findByClientId(realm()).getFirst().getId();
    }

    private Set<String> catalogClaimNames(JsonNode claims) {
        Set<String> names = new HashSet<>();
        CatalogClaims.of(claims, "prod").properties().forEach(claim -> names.add(claim.getKey()));
        return names;
    }

    private static Set<String> pools(JsonNode quotas) {
        Set<String> pools = new HashSet<>();
        quotas.forEach(quota -> pools.add(quota.path("pool").asText()));
        return pools;
    }

    private static Set<String> strings(JsonNode node) {
        Set<String> values = new HashSet<>();
        if (node.isArray()) {
            node.forEach(element -> values.add(element.asText()));
        }
        return values;
    }

    private String realm() {
        return testRealm.name();
    }
}
