package io.orgkeep.components.organisation.member;

import io.orgkeep.api.OrgService;
import io.orgkeep.api.model.AddMemberRequest;
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
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.MemberRepresentation;
import org.mockito.Mockito;

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

    // a user belongs to one organisation only: the quotas in the token are not qualified by organisation
    @Test
    @TestSecurity(user = "owner", roles = "realm-owner")
    void addMemberOfAnotherOrgReturns409() {
        orgService.create(testRealm.name(), new CreateOrgRequest("other-org", Set.of(), null, null));
        given().contentType(ContentType.JSON).body(new AddMemberRequest(OWNER_EMAIL, "Test", "User"))
                .when().post(BASE, "other-org")
                .then().statusCode(409)
                .body("message", containsString(ORG));
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
        String orgId = realm().organizations().search(ORG).get(0).getId();
        return realm().organizations().get(orgId).members().getAll().stream()
                .map(MemberRepresentation::getId)
                .collect(Collectors.toSet());
    }
}
