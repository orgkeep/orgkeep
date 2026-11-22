package org.orgkeep.components.organisation.serviceaccount;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mockito.Mockito;
import org.orgkeep.api.CatalogService;
import org.orgkeep.api.OrgService;
import org.orgkeep.api.ProductService;
import org.orgkeep.api.ServiceAccountService;
import org.orgkeep.api.ServiceAccountSettingsService;
import org.orgkeep.api.model.UpdateServiceAccountSettingsRequest;
import org.orgkeep.api.model.AssignProductRequest;
import org.orgkeep.api.model.CreateCatalogRequest;
import org.orgkeep.api.model.CreateOrgRequest;
import org.orgkeep.api.model.CreateProductRequest;
import org.orgkeep.api.model.CreateServiceAccountRequest;
import org.orgkeep.api.model.QuotaCycle;
import org.orgkeep.api.model.UpdateServiceAccountRequest;
import org.orgkeep.api.model.UpdateServiceAccountStatusRequest;
import org.orgkeep.fixtures.TestRealm;
import org.orgkeep.web.CurrentRealm;

import java.util.List;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Service accounts of an org over REST: who may call, status codes, and that the secret only shows when generated.
 * The roles in @TestSecurity use the form produced by OrgRolesAugmentor: "org-{org}:{role}".
 * <p>
 * The orgs, catalogs and product are created once (@BeforeAll); the service accounts a test creates, the status
 * of "acme" and the realm default of lightweight tokens are reset after each test (@AfterEach).
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ServiceAccountsResourceTest {
    private static final String BASE = "/api/organisations/{org}/service-accounts";
    private static final String ONE = BASE + "/{sa}";
    private static final String ADMIN = "org-acme:org-admin";

    @Inject OrgService orgService;
    @Inject CatalogService catalogService;
    @Inject ProductService productService;
    @Inject ServiceAccountService serviceAccountService;
    @Inject ServiceAccountSettingsService settingsService;
    @InjectMock CurrentRealm currentRealm;

    private TestRealm testRealm;

    @BeforeAll
    void createRealm() {
        testRealm = TestRealm.create();
        orgService.create(testRealm.name(), new CreateOrgRequest("acme", Set.of(), null, null));
        orgService.create(testRealm.name(), new CreateOrgRequest("globex", Set.of(), null, null));
        catalogService.create(testRealm.name(), new CreateCatalogRequest("prod", "Production", null));
        catalogService.create(testRealm.name(), new CreateCatalogRequest("sandbox", "Sandbox", null));
        productService.create(testRealm.name(), "prod", new CreateProductRequest("billing", "Billing", null));
        productService.assign(testRealm.name(), "acme", "prod", "billing", new AssignProductRequest(10L, QuotaCycle.DAILY));
    }

    @BeforeEach
    void stubCurrentRealm() {
        Mockito.when(currentRealm.name()).thenReturn(testRealm.name());
    }

    @AfterEach
    void cleanUp() {
        settingsService.updateRealmDefaults(testRealm.name(), new UpdateServiceAccountSettingsRequest(false));
        for (String org : List.of("acme", "globex")) {
            orgService.setEnabled(testRealm.name(), org, true);
            for (var serviceAccount : serviceAccountService.list(testRealm.name(), org)) {
                serviceAccountService.delete(testRealm.name(), org, serviceAccount.name());
            }
        }
    }

    @AfterAll
    void deleteRealm() {
        testRealm.close();
    }

    @Test
    void withoutATokenGets401() {
        given().when().get(BASE, "acme").then().statusCode(401);
    }

    @Test
    @TestSecurity(user = "carol", roles = "org-acme:org-user")
    void orgUserGets403() {
        given().when().get(BASE, "acme").then().statusCode(403);
        given().contentType(ContentType.JSON).body(new CreateServiceAccountRequest("batch", null, null))
                .when().post(BASE, "acme").then().statusCode(403);
    }

    // the token of a service account carries no org role: the same 403 as a caller without any role
    @Test
    @TestSecurity(user = "service-account-org-acme.batch", roles = {})
    void serviceAccountTokenGets403() {
        given().when().get(BASE, "acme").then().statusCode(403);
        given().when().get("/api/organisations/{org}/members", "acme").then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "mallory", roles = "org-globex:org-admin")
    void adminOfAnotherOrgGets403() {
        given().when().get(BASE, "acme").then().statusCode(403);
        given().when().delete(ONE, "acme", "batch").then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "bob", roles = ADMIN)
    void adminCreatesGetsAndListsWithoutTheSecretAfterwards() {
        given().contentType(ContentType.JSON).body(new CreateServiceAccountRequest("batch", "Nightly batch", List.of("prod")))
                .when().post(BASE, "acme")
                .then().statusCode(201)
                .body("serviceAccount.name", equalTo("batch"))
                .body("serviceAccount.clientId", equalTo("org-acme.batch"))
                .body("serviceAccount.description", equalTo("Nightly batch"))
                .body("serviceAccount.enabled", equalTo(true))
                .body("serviceAccount.defaultCatalogs", contains("prod"))
                .body("serviceAccount.userId", notNullValue())
                .body("credentials.clientId", equalTo("org-acme.batch"))
                .body("credentials.clientSecret", notNullValue())
                .body("credentials.tokenUrl", containsString("/realms/" + testRealm.name() + "/protocol/openid-connect/token"));

        String get = given().when().get(ONE, "acme", "batch")
                .then().statusCode(200).body("name", equalTo("batch")).extract().asString();
        String list = given().when().get(BASE, "acme")
                .then().statusCode(200).body("name", contains("batch")).extract().asString();
        org.junit.jupiter.api.Assertions.assertFalse(get.toLowerCase().contains("secret"), get);
        org.junit.jupiter.api.Assertions.assertFalse(list.toLowerCase().contains("secret"), list);
    }

    // the mode of the tokens is for the realm owner only (its own endpoints): neither the org admin nor the realm owner
    // finds it in the representation of the service account
    @Test
    @TestSecurity(user = "alice", roles = {"realm-owner", ADMIN})
    void getHasNoLightweightField() {
        create("acme", "batch");
        settingsService.update(testRealm.name(), "acme", "batch", new UpdateServiceAccountSettingsRequest(true));

        String get = given().when().get(ONE, "acme", "batch").then().statusCode(200).extract().asString();
        String list = given().when().get(BASE, "acme").then().statusCode(200).extract().asString();
        org.junit.jupiter.api.Assertions.assertFalse(get.toLowerCase().contains("lightweight"), get);
        org.junit.jupiter.api.Assertions.assertFalse(list.toLowerCase().contains("lightweight"), list);
    }

    // created by an org admin, the service account takes the default of the realm (the admin never sees the mode)
    @Test
    @TestSecurity(user = "bob", roles = ADMIN)
    void serviceAccountCreatedByAnOrgAdminTakesTheRealmDefault() {
        settingsService.updateRealmDefaults(testRealm.name(), new UpdateServiceAccountSettingsRequest(true));

        given().contentType(ContentType.JSON).body(new CreateServiceAccountRequest("batch", null, null))
                .when().post(BASE, "acme").then().statusCode(201);

        org.junit.jupiter.api.Assertions.assertTrue(settingsService.get(testRealm.name(), "acme", "batch").lightweightTokens());
    }

    @Test
    @TestSecurity(user = "alice", roles = "realm-owner")
    void realmOwnerIsAllowed() {
        given().contentType(ContentType.JSON).body(new CreateServiceAccountRequest("batch", null, null))
                .when().post(BASE, "acme").then().statusCode(201);
        given().when().get(BASE, "acme").then().statusCode(200).body("name", contains("batch"));
    }

    // the same name in two orgs: each admin only sees its own, and an unknown name in one's own org is a 404
    @Test
    @TestSecurity(user = "bob", roles = {ADMIN, "org-globex:org-admin"})
    void sameNameInTwoOrgsStaysApart() {
        create("acme", "batch");
        given().when().get(ONE, "globex", "batch").then().statusCode(404);
        create("globex", "batch");
        given().when().get(ONE, "globex", "batch").then().statusCode(200).body("clientId", equalTo("org-globex.batch"));
        given().when().get(BASE, "acme").then().body("clientId", contains("org-acme.batch"));
        given().when().get(BASE, "globex").then().body("clientId", contains("org-globex.batch"));
        given().when().get(ONE, "acme", "other").then().statusCode(404);
    }

    @Test
    @TestSecurity(user = "bob", roles = ADMIN)
    void invalidRequestsGet400() {
        given().contentType(ContentType.JSON).body(new CreateServiceAccountRequest("Not A Slug", null, null))
                .when().post(BASE, "acme").then().statusCode(400);
        given().contentType(ContentType.JSON).body(new CreateServiceAccountRequest("batch", null, List.of("nope")))
                .when().post(BASE, "acme").then().statusCode(400);
        // exists, but the org has no product in it
        given().contentType(ContentType.JSON).body(new CreateServiceAccountRequest("batch", null, List.of("sandbox")))
                .when().post(BASE, "acme").then().statusCode(400);
        // a name read from the URL is checked before any use
        given().when().get(ONE, "acme", "a:b").then().statusCode(400);
        given().when().get(ONE, "acme", "UPPER").then().statusCode(400);
        given().contentType(ContentType.JSON).body("{}")
                .when().put(ONE + "/enabled", "acme", "batch").then().statusCode(400);
    }

    @Test
    @TestSecurity(user = "bob", roles = ADMIN)
    void duplicateNameGets409() {
        create("acme", "batch");
        given().contentType(ContentType.JSON).body(new CreateServiceAccountRequest("batch", null, null))
                .when().post(BASE, "acme").then().statusCode(409);
    }

    @Test
    @TestSecurity(user = "bob", roles = ADMIN)
    void creationInADisabledOrgGets409() {
        orgService.setEnabled(testRealm.name(), "acme", false);

        given().contentType(ContentType.JSON).body(new CreateServiceAccountRequest("batch", null, null))
                .when().post(BASE, "acme").then().statusCode(409);
    }

    @Test
    @TestSecurity(user = "bob", roles = ADMIN)
    void updateRotateDisableAndDelete() {
        String firstSecret = create("acme", "batch");

        given().contentType(ContentType.JSON).body(new UpdateServiceAccountRequest("New description", List.of("prod")))
                .when().put(ONE, "acme", "batch")
                .then().statusCode(200)
                .body("description", equalTo("New description"))
                .body("defaultCatalogs", contains("prod"));

        String newSecret = given().when().post(ONE + "/secret", "acme", "batch")
                .then().statusCode(200)
                .body("clientId", equalTo("org-acme.batch"))
                .body("tokenUrl", containsString("/protocol/openid-connect/token"))
                .extract().path("clientSecret");
        org.junit.jupiter.api.Assertions.assertNotEquals(firstSecret, newSecret);

        given().contentType(ContentType.JSON).body(new UpdateServiceAccountStatusRequest(false))
                .when().put(ONE + "/enabled", "acme", "batch")
                .then().statusCode(200).body("enabled", equalTo(false));
        given().when().get(BASE, "acme").then().body("[0].enabled", equalTo(false));

        given().when().delete(ONE, "acme", "batch").then().statusCode(204);
        given().when().get(ONE, "acme", "batch").then().statusCode(404);
        given().when().delete(ONE, "acme", "batch").then().statusCode(404);
        given().when().post(ONE + "/secret", "acme", "batch").then().statusCode(404);
    }

    // refused in both directions while the org is disabled
    @Test
    @TestSecurity(user = "bob", roles = ADMIN)
    void statusChangeInADisabledOrgGets409BothWays() {
        create("acme", "batch");
        orgService.setEnabled(testRealm.name(), "acme", false);

        for (boolean enabled : new boolean[]{true, false}) {
            given().contentType(ContentType.JSON).body(new UpdateServiceAccountStatusRequest(enabled))
                    .when().put(ONE + "/enabled", "acme", "batch").then().statusCode(409);
        }
        // a rotation is still allowed
        given().when().post(ONE + "/secret", "acme", "batch").then().statusCode(200);
    }

    @Test
    @TestSecurity(user = "alice", roles = "realm-owner")
    void unknownOrgGets404() {
        given().when().get(BASE, "nope").then().statusCode(404);
        given().contentType(ContentType.JSON).body(new CreateServiceAccountRequest("batch", null, null))
                .when().post(BASE, "nope").then().statusCode(404);
    }

    /** @return the secret of the new service account */
    private String create(String org, String name) {
        return given().contentType(ContentType.JSON).body(new CreateServiceAccountRequest(name, null, null))
                .when().post(BASE, org)
                .then().statusCode(201).extract().path("credentials.clientSecret");
    }
}
