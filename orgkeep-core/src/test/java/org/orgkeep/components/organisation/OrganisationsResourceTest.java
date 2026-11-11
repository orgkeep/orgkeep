package org.orgkeep.components.organisation;

import org.orgkeep.api.OrgMemberService;
import org.orgkeep.api.model.AddMemberRequest;
import org.orgkeep.api.model.CreateOrgRequest;
import org.orgkeep.api.model.UpdateOrgStatusRequest;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    @Inject OrgMemberService memberService;
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

    // the owner of a new org follows the rules of a member: not privileged (here the realm owner themselves)
    @Test
    @TestSecurity(user = "owner", roles = "realm-owner")
    void createWithAPrivilegedOwnerReturns409() {
        privilegedUser("boss");

        given().contentType(ContentType.JSON)
                .body(new CreateOrgRequest(ORG, Set.of(), "boss@elsewhere.test", null, "Boss", "Test"))
                .when().post(BASE)
                .then().statusCode(409)
                .body("message", equalTo("User 'boss@elsewhere.test' cannot be added to this organisation"));
        // checked before anything is created
        given().when().get(BASE + "/{org}", ORG)
                .then().statusCode(404);
    }

    // ... and no other org: same message, the other org is not named
    @Test
    @TestSecurity(user = "owner", roles = "realm-owner")
    void createWithAnOwnerOfAnotherOrgReturns409WithoutNamingIt() {
        given().contentType(ContentType.JSON)
                .body(new CreateOrgRequest("first-org", Set.of(), "dave@elsewhere.test", null, "Dave", "Test"))
                .when().post(BASE)
                .then().statusCode(201);

        given().contentType(ContentType.JSON)
                .body(new CreateOrgRequest(ORG, Set.of(), "dave@elsewhere.test", null, "Dave", "Test"))
                .when().post(BASE)
                .then().statusCode(409)
                .body("message", equalTo("User 'dave@elsewhere.test' cannot be added to this organisation"));
    }

    // disabling an org disables its members, never a privileged one (the realm owner would lose dashboard and console)
    @Test
    @TestSecurity(user = "owner", roles = "realm-owner")
    void setEnabledLeavesAPrivilegedMemberEnabled() {
        create();
        String paulId = memberService.add(testRealm.name(), ORG, new AddMemberRequest("paul@elsewhere.test", "Paul", "Test")).id();
        // a member who got the role afterwards: Orgkeep does not add a privileged user
        String bossId = memberService.add(testRealm.name(), ORG, new AddMemberRequest("boss@elsewhere.test", "Boss", "Test")).id();
        testRealm.createRealmRole("realm-owner");
        testRealm.grantRealmRole(bossId, "realm-owner");

        given().contentType(ContentType.JSON).body(new UpdateOrgStatusRequest(false))
                .when().put(BASE + "/{org}/enabled", ORG)
                .then().statusCode(200)
                .body("enabled", equalTo(false));

        assertTrue(testRealm.resource().users().get(bossId).toRepresentation().isEnabled());
        assertFalse(testRealm.resource().users().get(paulId).toRepresentation().isEnabled());
    }

    /** An existing user holding the realm role realm-owner, as the realm owner created by the bootstrap. */
    private String privilegedUser(String username) {
        String userId = testRealm.createUser(username, username + "@elsewhere.test");
        testRealm.createRealmRole("realm-owner");
        testRealm.grantRealmRole(userId, "realm-owner");
        return userId;
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
    void setEnabledReturnsTheOrg() {
        create();

        given().contentType(ContentType.JSON).body(new UpdateOrgStatusRequest(false))
                .when().put(BASE + "/{org}/enabled", ORG)
                .then().statusCode(200)
                .body("name", equalTo(ORG))
                .body("enabled", equalTo(false));
        given().when().get(BASE + "/{org}", ORG)
                .then().statusCode(200)
                .body("enabled", equalTo(false));
    }

    @Test
    @TestSecurity(user = "owner", roles = "realm-owner")
    void setEnabledOnUnknownReturns404() {
        given().contentType(ContentType.JSON).body(new UpdateOrgStatusRequest(false))
                .when().put(BASE + "/{org}/enabled", "unknown-org")
                .then().statusCode(404);
    }

    // an org admin cannot re-enable their own org: the realm owner decides
    @Test
    @TestSecurity(user = "alice", roles = "org-" + ORG + ":org-admin")
    void setEnabledByAnOrgAdminReturns403() {
        given().contentType(ContentType.JSON).body(new UpdateOrgStatusRequest(true))
                .when().put(BASE + "/{org}/enabled", ORG)
                .then().statusCode(403);
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
