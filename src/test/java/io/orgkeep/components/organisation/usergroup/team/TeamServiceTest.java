package io.orgkeep.components.organisation.usergroup.team;

import io.orgkeep.api.CatalogRoleService;
import io.orgkeep.api.CatalogService;
import io.orgkeep.api.OrgMemberService;
import io.orgkeep.api.OrgService;
import io.orgkeep.api.ProductService;
import io.orgkeep.api.TeamService;
import io.orgkeep.api.UserGroupService;
import io.orgkeep.api.exceptions.CatalogNotAssignedException;
import io.orgkeep.api.exceptions.InvalidRequestException;
import io.orgkeep.api.exceptions.OperationNotAllowedException;
import io.orgkeep.api.exceptions.ProductNotAssignedException;
import io.orgkeep.api.exceptions.UserGroupNotFoundException;
import io.orgkeep.api.model.AddMemberRequest;
import io.orgkeep.api.model.AssignProductRequest;
import io.orgkeep.api.model.AvailableProduct;
import io.orgkeep.api.model.CatalogRole;
import io.orgkeep.api.model.CreateCatalogRequest;
import io.orgkeep.api.model.CreateCatalogRoleRequest;
import io.orgkeep.api.model.CreateOrgRequest;
import io.orgkeep.api.model.CreateProductRequest;
import io.orgkeep.api.model.CreateUserGroupRequest;
import io.orgkeep.api.model.Feature;
import io.orgkeep.api.model.GrantProductRequest;
import io.orgkeep.api.model.ParamDefinition;
import io.orgkeep.api.model.ParamType;
import io.orgkeep.api.model.QuotaCycle;
import io.orgkeep.api.model.TeamCatalogRole;
import io.orgkeep.api.model.TeamProduct;
import io.orgkeep.api.model.UpdateCatalogRoleRequest;
import io.orgkeep.api.model.UpdateProductRequest;
import io.orgkeep.api.model.UserGroup;
import io.orgkeep.fixtures.TestRealm;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;

/**
 * What the teams give, against the Keycloak of Dev Services, in a throwaway realm per test. The assertions read
 * Keycloak directly: the role mappings and attributes of the group of the team are what the native mappers put in
 * the tokens (the tokens themselves are checked by TeamTokenTest).
 * <p>
 * Common setup: the catalog "prod" with the product "shop-repository" (features scan and flow, an enum parameter
 * region), assigned to the org "acme" with a quota of 1000 a month, the org bought "scan" only. The team "ops" has
 * one member, alice.
 */
@QuarkusTest
class TeamServiceTest {
    private static final String CATALOG = "prod";
    private static final String PRODUCT = "shop-repository";
    private static final String ORG = "acme";
    private static final String TEAM = "ops";
    private static final String QUOTAS = "prod.shop-repository.quotas";
    private static final String PARAMS = "prod.shop-repository.params";
    private static final String ORG_POOL = "{\"pool\":\"acme\",\"limit\":1000,\"cycle\":\"monthly\"}";
    private static final ParamDefinition REGION = new ParamDefinition("region", ParamType.ENUM, true, "eu", List.of("eu", "us"));

    @Inject Keycloak keycloak;
    @Inject TeamService teamService;
    @Inject UserGroupService userGroupService;
    @Inject ProductService productService;
    @Inject CatalogService catalogService;
    @Inject CatalogRoleService catalogRoleService;
    @Inject OrgService orgService;
    @Inject OrgMemberService memberService;

    private TestRealm testRealm;
    private String aliceId;

    @BeforeEach
    void beforeEach() {
        testRealm = TestRealm.create(keycloak);
        catalogService.create(realmName(), new CreateCatalogRequest(CATALOG, "Production", null));
        productService.create(realmName(), CATALOG, new CreateProductRequest(PRODUCT, "Shop repository", null,
                List.of(new Feature("scan", "Scan engine"), new Feature("flow", null)), List.of(REGION)));
        orgService.create(realmName(), new CreateOrgRequest(ORG, Set.of(), null, null));
        assign(List.of("scan"));
        userGroupService.create(realmName(), ORG, new CreateUserGroupRequest(TEAM));
        aliceId = addMember("alice@acme.test");
        userGroupService.addMember(realmName(), ORG, TEAM, aliceId);
    }

