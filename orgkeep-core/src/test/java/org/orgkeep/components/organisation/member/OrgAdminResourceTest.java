package org.orgkeep.components.organisation.member;

import org.orgkeep.api.OrgMemberService;
import org.orgkeep.api.OrgService;
import org.orgkeep.api.model.AddMemberRequest;
import org.orgkeep.api.model.CreateOrgRequest;
import org.orgkeep.fixtures.TestRealm;
import org.orgkeep.web.CurrentRealm;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.CreatedResponseUtil;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.MemberRepresentation;
import org.mockito.Mockito;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Member management by the org admins: security (only the admins of THIS org), status codes,
 * and the effects in Keycloak (membership, groups, roles).
 * The roles in @TestSecurity use the form produced by OrgRolesAugmentor: "org-{org}:{role}".
 */
@QuarkusTest
class OrgAdminResourceTest {
    private static final String ORG = "it-members-org";
    private static final String OWNER_EMAIL = "owner@it-members-org.test";
    private static final String BASE = "/api/organisations/{org}/members";

    @Inject Keycloak keycloak;
    @Inject OrgService orgService;
    @Inject OrgMemberService memberService;
    @InjectMock CurrentRealm currentRealm;

    private TestRealm testRealm;

    @BeforeEach
    void beforeEach() {
        testRealm = TestRealm.create(keycloak);
        Mockito.when(currentRealm.name()).thenReturn(testRealm.name());
        orgService.create(testRealm.name(),
                new CreateOrgRequest(ORG, Set.of("it-members-org.test"), OWNER_EMAIL, "changeme", "Olivia", "Owner"));
    }

    @AfterEach
    void afterEach() {
        testRealm.close();
    }

    @Test
    void anonymousGets401() {
        given().when().get(BASE, ORG)
                .then().statusCode(401);
    }

    @Test
    @TestSecurity(user = "mallory", roles = "org-other-org:org-admin")
    void adminOfAnotherOrgGets403() {
        given().when().get(BASE, ORG)
                .then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "carol", roles = "org-it-members-org:org-user")
    void simpleOrgUserGets403() {
        given().when().get(BASE, ORG)
                .then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "owner", roles = "realm-owner")
    void realmOwnerIsAllowed() {
        given().when().get(BASE, ORG)
                .then().statusCode(200);
    }

    @Test
    @TestSecurity(user = "alice", roles = "org-it-members-org:org-admin")
    void listReturnsMembers() {
        given().when().get(BASE, ORG)
                .then().statusCode(200)
                .body("email", hasItem(OWNER_EMAIL));
    }

    // the emails themselves are tested in InvitationMailerTest: here the security, the status codes and the JSON
    @Test
    @TestSecurity(user = "alice", roles = "org-it-members-org:org-admin")
    void listTellsWhoHasNotFollowedTheInvitation() {
        given().contentType(ContentType.JSON).body(new AddMemberRequest("ines@it-members-org.test", "Ines", "Martin"))
                .when().post(BASE, ORG)
                .then().statusCode(201)
                .body("invitationPending", equalTo(true));

        given().when().get(BASE, ORG)
                .then().statusCode(200)
                .body("find { it.email == 'ines@it-members-org.test' }.invitationPending", equalTo(true))
                // created with a password
                .body("find { it.email == '%s' }.invitationPending".formatted(OWNER_EMAIL), equalTo(false));
    }

    @Test
    @TestSecurity(user = "mallory", roles = "org-other-org:org-admin")
    void resendInvitationByAdminOfAnotherOrgGets403() {
        given().when().post(BASE + "/{userId}/invitation", ORG, ownerId())
                .then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "alice", roles = "org-it-members-org:org-admin")
    void resendInvitationToAMemberWithAPasswordReturns409() {
        given().when().post(BASE + "/{userId}/invitation", ORG, ownerId())
                .then().statusCode(409)
                .body("message", containsString("password"));
    }

    @Test
    @TestSecurity(user = "alice", roles = "org-it-members-org:org-admin")
    void resendInvitationToANonMemberReturns404() {
        String strangerId = testRealm.createUser("stranger", "stranger@elsewhere.test");

        given().when().post(BASE + "/{userId}/invitation", ORG, strangerId)
                .then().statusCode(404);
    }

    private String ownerId() {
        return testRealm.resource().users().searchByEmail(OWNER_EMAIL, true).getFirst().getId();
    }

