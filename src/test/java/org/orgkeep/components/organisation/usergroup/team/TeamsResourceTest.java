package org.orgkeep.components.organisation.usergroup.team;

import org.orgkeep.api.CatalogRoleService;
import org.orgkeep.api.CatalogService;
import org.orgkeep.api.OrgService;
import org.orgkeep.api.ProductService;
import org.orgkeep.api.UserGroupService;
import org.orgkeep.api.model.AssignProductRequest;
import org.orgkeep.api.model.CreateCatalogRequest;
import org.orgkeep.api.model.CreateCatalogRoleRequest;
import org.orgkeep.api.model.CreateOrgRequest;
import org.orgkeep.api.model.CreateProductRequest;
import org.orgkeep.api.model.CreateUserGroupRequest;
import org.orgkeep.api.model.Feature;
import org.orgkeep.api.model.FeatureQuota;
import org.orgkeep.api.model.QuotaCycle;
import org.orgkeep.fixtures.TestRealm;
import org.orgkeep.web.CurrentRealm;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

/**
 * The REST side of the teams: security, status codes and JSON. What is written in Keycloak is checked by
 * TeamServiceTest. The roles in @TestSecurity use the form produced by OrgRolesAugmentor: "org-{org}:{role}".
 */
@QuarkusTest
class TeamsResourceTest {
    private static final String ORG = "it-teams-org";
    private static final String TEAM_PRODUCT = "/api/organisations/{org}/user-groups/{group}/products/{catalog}/{product}";
    private static final String TEAM_ROLE = "/api/organisations/{org}/user-groups/{group}/catalogs/{catalog}/roles/{role}";
    private static final String ADMIN = "org-it-teams-org:org-admin";

    @Inject Keycloak keycloak;
    @Inject OrgService orgService;
    @Inject CatalogService catalogService;
    @Inject ProductService productService;
    @Inject CatalogRoleService catalogRoleService;
    @Inject UserGroupService userGroupService;
    @InjectMock CurrentRealm currentRealm;

    private TestRealm testRealm;

    @BeforeEach
    void beforeEach() {
        testRealm = TestRealm.create(keycloak);
        Mockito.when(currentRealm.name()).thenReturn(testRealm.name());
        catalogService.create(testRealm.name(), new CreateCatalogRequest("prod", "Production", null));
        productService.create(testRealm.name(), "prod", new CreateProductRequest("billing", "Billing", null,
                List.of(new Feature("export", "Export")), List.of()));
        catalogRoleService.create(testRealm.name(), "prod", new CreateCatalogRoleRequest("anonymous", null, null, false));
        orgService.create(testRealm.name(), new CreateOrgRequest(ORG, Set.of(), null, null));
        productService.assign(testRealm.name(), ORG, "prod", "billing",
                new AssignProductRequest(1000L, QuotaCycle.MONTHLY, List.of("export"), Map.of()));
        userGroupService.create(testRealm.name(), ORG, new CreateUserGroupRequest("ops"));
    }

    @AfterEach
    void afterEach() {
        testRealm.close();
    }

    @Test
    @TestSecurity(user = "mallory", roles = "org-other-org:org-admin")
    void adminOfAnotherOrgGets403() {
        given().contentType(ContentType.JSON).body("{}")
                .when().put(TEAM_PRODUCT, ORG, "ops", "prod", "billing")
                .then().statusCode(403);
        given().when().get("/api/organisations/{org}/products", ORG)
                .then().statusCode(403);
    }

    // an empty body: every feature the org bought, "features" stays null in the answer (not an empty list)
    @Test
    @TestSecurity(user = "bob", roles = ADMIN)
    void grantAProduct() {
        given().contentType(ContentType.JSON).body("{}")
                .when().put(TEAM_PRODUCT, ORG, "ops", "prod", "billing")
                .then().statusCode(200)
                .body("features", nullValue())
                .body("effectiveFeatures", contains("export"));

        given().contentType(ContentType.JSON).body("{\"features\": [], \"quota\": 10, \"cycle\": \"DAILY\"}")
                .when().put(TEAM_PRODUCT, ORG, "ops", "prod", "billing")
                .then().statusCode(200)
                .body("features.size()", equalTo(0))
                .body("quota", equalTo(10));

        given().when().get("/api/organisations/{org}/user-groups", ORG)
                .then().statusCode(200)
                .body("find { it.name == 'ops' }.products[0].product", equalTo("billing"));

        given().when().delete(TEAM_PRODUCT, ORG, "ops", "prod", "billing")
                .then().statusCode(204);
    }

    // the realm owner manages the teams like an org admin (no switch of account during a demo); a quota of team by
    // metered feature, its cycle defaulting to the one of the org for the feature
    @Test
    @TestSecurity(user = "realm-owner", roles = "realm-owner")
    void realmOwnerGivesAMeteredProductWithAQuotaOfFeature() {
        productService.create(testRealm.name(), "prod", new CreateProductRequest("shop", "Shop", null,
                List.of(new Feature("search", null, true)), List.of()));
        productService.assign(testRealm.name(), ORG, "prod", "shop",
                AssignProductRequest.metered(List.of("search"), Map.of("search", new FeatureQuota(200L, QuotaCycle.DAILY))));

        given().contentType(ContentType.JSON).body("{\"featureQuotas\": {\"search\": {\"quota\": 50}}}")
                .when().put(TEAM_PRODUCT, ORG, "ops", "prod", "shop")
                .then().statusCode(200)
                .body("featureQuotas.search.quota", equalTo(50))
                .body("featureQuotas.search.cycle", equalTo("DAILY"));

        given().when().get("/api/organisations/{org}/user-groups", ORG)
                .then().statusCode(200)
                .body("find { it.name == 'ops' }.products[0].featureQuotas.search.quota", equalTo(50));

        // the quota is required with a quota of feature
        given().contentType(ContentType.JSON).body("{\"featureQuotas\": {\"search\": {\"cycle\": \"DAILY\"}}}")
                .when().put(TEAM_PRODUCT, ORG, "ops", "prod", "shop")
                .then().statusCode(400);
    }

    @Test
    @TestSecurity(user = "bob", roles = ADMIN)
    void grantErrors() {
        given().contentType(ContentType.JSON).body("{\"features\": [\"unknown\"]}")
                .when().put(TEAM_PRODUCT, ORG, "ops", "prod", "billing")
                .then().statusCode(400);
        given().contentType(ContentType.JSON).body("{\"quota\": 0}")
                .when().put(TEAM_PRODUCT, ORG, "ops", "prod", "billing")
                .then().statusCode(400);
        given().contentType(ContentType.JSON).body("{}")
                .when().put(TEAM_PRODUCT, ORG, "ops", "prod", "unknown")
                .then().statusCode(404);
        // reserved to the realm owner
        given().when().put(TEAM_ROLE, ORG, "ops", "prod", "anonymous")
                .then().statusCode(409);
    }

    // what the org admin reads to configure the teams
    @Test
    @TestSecurity(user = "bob", roles = ADMIN)
    void readTheOfferOfTheOrg() {
        given().when().get("/api/organisations/{org}/products", ORG)
                .then().statusCode(200)
                .body("[0].displayName", equalTo("Billing"))
                .body("[0].boughtFeatures[0].displayName", equalTo("Export"));
        given().when().get("/api/organisations/{org}/catalogs", ORG)
                .then().statusCode(200)
                .body("", contains("prod"));
        // "anonymous" is reserved: not listed
        given().when().get("/api/organisations/{org}/catalogs/{catalog}/roles", ORG, "prod")
                .then().statusCode(200)
                .body("size()", equalTo(0));
    }
}