    @AfterEach
    void afterEach() {
        testRealm.close();
    }

    // no feature listed: the team gives the product with every feature the org bought, the pool of the org, its parameters
    @Test
    void grantWithoutFeaturesGivesEveryBoughtFeature() {
        TeamProduct given = teamService.grantProduct(realmName(), ORG, TEAM, CATALOG, PRODUCT, GrantProductRequest.allFeatures());

        assertEquals(new TeamProduct(CATALOG, PRODUCT, null, List.of("scan"), null, null), given);
        assertEquals(Set.of(PRODUCT, PRODUCT + ":scan"), teamRoles(TEAM));
        Map<String, List<String>> attributes = team(TEAM).getAttributes();
        assertEquals(List.of(ORG_POOL), attributes.get(QUOTAS));
        assertEquals(List.of("{\"region\":\"eu\"}"), attributes.get(PARAMS));
        assertEquals(List.of("all"), attributes.get("grant:prod:shop-repository"));
    }

    // "every feature bought" follows the purchases: a feature bought later reaches the team without the org admin
    @Test
    void everyBoughtFeatureFollowsThePurchases() {
        teamService.grantProduct(realmName(), ORG, TEAM, CATALOG, PRODUCT, GrantProductRequest.allFeatures());

        assign(List.of("scan", "flow"));

        assertEquals(Set.of(PRODUCT, PRODUCT + ":scan", PRODUCT + ":flow"), teamRoles(TEAM));
    }

    // a listed feature is given only while the org has bought it, and comes back when the org buys it again
    @Test
    void listedFeaturesAreCappedByThePurchases() {
        teamService.grantProduct(realmName(), ORG, TEAM, CATALOG, PRODUCT, new GrantProductRequest(List.of("scan"), null, null));
        assertEquals(Set.of(PRODUCT, PRODUCT + ":scan"), teamRoles(TEAM));

        assign(List.of("flow"));
        assertEquals(Set.of(PRODUCT), teamRoles(TEAM));

        assign(List.of("scan", "flow"));
        // flow is bought but not asked for by the team
        assertEquals(Set.of(PRODUCT, PRODUCT + ":scan"), teamRoles(TEAM));
    }

    // an empty list: the product without any feature (the catalog roles of the user decide what they can do)
    @Test
    void grantWithAnEmptyListGivesTheProductAlone() {
        TeamProduct given = teamService.grantProduct(realmName(), ORG, TEAM, CATALOG, PRODUCT,
                new GrantProductRequest(List.of(), null, null));

        assertEquals(List.of(), given.features());
        assertEquals(Set.of(PRODUCT), teamRoles(TEAM));
    }

    @Test
    void grantRefusesWhatTheOrgDoesNotHave() {
        // never more than the org bought
        assertThrowsExactly(InvalidRequestException.class, () -> teamService.grantProduct(realmName(), ORG, TEAM,
                CATALOG, PRODUCT, new GrantProductRequest(List.of("flow"), null, null)));
        // a cycle alone means nothing
        assertThrowsExactly(InvalidRequestException.class, () -> teamService.grantProduct(realmName(), ORG, TEAM,
                CATALOG, PRODUCT, new GrantProductRequest(null, null, QuotaCycle.DAILY)));
        productService.create(realmName(), CATALOG, new CreateProductRequest("billing", "Billing", null));
        assertThrowsExactly(ProductNotAssignedException.class, () -> teamService.grantProduct(realmName(), ORG, TEAM,
                CATALOG, "billing", GrantProductRequest.allFeatures()));
        assertThrowsExactly(UserGroupNotFoundException.class, () -> teamService.grantProduct(realmName(), ORG, "unknown",
                CATALOG, PRODUCT, GrantProductRequest.allFeatures()));
    }