    // owner: role granted directly at creation; admin: member of the admin group; user: anyone else
    @Test
    @TestSecurity(user = "alice", roles = "org-it-members-org:org-admin")
    void listGivesTheRoleOfEachMember() {
        String adminId = given().contentType(ContentType.JSON).body(new AddMemberRequest("emma@it-members-org.test", "Test", "User"))
                .when().post(BASE, ORG)
                .then().statusCode(201)
                .body("role", equalTo("ORG_USER"))
                .extract().path("id");
        testRealm.joinGroup(adminId, "/" + ORG + "/user-groups/admin");
        given().contentType(ContentType.JSON).body(new AddMemberRequest("hugo@it-members-org.test", "Test", "User"))
                .when().post(BASE, ORG)
                .then().statusCode(201);

        // JSON path on a list: find the member by email, then read its role
        given().when().get(BASE, ORG)
                .then().statusCode(200)
                .body("find { it.email == '%s' }.role".formatted(OWNER_EMAIL), equalTo("ORG_OWNER"))
                .body("find { it.email == 'emma@it-members-org.test' }.role", equalTo("ORG_ADMIN"))
                .body("find { it.email == 'hugo@it-members-org.test' }.role", equalTo("ORG_USER"));
    }

    @Test
    @TestSecurity(user = "alice", roles = "org-it-members-org:org-admin")
    void addCreatesUserAndJoinsUsersGroup() {
        String userId = given().contentType(ContentType.JSON).body(new AddMemberRequest("bob@it-members-org.test", "Bob", "Smith"))
                .when().post(BASE, ORG)
                .then().statusCode(201)
                .body("email", equalTo("bob@it-members-org.test"))
                .body("firstName", equalTo("Bob"))
                .extract().path("id");

        assertTrue(memberIds().contains(userId));
        assertTrue(testRealm.groupPaths(userId).contains("/" + ORG + "/user-groups/users"));
    }

    @Test
    @TestSecurity(user = "alice", roles = "org-it-members-org:org-admin")
    void addExistingMemberReturns409() {
        given().contentType(ContentType.JSON).body(new AddMemberRequest(OWNER_EMAIL, "Test", "User"))
                .when().post(BASE, ORG)
                .then().statusCode(409);
    }

    // Two existing users that cannot be added, for two different reasons: a member of another org (a user belongs to
    // one organisation only, the quotas in the token are not qualified by organisation) and a privileged user (the
    // realm owner, whom disabling the org would disable). The 409 must not tell which: an org admin would otherwise
    // learn who is a client of the realm owner, or which emails hold a realm role. Only the email differs.
    @Test
    @TestSecurity(user = "mallory", roles = "org-other-org:org-admin")
    void refusedUsersGetTheSameMessageAsAnOrgAdmin() {
        assertRefusalsLookTheSame();
    }

    // the realm owner gets the same messages: the service does not know who calls it
    @Test
    @TestSecurity(user = "owner", roles = "realm-owner")
    void refusedUsersGetTheSameMessageAsTheRealmOwner() {
        assertRefusalsLookTheSame();
    }

    private void assertRefusalsLookTheSame() {
        orgService.create(testRealm.name(), new CreateOrgRequest("other-org", Set.of(), null, null));
        // a member of ORG, the org of the test (not other-org)
        String daveId = memberService.add(testRealm.name(), ORG, new AddMemberRequest("dave@elsewhere.test", "Dave", "Test")).id();
        String bossId = privilegedUser("boss");

        String otherOrgMessage = given().contentType(ContentType.JSON)
                .body(new AddMemberRequest("dave@elsewhere.test", "Dave", "Test"))
                .when().post(BASE, "other-org")
                .then().statusCode(409)
                .extract().path("message");
        String privilegedMessage = given().contentType(ContentType.JSON)
                .body(new AddMemberRequest("boss@elsewhere.test", "Boss", "Test"))
                .when().post(BASE, "other-org")
                .then().statusCode(409)
                .extract().path("message");

        assertEquals("User 'boss@elsewhere.test' cannot be added to this organisation", privilegedMessage);
        assertEquals(otherOrgMessage.replace("dave@", "boss@"), privilegedMessage);
        assertFalse(memberIds("other-org").contains(daveId));
        assertFalse(memberIds("other-org").contains(bossId));
    }

    /** An existing user holding the realm role realm-owner, as the realm owner created by the bootstrap. */
    private String privilegedUser(String username) {
        String userId = testRealm.createUser(username, username + "@elsewhere.test");
        testRealm.createRealmRole("realm-owner");
        testRealm.grantRealmRole(userId, "realm-owner");
        return userId;
    }

