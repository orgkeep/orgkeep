package org.orgkeep.components.organisation.usergroup;

import org.orgkeep.api.OrgMemberService;
import org.orgkeep.api.OrgService;
import org.orgkeep.api.model.AddMemberRequest;
import org.orgkeep.api.model.CreateOrgRequest;
import org.orgkeep.api.model.CreateUserGroupRequest;
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

import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * User groups managed by the org admins: security, status codes, and the effects in Keycloak (group tree, membership).
 * The roles in @TestSecurity use the form produced by OrgRolesAugmentor: "org-{org}:{role}".
 */
@QuarkusTest
class UserGroupsResourceTest {
    private static final String ORG = "it-groups-org";
    private static final String BASE = "/api/organisations/{org}/user-groups";
    private static final String MEMBER = BASE + "/{group}/members/{userId}";
    // The caller is the org owner. Keycloak puts the effective roles in the token (org-owner includes org-admin),
    // @TestSecurity does not expand composites: both roles are listed, as the token of an owner has them
    private static final String OWNER = "org-it-groups-org:org-owner";
    private static final String ADMIN = "org-it-groups-org:org-admin";

    @Inject Keycloak keycloak;
    @Inject OrgService orgService;
    @Inject OrgMemberService memberService;
    @InjectMock CurrentRealm currentRealm;

    private TestRealm testRealm;
    private String memberId;

    @BeforeEach
    void beforeEach() {
        testRealm = TestRealm.create(keycloak);
        Mockito.when(currentRealm.name()).thenReturn(testRealm.name());
        orgService.create(testRealm.name(), new CreateOrgRequest(ORG, Set.of(), null, null));
        memberId = memberService.add(testRealm.name(), ORG, new AddMemberRequest("bob@it-groups-org.test", "Bob", "Smith")).id();
    }

    @AfterEach
    void afterEach() {
        testRealm.close();
    }

    @Test
    @TestSecurity(user = "mallory", roles = "org-other-org:org-admin")
    void adminOfAnotherOrgGets403() {
        given().when().get(BASE, ORG)
                .then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "carol", roles = "org-it-groups-org:org-user")
    void simpleOrgUserGets403() {
        given().contentType(ContentType.JSON).body(new CreateUserGroupRequest("support"))
                .when().post(BASE, ORG)
                .then().statusCode(403);
    }

    // the two groups of a new org, sorted by name, with the member added in beforeEach in "users"
    @Test
    @TestSecurity(user = "bob", roles = {OWNER, ADMIN})
    void listGivesThePredefinedGroups() {
        given().when().get(BASE, ORG)
                .then().statusCode(200)
                .body("name", contains("admin", "users"))
                .body("predefined", contains(true, true))
                .body("find { it.name == 'users' }.memberIds", hasItem(memberId));
    }

    @Test
    @TestSecurity(user = "bob", roles = {OWNER, ADMIN})
    void createAddsAGroupUnderUserGroups() {
        given().contentType(ContentType.JSON).body(new CreateUserGroupRequest("support"))
                .when().post(BASE, ORG)
                .then().statusCode(201)
                .body("name", equalTo("support"))
                .body("predefined", equalTo(false));

        // throws a 404 WebApplicationException if the group is missing
        testRealm.resource().getGroupByPath("/" + ORG + "/user-groups/support");
    }

    @Test
    @TestSecurity(user = "bob", roles = {OWNER, ADMIN})
    void createExistingGroupReturns409() {
        given().contentType(ContentType.JSON).body(new CreateUserGroupRequest("admin"))
                .when().post(BASE, ORG)
                .then().statusCode(409);
    }

    @Test
    @TestSecurity(user = "bob", roles = {OWNER, ADMIN})
    void createWithInvalidNameReturns400() {
        given().contentType(ContentType.JSON).body(new CreateUserGroupRequest("Support Team"))
                .when().post(BASE, ORG)
                .then().statusCode(400);
    }

    @Test
    @TestSecurity(user = "bob", roles = {OWNER, ADMIN})
    void membersMoveInAndOutOfAGroup() {
        createGroup("support");

        given().when().put(MEMBER, ORG, "support", memberId)
                .then().statusCode(204);
        assertTrue(testRealm.groupPaths(memberId).contains("/" + ORG + "/user-groups/support"));

        given().when().delete(MEMBER, ORG, "support", memberId)
                .then().statusCode(204);
        assertFalse(testRealm.groupPaths(memberId).contains("/" + ORG + "/user-groups/support"));
        // still in users: a member never leaves it through a group change
        assertTrue(testRealm.groupPaths(memberId).contains("/" + ORG + "/user-groups/users"));
    }

    // the group admin is a user group like the others: putting a member in it makes them org admin
    @Test
    @TestSecurity(user = "bob", roles = {OWNER, ADMIN})
    void puttingAMemberInAdminMakesThemOrgAdmin() {
        given().when().put(MEMBER, ORG, "admin", memberId)
                .then().statusCode(204);

        given().when().get("/api/organisations/{org}/members", ORG)
                .then().statusCode(200)
                .body("find { it.id == '%s' }.role".formatted(memberId), equalTo("ORG_ADMIN"));
    }

    @Test
    @TestSecurity(user = "bob", roles = {OWNER, ADMIN})
    void addingSomeoneOutsideTheOrgReturns404() {
        createGroup("support");
        String strangerId = testRealm.createUser("stranger", "stranger@elsewhere.test");

        given().when().put(MEMBER, ORG, "support", strangerId)
                .then().statusCode(404);
        assertFalse(testRealm.groupPaths(strangerId).contains("/" + ORG + "/user-groups/support"));
    }

    @Test
    @TestSecurity(user = "bob", roles = {OWNER, ADMIN})
    void unknownGroupReturns404() {
        given().when().put(MEMBER, ORG, "unknown", memberId)
                .then().statusCode(404);
    }

    @Test
    @TestSecurity(user = "bob", roles = {OWNER, ADMIN})
    void leavingUsersReturns409() {
        given().when().delete(MEMBER, ORG, "users", memberId)
                .then().statusCode(409);
        assertTrue(testRealm.groupPaths(memberId).contains("/" + ORG + "/user-groups/users"));
    }

    @Test
    @TestSecurity(user = "bob", roles = {OWNER, ADMIN})
    void deleteRemovesTheGroupAndItsMemberships() {
        createGroup("support");
        given().when().put(MEMBER, ORG, "support", memberId)
                .then().statusCode(204);

        given().when().delete(BASE + "/{group}", ORG, "support")
                .then().statusCode(204);

        assertFalse(testRealm.groupPaths(memberId).contains("/" + ORG + "/user-groups/support"));
        given().when().get(BASE, ORG)
                .then().statusCode(200)
                .body("name", contains("admin", "users"));
    }

    @Test
    @TestSecurity(user = "bob", roles = {OWNER, ADMIN})
    void deletePredefinedGroupReturns409() {
        given().when().delete(BASE + "/{group}", ORG, "admin")
                .then().statusCode(409);
    }

    private void createGroup(String name) {
        given().contentType(ContentType.JSON).body(new CreateUserGroupRequest(name))
                .when().post(BASE, ORG)
                .then().statusCode(201);
    }
}