    // the quota of the team is a second pool, keyed by org/team; its cycle defaults to the one of the org
    @Test
    void aQuotaOfTeamAddsItsPool() {
        TeamProduct given = teamService.grantProduct(realmName(), ORG, TEAM, CATALOG, PRODUCT,
                new GrantProductRequest(null, 300L, null));

        assertEquals(QuotaCycle.MONTHLY, given.cycle());
        assertEquals(Set.of(ORG_POOL, "{\"pool\":\"acme/ops\",\"limit\":300,\"cycle\":\"monthly\"}"),
                Set.copyOf(team(TEAM).getAttributes().get(QUOTAS)));
    }

    // a renegotiation reaches the teams: new pool of the org
    @Test
    void aRenegotiationReachesTheTeams() {
        teamService.grantProduct(realmName(), ORG, TEAM, CATALOG, PRODUCT, GrantProductRequest.allFeatures());

        productService.assign(realmName(), ORG, CATALOG, PRODUCT,
                new AssignProductRequest(50L, QuotaCycle.DAILY, List.of("scan"), Map.of("region", "us")));

        Map<String, List<String>> attributes = team(TEAM).getAttributes();
        assertEquals(List.of("{\"pool\":\"acme\",\"limit\":50,\"cycle\":\"daily\"}"), attributes.get(QUOTAS));
        assertEquals(List.of("{\"region\":\"us\"}"), attributes.get(PARAMS));
    }

    // for a product, a member is in at most one team with a quota: refused when the quota is set...
    @Test
    void aMemberHasOneQuotaOfTeamPerProductWhenTheQuotaIsSet() {
        teamService.grantProduct(realmName(), ORG, TEAM, CATALOG, PRODUCT, new GrantProductRequest(null, 300L, null));
        userGroupService.create(realmName(), ORG, new CreateUserGroupRequest("analysts"));
        userGroupService.addMember(realmName(), ORG, "analysts", aliceId);

        // a team without quota is fine
        teamService.grantProduct(realmName(), ORG, "analysts", CATALOG, PRODUCT, GrantProductRequest.allFeatures());
        assertThrowsExactly(OperationNotAllowedException.class, () -> teamService.grantProduct(realmName(), ORG,
                "analysts", CATALOG, PRODUCT, new GrantProductRequest(null, 100L, null)));
    }

    // ...and when the member joins a second team with a quota
    @Test
    void aMemberHasOneQuotaOfTeamPerProductWhenJoining() {
        teamService.grantProduct(realmName(), ORG, TEAM, CATALOG, PRODUCT, new GrantProductRequest(null, 300L, null));
        userGroupService.create(realmName(), ORG, new CreateUserGroupRequest("analysts"));
        teamService.grantProduct(realmName(), ORG, "analysts", CATALOG, PRODUCT, new GrantProductRequest(null, 100L, null));

        assertThrowsExactly(OperationNotAllowedException.class,
                () -> userGroupService.addMember(realmName(), ORG, "analysts", aliceId));
        // bob is in no team with a quota
        userGroupService.addMember(realmName(), ORG, "analysts", addMember("bob@acme.test"));
    }

    @Test
    void revokeTakesEverythingAway() {
        teamService.grantProduct(realmName(), ORG, TEAM, CATALOG, PRODUCT, new GrantProductRequest(null, 300L, null));

        teamService.revokeProduct(realmName(), ORG, TEAM, CATALOG, PRODUCT);
        // idempotent
        teamService.revokeProduct(realmName(), ORG, TEAM, CATALOG, PRODUCT);

        assertEquals(Set.of(), teamRoles(TEAM));
        assertEquals(Map.of(), teamAttributes(TEAM));
    }