    // effective roles count, not only the ones granted directly: here realm-owner given by a group of the console
    @Test
    @TestSecurity(user = "alice", roles = "org-it-members-org:org-admin")
    void addUserWithARealmRoleFromAGroupReturns409() {
        String samId = testRealm.createUser("sam", "sam@elsewhere.test");
        testRealm.createRealmRole("realm-owner");
        GroupRepresentation staff = new GroupRepresentation();
        staff.setName("staff");
        try (Response response = realm().groups().add(staff)) {
            String staffId = CreatedResponseUtil.getCreatedId(response);
            realm().groups().group(staffId).roles().realmLevel()
                    .add(List.of(realm().roles().get("realm-owner").toRepresentation()));
        }
        testRealm.joinGroup(samId, "/staff");

        given().contentType(ContentType.JSON).body(new AddMemberRequest("sam@elsewhere.test", "Sam", "Test"))
                .when().post(BASE, ORG)
                .then().statusCode(409);
        assertFalse(memberIds().contains(samId));
    }

    // an administrator of the realm in the Keycloak console, without any realm role: privileged too
    @Test
    @TestSecurity(user = "alice", roles = "org-it-members-org:org-admin")
    void addUserWithARealmManagementRoleReturns409() {
        String adaId = testRealm.createUser("ada", "ada@elsewhere.test");
        testRealm.grantClientRole(adaId, "realm-management", "manage-users");

        given().contentType(ContentType.JSON).body(new AddMemberRequest("ada@elsewhere.test", "Ada", "Test"))
                .when().post(BASE, ORG)
                .then().statusCode(409)
                .body("message", equalTo("User 'ada@elsewhere.test' cannot be added to this organisation"));
        assertFalse(memberIds().contains(adaId));
    }

    // only the realm roles of Orgkeep count: a realm role created in the console does not make a user privileged
    @Test
    @TestSecurity(user = "alice", roles = "org-it-members-org:org-admin")
    void addUserWithAnotherRealmRoleReusesIt() {
        String frankId = testRealm.createUser("frank", "frank@elsewhere.test");
        testRealm.createRealmRole("support");
        testRealm.grantRealmRole(frankId, "support");

        given().contentType(ContentType.JSON).body(new AddMemberRequest("frank@elsewhere.test", "Frank", "Test"))
                .when().post(BASE, ORG)
                .then().statusCode(201)
                .body("id", equalTo(frankId));
        assertTrue(memberIds().contains(frankId));
    }

    @Test
    @TestSecurity(user = "alice", roles = "org-it-members-org:org-admin")
    void addWithInvalidEmailReturns400() {
        given().contentType(ContentType.JSON).body(new AddMemberRequest("not-an-email", "Test", "User"))
                .when().post(BASE, ORG)
                .then().statusCode(400);
    }

    // Keycloak asks for a first and last name at login otherwise, and a password grant fails
    @Test
    @TestSecurity(user = "alice", roles = "org-it-members-org:org-admin")
    void addWithoutNamesReturns400() {
        given().contentType(ContentType.JSON).body(new AddMemberRequest("bob@it-members-org.test", " ", null))
                .when().post(BASE, ORG)
                .then().statusCode(400);
    }

    @Test
    @TestSecurity(user = "alice", roles = "org-it-members-org:org-admin")
    void removeCleansMembershipAndGroups() {
        String userId = given().contentType(ContentType.JSON).body(new AddMemberRequest("bob@it-members-org.test", "Test", "User"))
                .when().post(BASE, ORG)
                .then().statusCode(201)
                .extract().path("id");
        testRealm.joinGroup(userId, "/" + ORG + "/user-groups/admin");

        given().when().delete(BASE + "/{userId}", ORG, userId)
                .then().statusCode(204);

        assertFalse(memberIds().contains(userId));
        // no group of the org left, hence no org role and no product either
        assertEquals(Set.of(), testRealm.groupPaths(userId));
    }

    @Test
    @TestSecurity(user = "alice", roles = "org-it-members-org:org-admin")
    void removeOwnerReturns409() {
        String ownerId = realm().users().searchByEmail(OWNER_EMAIL, true).get(0).getId();

        given().when().delete(BASE + "/{userId}", ORG, ownerId)
                .then().statusCode(409);
        assertTrue(memberIds().contains(ownerId));
    }

    @Test
    @TestSecurity(user = "alice", roles = "org-it-members-org:org-admin")
    void removeNonMemberReturns404() {
        String strangerId = testRealm.createUser("stranger", "stranger@elsewhere.test");

        given().when().delete(BASE + "/{userId}", ORG, strangerId)
                .then().statusCode(404);
    }

    private RealmResource realm() {
        return testRealm.resource();
    }

    private Set<String> memberIds() {
        return memberIds(ORG);
    }

    private Set<String> memberIds(String orgName) {
        String orgId = realm().organizations().search(orgName).get(0).getId();
        return realm().organizations().get(orgId).members().getAll().stream()
                .map(MemberRepresentation::getId)
                .collect(Collectors.toSet());
    }
}
