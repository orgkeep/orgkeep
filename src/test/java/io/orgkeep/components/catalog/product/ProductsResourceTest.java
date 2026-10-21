package io.orgkeep.components.catalog.product;

import io.orgkeep.api.CatalogService;
import io.orgkeep.api.OrgService;
import io.orgkeep.api.model.AssignProductRequest;
import io.orgkeep.api.model.CreateCatalogRequest;
import io.orgkeep.api.model.CreateOrgRequest;
import io.orgkeep.api.model.CreateProductRequest;
import io.orgkeep.api.model.Feature;
import io.orgkeep.api.model.ParamDefinition;
import io.orgkeep.api.model.ParamType;
import io.orgkeep.api.model.QuotaCycle;
import io.orgkeep.api.model.UpdateFeatureRequest;
import io.orgkeep.fixtures.TestRealm;
import io.orgkeep.web.CurrentRealm;
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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

/**
 * Products and their assignment through the REST API: security, validation and status codes.
 * The behaviour in Keycloak is covered by ProductServiceTest.
 */
@QuarkusTest
class ProductsResourceTest {
    private static final String PRODUCTS = "/api/admin/catalogs/{catalog}/products";
    private static final String ASSIGNMENT = "/api/admin/organisations/{org}/catalogs/{catalog}/products/{product}";
    private static final String CATALOG = "prod";
    private static final String ORG = "acme";

    @Inject Keycloak keycloak;
    @Inject CatalogService catalogService;
    @Inject OrgService orgService;
    @InjectMock CurrentRealm currentRealm;

    private TestRealm testRealm;

    @BeforeEach
    void beforeEach() {
        testRealm = TestRealm.create(keycloak);
        Mockito.when(currentRealm.name()).thenReturn(testRealm.name());
        catalogService.create(testRealm.name(), new CreateCatalogRequest(CATALOG, "Production", null));
        orgService.create(testRealm.name(), new CreateOrgRequest(ORG, Set.of(), null, null));
    }

    @AfterEach
    void afterEach() {
        testRealm.close();
    }

    @Test
    @TestSecurity(user = "alice", roles = "org-acme:org-admin")
    void orgAdminGets403() {
        given().when().get(PRODUCTS, CATALOG)
                .then().statusCode(403);
        given().contentType(ContentType.JSON).body(new AssignProductRequest(10L, QuotaCycle.DAILY))
                .when().put(ASSIGNMENT, ORG, CATALOG, "billing")
                .then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "owner", roles = "realm-owner")
    void createReturns201ThenGetAndList() {
        createBilling();

        given().when().get(PRODUCTS + "/{product}", CATALOG, "billing")
                .then().statusCode(200)
                .body("catalog", equalTo(CATALOG))
                .body("features.name", contains("read", "write"))
                .body("params[0].type", equalTo("ENUM"))
                .body("orgCount", equalTo(0));
        given().when().get(PRODUCTS, CATALOG)
                .then().statusCode(200)
                .body("name", contains("billing"));
        given().when().get(PRODUCTS, "unknown")
                .then().statusCode(404);
        createBillingRequest().then().statusCode(409);
    }

    // slugs only: a ":" would clash with the "<product>:<feature>" names of the features
    @Test
    @TestSecurity(user = "owner", roles = "realm-owner")
    void invalidNamesReturn400() {
        given().contentType(ContentType.JSON).body(new CreateProductRequest("billing:read", "Billing", null))
                .when().post(PRODUCTS, CATALOG)
                .then().statusCode(400);
        given().contentType(ContentType.JSON).body(new CreateProductRequest("billing", "Billing", null,
                        List.of(new Feature("Read", null)), List.of()))
                .when().post(PRODUCTS, CATALOG)
                .then().statusCode(400);
        createBilling();
        given().contentType(ContentType.JSON).body(new UpdateFeatureRequest(null))
                .when().put(PRODUCTS + "/{product}/features/{feature}", CATALOG, "billing", "export:all")
                .then().statusCode(400);
    }

    @Test
    @TestSecurity(user = "owner", roles = "realm-owner")
    void assignListUnassign() {
        createBilling();

        given().contentType(ContentType.JSON)
                .body(new AssignProductRequest(500L, QuotaCycle.MONTHLY, List.of("read"), Map.of("region", "us")))
                .when().put(ASSIGNMENT, ORG, CATALOG, "billing")
                .then().statusCode(200)
                .body("boughtFeatures", contains("read"))
                .body("params.region", equalTo("us"));
        given().when().get("/api/admin/organisations/{org}/products", ORG)
                .then().statusCode(200)
                .body("product", contains("billing"));
        // the assignment resources have their own, more specific paths: the organisation itself is still found
        given().when().get("/api/admin/organisations/{org}", ORG)
                .then().statusCode(200)
                .body("name", equalTo(ORG));
        given().when().get("/api/admin/organisations?catalog={catalog}&product={product}", CATALOG, "billing")
                .then().statusCode(200)
                .body("name", contains(ORG))
                .body("[0].products", contains("prod/billing"));

        given().when().delete(PRODUCTS + "/{product}", CATALOG, "billing")
                .then().statusCode(409);
        given().when().delete(ASSIGNMENT, ORG, CATALOG, "billing")
                .then().statusCode(204);
        given().when().delete(ASSIGNMENT, ORG, CATALOG, "billing")
                .then().statusCode(404);
        given().when().delete(PRODUCTS + "/{product}", CATALOG, "billing")
                .then().statusCode(204);
    }