    // unassigned: the teams lose the product, and the catalog roles of a catalog where the org has nothing left
    @Test
    void unassignTakesTheProductAndTheCatalogRolesAway() {
        catalogRoleService.create(realmName(), CATALOG, new CreateCatalogRoleRequest("scanner", null, null, true));
        teamService.grantProduct(realmName(), ORG, TEAM, CATALOG, PRODUCT, GrantProductRequest.allFeatures());
        teamService.grantCatalogRole(realmName(), ORG, TEAM, CATALOG, "scanner");
        assertEquals(Set.of(PRODUCT, PRODUCT + ":scan", "scanner"), teamRoles(TEAM));

        productService.unassign(realmName(), ORG, CATALOG, PRODUCT);

        assertEquals(Set.of(), teamRoles(TEAM));
        assertEquals(Map.of(), teamAttributes(TEAM));
    }

    // a feature deleted by the realm owner: Keycloak takes it from the groups, the team forgets it in its choices
    @Test
    void aDeletedFeatureIsForgotten() {
        teamService.grantProduct(realmName(), ORG, TEAM, CATALOG, PRODUCT, new GrantProductRequest(List.of("scan"), null, null));

        productService.deleteFeature(realmName(), CATALOG, PRODUCT, "scan");

        assertEquals(Set.of(PRODUCT), teamRoles(TEAM));
        assertNull(team(TEAM).getAttributes().get("grant:prod:shop-repository:features"));
    }

    // a new parameter of the schema reaches the teams with its default
    @Test
    void aNewParameterReachesTheTeams() {
        teamService.grantProduct(realmName(), ORG, TEAM, CATALOG, PRODUCT, GrantProductRequest.allFeatures());

        productService.update(realmName(), CATALOG, PRODUCT, new UpdateProductRequest("Shop repository", null,
                List.of(REGION, new ParamDefinition("seats", ParamType.NUMBER, false, "10", List.of()))));

        // typed after the schema: a number is a JSON number
        assertEquals(List.of("{\"region\":\"eu\",\"seats\":10}"), team(TEAM).getAttributes().get(PARAMS));
    }

    // Keycloak stores a group attribute in 255 characters: the parameters of an org must fit in one value
    @Test
    void parametersLongerThanAnAttributeAreRefused() {
        List<ParamDefinition> schema = List.of("a", "b", "c").stream()
                .map(name -> new ParamDefinition(name, ParamType.STRING, false, null, List.of()))
                .toList();
        productService.create(realmName(), CATALOG, new CreateProductRequest("notes", "Notes", null, List.of(), schema));
        String hundred = "x".repeat(100);

        assertThrowsExactly(InvalidRequestException.class, () -> productService.assign(realmName(), ORG, CATALOG, "notes",
                new AssignProductRequest(10L, QuotaCycle.DAILY, List.of(), Map.of("a", hundred, "b", hundred, "c", hundred))));
    }

    @Test
    void catalogRolesOfATeam() {
        catalogRoleService.create(realmName(), CATALOG, new CreateCatalogRoleRequest("stats", null, null, true));
        catalogRoleService.create(realmName(), CATALOG, new CreateCatalogRoleRequest("anonymous", null, null, false));

        teamService.grantCatalogRole(realmName(), ORG, TEAM, CATALOG, "stats");
        // idempotent
        teamService.grantCatalogRole(realmName(), ORG, TEAM, CATALOG, "stats");

        assertEquals(Set.of("stats"), teamRoles(TEAM));
        assertEquals(List.of("stats"), teamService.grantableRoles(realmName(), ORG, CATALOG).stream()
                .map(CatalogRole::name).toList());
        // reserved to the realm owner
        assertThrowsExactly(OperationNotAllowedException.class,
                () -> teamService.grantCatalogRole(realmName(), ORG, TEAM, CATALOG, "anonymous"));

        teamService.revokeCatalogRole(realmName(), ORG, TEAM, CATALOG, "stats");
        assertEquals(Set.of(), teamRoles(TEAM));
    }

