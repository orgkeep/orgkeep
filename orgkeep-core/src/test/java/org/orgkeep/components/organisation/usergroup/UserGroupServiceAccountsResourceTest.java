package org.orgkeep.components.organisation.usergroup;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mockito.Mockito;
import org.orgkeep.api.OrgMemberService;
import org.orgkeep.api.OrgService;
import org.orgkeep.api.ServiceAccountService;
import org.orgkeep.api.exceptions.OrgNotFoundException;
import org.orgkeep.api.UserGroupService;
import org.orgkeep.api.model.AddMemberRequest;
import org.orgkeep.api.model.CreateOrgRequest;
import org.orgkeep.api.model.CreateServiceAccountRequest;
import org.orgkeep.api.model.CreateUserGroupRequest;
import org.orgkeep.fixtures.TestRealm;
import org.orgkeep.web.CurrentRealm;

import java.util.List;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Service accounts in the teams of an org over REST: who may call, status codes, and what the lists show.
 * The roles in @TestSecurity use the form produced by OrgRolesAugmentor: "org-{org}:{role}".
 * <p>
 * One realm for the whole class (@BeforeAll); the orgs are rebuilt before every test (@BeforeEach) and torn down
 * after it (@AfterEach), since teams and service accounts are created under fixed names.
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UserGroupServiceAccountsResourceTest {
    private static final String BASE = "/api/organisations/{org}/user-groups";
    private static final String ONE = BASE + "/{group}/service-accounts/{sa}";
    private static final String ADMIN = "org-acme:org-admin";

    @Inject OrgService orgService;
    @Inject OrgMemberService memberService;
    @Inject UserGroupService userGroupService;
    @Inject ServiceAccountService serviceAccountService;
    @InjectMock CurrentRealm currentRealm;

    private TestRealm testRealm;
    private String paulId;
    private String batchUserId;

    @BeforeAll
    void createRealm() {
        testRealm = TestRealm.create();
    }

    @BeforeEach
    void setUpFixture() {
        Mockito.when(currentRealm.name()).thenReturn(testRealm.name());
        String realm = testRealm.name();
        orgService.create(realm, new CreateOrgRequest("acme", Set.of(), null, null));
        orgService.create(realm, new CreateOrgRequest("globex", Set.of(), null, null));
        paulId = memberService.add(realm, "acme", new AddMemberRequest("paul@acme.test", "Paul", "Test")).id();
        userGroupService.create(realm, "acme", new CreateUserGroupRequest("ops"));
        batchUserId = serviceAccountService.create(realm, "acme", new CreateServiceAccountRequest("batch", null, null))
                .serviceAccount().userId();
        serviceAccountService.create(realm, "globex", new CreateServiceAccountRequest("batch", null, null));
    }

    @AfterEach
    void cleanUp() {
        for (String org : List.of("acme", "globex")) {
            try {
                orgService.delete(testRealm.name(), org);
            } catch (OrgNotFoundException ignored) {
                // not created by this test
            }
        }
        testRealm.resource().users().list(0, 1000)
                .forEach(user -> testRealm.resource().users().get(user.getId()).remove());
    }

    @AfterAll
    void deleteRealm() {
        testRealm.close();
    }

    @Test
    void withoutATokenGets401() {
        given().when().put(ONE, "acme", "ops", "batch").then().statusCode(401);
    }

    @Test
    @TestSecurity(user = "carol", roles = "org-acme:org-user")
    void orgUserGets403() {
        given().when().put(ONE, "acme", "ops", "batch").then().statusCode(403);
        given().when().delete(ONE, "acme", "ops", "batch").then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "mallory", roles = "org-globex:org-admin")
    void adminOfAnotherOrgGets403() {
        given().when().put(ONE, "acme", "ops", "batch").then().statusCode(403);
        given().when().delete(ONE, "acme", "ops", "batch").then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "bob", roles = ADMIN)
    void serviceAccountJoinsAndLeavesATeam() {
        given().when().put(ONE, "acme", "ops", "batch").then().statusCode(204);
        // twice: idempotent
        given().when().put(ONE, "acme", "ops", "batch").then().statusCode(204);
        assertTrue(testRealm.groupPaths(batchUserId).contains("/acme/user-groups/ops"));

        given().when().get(BASE, "acme").then().statusCode(200)
                .body("find { it.name == 'ops' }.serviceAccounts", contains("batch"))
                .body("find { it.name == 'ops' }.memberIds", empty());
        given().when().get("/api/organisations/{org}/service-accounts/{sa}", "acme", "batch").then().statusCode(200)
                .body("teams", contains("ops"));

        given().when().delete(ONE, "acme", "ops", "batch").then().statusCode(204);
        given().when().delete(ONE, "acme", "ops", "batch").then().statusCode(204);
        assertFalse(testRealm.groupPaths(batchUserId).contains("/acme/user-groups/ops"));
        // still a service account of the org
        assertTrue(testRealm.groupPaths(batchUserId).contains("/acme/service-accounts"));
    }

    // the name of a service account of another org is unknown here, the same name in the own org is fine
    @Test
    @TestSecurity(user = "bob", roles = {ADMIN, "org-globex:org-admin"})
    void aServiceAccountOfAnotherOrgIsNotFound() {
        given().when().put(ONE, "acme", "ops", "other").then().statusCode(404);
        given().when().put(ONE, "globex", "ops", "batch").then().statusCode(404);
        userGroupService.create(testRealm.name(), "globex", new CreateUserGroupRequest("ops"));
        given().when().put(ONE, "globex", "ops", "batch").then().statusCode(204);
        // acme's batch is not in globex's team, and the other way round
        given().when().get(BASE, "acme").then().body("find { it.name == 'ops' }.serviceAccounts", empty());
    }

    @Test
    @TestSecurity(user = "bob", roles = ADMIN)
    void unknownTeamAndInvalidNameAreRefused() {
        given().when().put(ONE, "acme", "nope", "batch").then().statusCode(404);
        given().when().put(ONE, "acme", "ops", "a:b").then().statusCode(400);
    }

    // admin gives org-admin and users gives org-user: a service account never holds an org role
    @Test
    @TestSecurity(user = "bob", roles = ADMIN)
    void adminAndUsersGet409() {
        for (String team : List.of("admin", "users")) {
            given().when().put(ONE, "acme", team, "batch").then().statusCode(409);
            given().when().delete(ONE, "acme", team, "batch").then().statusCode(409);
        }
        assertFalse(testRealm.groupPaths(batchUserId).contains("/acme/user-groups/admin"));
        assertFalse(testRealm.groupPaths(batchUserId).contains("/acme/user-groups/users"));
    }

    // the endpoint of the members keeps refusing the user of a service account
    @Test
    @TestSecurity(user = "bob", roles = ADMIN)
    void memberEndpointStillAnswers404ForAServiceAccountUser() {
        given().when().put(BASE + "/{group}/members/{userId}", "acme", "ops", batchUserId).then().statusCode(404);
    }

    // people and service accounts together in a team: accepted, listed apart
    @Test
    @TestSecurity(user = "bob", roles = ADMIN)
    void aTeamMayMixPeopleAndServiceAccounts() {
        given().when().put(BASE + "/{group}/members/{userId}", "acme", "ops", paulId).then().statusCode(204);
        given().when().put(ONE, "acme", "ops", "batch").then().statusCode(204);

        given().when().get(BASE, "acme").then().statusCode(200)
                .body("find { it.name == 'ops' }.memberIds", contains(paulId))
                .body("find { it.name == 'ops' }.serviceAccounts", contains("batch"))
                .body("name", hasItem("ops"));
        given().when().get(BASE, "acme").then()
                .body("find { it.name == 'users' }.serviceAccounts", equalTo(List.of()));
    }
}