    // "metered" read from the JSON body; a first metered feature on an assigned product answers 409
    @Test
    @TestSecurity(user = "owner", roles = "realm-owner")
    void meteredFeatures() {
        createBilling();
        given().contentType(ContentType.JSON).body("{\"metered\": true}")
                .when().put(PRODUCTS + "/{product}/features/{feature}", CATALOG, "billing", "scan")
                .then().statusCode(200)
                .body("metered", equalTo(true))
                .body("features.find { it.name == 'scan' }.metered", equalTo(true));
        given().when().delete(PRODUCTS + "/{product}/features/{feature}", CATALOG, "billing", "scan")
                .then().statusCode(200)
                .body("metered", equalTo(false));

        given().contentType(ContentType.JSON).body(new AssignProductRequest(10L, QuotaCycle.DAILY))
                .when().put(ASSIGNMENT, ORG, CATALOG, "billing")
                .then().statusCode(200);
        given().contentType(ContentType.JSON).body("{\"metered\": true}")
                .when().put(PRODUCTS + "/{product}/features/{feature}", CATALOG, "billing", "scan")
                .then().statusCode(409)
                .body("message", containsString("billing"));
    }

    // the JSON of an assignment of a metered product: featureQuotas keyed by feature, no quota of product
    @Test
    @TestSecurity(user = "owner", roles = "realm-owner")
    void assignAMeteredProduct() {
        given().contentType(ContentType.JSON)
                .body("{\"name\": \"repo\", \"displayName\": \"Repository\", "
                        + "\"features\": [{\"name\": \"read\"}, {\"name\": \"scan\", \"metered\": true}]}")
                .when().post(PRODUCTS, CATALOG)
                .then().statusCode(201)
                .body("metered", equalTo(true));

        given().contentType(ContentType.JSON)
                .body("{\"boughtFeatures\": [\"read\", \"scan\"], "
                        + "\"featureQuotas\": {\"scan\": {\"quota\": 200, \"cycle\": \"DAILY\"}}}")
                .when().put(ASSIGNMENT, ORG, CATALOG, "repo")
                .then().statusCode(200)
                .body("quota", nullValue())
                .body("featureQuotas.scan.quota", equalTo(200))
                .body("featureQuotas.scan.cycle", equalTo("DAILY"));

        // Bean Validation on the quotas of the features; then the rules of the service
        given().contentType(ContentType.JSON)
                .body("{\"boughtFeatures\": [\"scan\"], \"featureQuotas\": {\"scan\": {\"quota\": 0, \"cycle\": \"DAILY\"}}}")
                .when().put(ASSIGNMENT, ORG, CATALOG, "repo")
                .then().statusCode(400);
        given().contentType(ContentType.JSON)
                .body("{\"boughtFeatures\": [\"scan\"]}")
                .when().put(ASSIGNMENT, ORG, CATALOG, "repo")
                .then().statusCode(400)
                .body("message", containsString("scan"));
    }

    @Test
    @TestSecurity(user = "owner", roles = "realm-owner")
    void assignErrors() {
        createBilling();

        // a feature the product does not have, a value outside of the enum, no quota
        given().contentType(ContentType.JSON).body(new AssignProductRequest(10L, QuotaCycle.DAILY, List.of("export"), Map.of()))
                .when().put(ASSIGNMENT, ORG, CATALOG, "billing")
                .then().statusCode(400);
        given().contentType(ContentType.JSON).body(new AssignProductRequest(10L, QuotaCycle.DAILY, List.of(), Map.of("region", "asia")))
                .when().put(ASSIGNMENT, ORG, CATALOG, "billing")
                .then().statusCode(400);
        given().contentType(ContentType.JSON).body(Map.of("cycle", "MONTHLY"))
                .when().put(ASSIGNMENT, ORG, CATALOG, "billing")
                .then().statusCode(400);
        given().contentType(ContentType.JSON).body(new AssignProductRequest(10L, QuotaCycle.DAILY))
                .when().put(ASSIGNMENT, "unknown", CATALOG, "billing")
                .then().statusCode(404);
        given().contentType(ContentType.JSON).body(new AssignProductRequest(10L, QuotaCycle.DAILY))
                .when().put(ASSIGNMENT, ORG, CATALOG, "unknown")
                .then().statusCode(404);
    }

    private void createBilling() {
        createBillingRequest().then().statusCode(201).body("name", equalTo("billing"));
    }

    private io.restassured.response.Response createBillingRequest() {
        return given().contentType(ContentType.JSON)
                .body(new CreateProductRequest("billing", "Billing", null,
                        List.of(new Feature("read", null), new Feature("write", null)),
                        List.of(new ParamDefinition("region", ParamType.ENUM, true, "eu", List.of("eu", "us")))))
                .when().post(PRODUCTS, CATALOG);
    }
}