    // only in a catalog where the org has a product
    @Test
    void catalogRolesOnlyInACatalogOfTheOrg() {
        catalogService.create(realmName(), new CreateCatalogRequest("sandbox", "Sandbox", null));
        catalogRoleService.create(realmName(), "sandbox", new CreateCatalogRoleRequest("stats", null, null, true));

        assertThrowsExactly(OperationNotAllowedException.class,
                () -> teamService.grantCatalogRole(realmName(), ORG, TEAM, "sandbox", "stats"));
        assertThrowsExactly(CatalogNotAssignedException.class,
                () -> teamService.grantableRoles(realmName(), ORG, "sandbox"));
        assertEquals(List.of(CATALOG), teamService.catalogs(realmName(), ORG));
    }

    // made reserved to the realm owner: the teams lose it, a user holding it directly keeps it
    @Test
    void aRoleNoLongerGrantableLeavesTheTeams() {
        catalogRoleService.create(realmName(), CATALOG, new CreateCatalogRoleRequest("stats", null, null, true));
        teamService.grantCatalogRole(realmName(), ORG, TEAM, CATALOG, "stats");
        catalogRoleService.grant(realmName(), CATALOG, "stats", aliceId);

        catalogRoleService.update(realmName(), CATALOG, "stats", new UpdateCatalogRoleRequest(null, null, false));

        assertEquals(Set.of(), teamRoles(TEAM));
        assertEquals(1, catalogRoleService.holders(realmName(), CATALOG, "stats").size());
    }

    // the list of the user groups says what each one gives
    @Test
    void userGroupsExposeWhatTheyGive() {
        catalogRoleService.create(realmName(), CATALOG, new CreateCatalogRoleRequest("stats", null, null, true));
        teamService.grantProduct(realmName(), ORG, TEAM, CATALOG, PRODUCT, new GrantProductRequest(List.of("scan"), 300L, QuotaCycle.DAILY));
        teamService.grantCatalogRole(realmName(), ORG, TEAM, CATALOG, "stats");

        UserGroup ops = userGroupService.list(realmName(), ORG).stream()
                .filter(group -> TEAM.equals(group.name()))
                .findFirst().orElseThrow();

        assertEquals(List.of(new TeamProduct(CATALOG, PRODUCT, List.of("scan"), List.of("scan"), 300L, QuotaCycle.DAILY)),
                ops.products());
        assertEquals(List.of(new TeamCatalogRole(CATALOG, "stats")), ops.catalogRoles());
        assertEquals(Set.of(aliceId), ops.memberIds());
    }

    // what the org admins see of the products of their org: display names included
    @Test
    void availableProducts() {
        assertEquals(List.of(new AvailableProduct(CATALOG, PRODUCT, "Shop repository", 1000L, QuotaCycle.MONTHLY,
                List.of(new Feature("scan", "Scan engine")))), teamService.availableProducts(realmName(), ORG));
    }

    private void assign(List<String> boughtFeatures) {
        productService.assign(realmName(), ORG, CATALOG, PRODUCT,
                new AssignProductRequest(1000L, QuotaCycle.MONTHLY, boughtFeatures, Map.of()));
    }

    private String addMember(String email) {
        return memberService.add(realmName(), ORG, new AddMemberRequest(email, null, null)).id();
    }

    private String realmName() {
        return testRealm.name();
    }

    private RealmResource realm() {
        return testRealm.resource();
    }

    private GroupRepresentation team(String name) {
        return realm().getGroupByPath("/" + ORG + "/user-groups/" + name);
    }

    /** Keycloak answers null or an empty map for a group without attributes. */
    private Map<String, List<String>> teamAttributes(String name) {
        Map<String, List<String>> attributes = team(name).getAttributes();
        return attributes == null ? Map.of() : attributes;
    }

    private Set<String> teamRoles(String name) {
        String catalogUuid = realm().clients().findByClientId(CATALOG).get(0).getId();
        return realm().groups().group(team(name).getId()).roles().clientLevel(catalogUuid).listAll().stream()
                .map(RoleRepresentation::getName)
                .collect(Collectors.toSet());
    }
}
