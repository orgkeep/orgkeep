package io.orgkeep.components.organisation;

import io.orgkeep.api.model.CreateOrgRequest;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.keycloak.admin.client.Keycloak;
import org.mockito.Mockito;

import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;

/**
 * HTTP-level tests: the business logic is covered by OrgServiceTest,
 * here we check routing, security, status codes and exception mapping.
 * <p>
 * - @TestSecurity runs a test as a given user with given roles, without any real token.
 * - @InjectMock replaces the CurrentRealm bean by a Mockito mock for this test class,
 *   so that every test works in its own throwaway realm instead of the bootstrapped one.
 */
@QuarkusTest
class OrganisationsResourceTest {
    private static final String ORG = "it-rest-org";
    // quarkus.rest.path=/api: RestAssured only knows the root path of the HTTP server, the prefix is in the URL
    private static final String BASE = "/api/admin/organisations";

    @Inject Keycloak keycloak;
    @InjectMock CurrentRealm currentRealm;

    private TestRealm testRealm;

    @BeforeEach
    void beforeEach() {
        testRealm = TestRealm.create(keycloak);
        Mockito.when(currentRealm.name()).thenReturn(testRealm.name());
    }

    @AfterEach
    void afterEach() {
        testRealm.close();
    }

    @Test
    void anonymousGets401() {
        given().when().get(BASE)
                .then().statusCode(401);
    }

    @Test
    @TestSecurity(user = "alice", roles = "org-it-rest-org:org-admin")
    void orgAdminGets403() {
        given().when().get(BASE)
                .then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "owner", roles = "realm-owner")
    void createReturns201WithIds() {
        given().contentType(ContentType.JSON).body(request())
                .when().post(BASE)
                .then().statusCode(201)
                .body("id", notNullValue())
                .body("clientId", equalTo("org-" + ORG));
    }

    @Test
    @TestSecurity(user = "owner", roles = "realm-owner")
    void createExistingReturns409() {
        create();

        given().contentType(ContentType.JSON).body(request())
                .when().post(BASE)
                .then().statusCode(409)
                .body("message", notNullValue());
    }

    // name is used in the client id and the group path: anything else than a lowercase slug is refused
    @ParameterizedTest
    @ValueSource(strings = {"", "Acme", "acme/sub", "-acme", "acme corp"})
    @TestSecurity(user = "owner", roles = "realm-owner")
    void createWithInvalidNameReturns400(String name) {
        given().contentType(ContentType.JSON).body(new CreateOrgRequest(name, Set.of(), null, null))
                .when().post(BASE)
                .then().statusCode(400);
    }

    @Test
    @TestSecurity(user = "owner", roles = "realm-owner")
    void createWithInvalidOwnerEmailReturns400() {
        given().contentType(ContentType.JSON).body(new CreateOrgRequest(ORG, Set.of(), "not-an-email", null, "Olivia", "Owner"))
                .when().post(BASE)
                .then().statusCode(400);
    }

    // an owner is created with a first and last name, or Keycloak asks for them at login
    @Test
    @TestSecurity(user = "owner", roles = "realm-owner")
    void createWithOwnerWithoutNamesReturns400() {
        given().contentType(ContentType.JSON).body(new CreateOrgRequest(ORG, Set.of(), "owner@it-rest-org.test", null, "Olivia", ""))
                .when().post(BASE)
                .then().statusCode(400)
                .body("violations[0].field", containsString("ownerNamed"));
    }

    // no owner, no user created: no names needed (request() has neither)
    @Test
    @TestSecurity(user = "owner", roles = "realm-owner")
    void createWithoutOwnerNeedsNoNames() {
        create();
    }

    @Test
    @TestSecurity(user = "owner", roles = "realm-owner")
    void listWithTooLargePageReturns400() {
        given().when().get(BASE + "?max=1000")
                .then().statusCode(400);
    }

    @Test
    @TestSecurity(user = "owner", roles = "realm-owner")
    void listReturnsCreatedOrg() {
        create();

        given().when().get(BASE)
                .then().statusCode(200)
                .body("name", hasItem(ORG));
    }

    @Test
    @TestSecurity(user = "owner", roles = "realm-owner")
    void getReturnsOrgDetails() {
        String id = create();

        given().when().get(BASE + "/{org}", ORG)
                .then().statusCode(200)
                .body("id", equalTo(id))
                .body("name", equalTo(ORG));
    }

    @Test
    @TestSecurity(user = "owner", roles = "realm-owner")
    void getUnknownReturns404() {
        given().when().get(BASE + "/{org}", "unknown-org")
                .then().statusCode(404)
                .body("message", notNullValue());
    }

    @Test
    @TestSecurity(user = "owner", roles = "realm-owner")
    void deleteReturns204ThenGetReturns404() {
        create();

        given().when().delete(BASE + "/{org}", ORG)
                .then().statusCode(204);
        given().when().get(BASE + "/{org}", ORG)
                .then().statusCode(404);
    }

    private String create() {
        return given().contentType(ContentType.JSON).body(request())
                .when().post(BASE)
                .then().statusCode(201)
                .extract().path("id");
    }

    private static CreateOrgRequest request() {
        return new CreateOrgRequest(ORG, Set.of("it-rest-org.test"), null, null);
    }
}
