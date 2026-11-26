package org.orgkeep.components.organisation.member;

import org.orgkeep.api.OrgMemberService;
import org.orgkeep.api.OrgService;
import org.orgkeep.api.SeatService;
import org.orgkeep.api.exceptions.OrgNotFoundException;
import org.orgkeep.api.model.AddMemberRequest;
import org.orgkeep.api.model.CreateOrgRequest;
import org.orgkeep.api.model.CreateServiceAccountRequest;
import org.orgkeep.api.model.UpdateSeatsRequest;
import org.orgkeep.fixtures.TestRealm;
import org.orgkeep.web.CurrentRealm;
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

import java.util.List;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Seat limits enforced by OrgMemberServiceImpl.add: the checks added by #39 on top of OrgAdminResourceTest's
 * existing coverage of member add. Members are counted whatever their state (enabled, disabled, pending
 * invitation), owners included; only removing one frees a seat (see SeatGuard, docs/design/members.md).
 * <p>
 * "No invitation email sent" is checked indirectly, like the rest of this suite: a refused add never creates a
 * Keycloak user (InvitationMailer only reacts to UserCreated, published by createUser), so the realm's user count
 * is unchanged.
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MemberSeatsTest {
    private static final String ORG = "it-member-seats-org";
    private static final String OTHER_ORG = "it-member-seats-other-org";
    private static final String OWNER_EMAIL = "owner@it-member-seats-org.test";
    private static final String BASE = "/api/organisations/{org}/members";
    private static final String SEATS_BASE = "/api/organisations/{org}/seats";

    @Inject OrgService orgService;
    @Inject OrgMemberService memberService;
    @Inject SeatService seatService;
    @InjectMock CurrentRealm currentRealm;

    private TestRealm testRealm;

    @BeforeAll
    void createRealm() {
        testRealm = TestRealm.create();
    }

    @BeforeEach
    void stubCurrentRealm() {
        Mockito.when(currentRealm.name()).thenReturn(testRealm.name());
    }

    @AfterEach
    void cleanUp() {
        for (String org : List.of(ORG, OTHER_ORG)) {
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

    /** An org with an owner (who takes a seat) and the given members limit, null = no limit. */
    private void createOrg(Integer maxMembers) {
        orgService.create(testRealm.name(),
                new CreateOrgRequest(ORG, Set.of(), OWNER_EMAIL, "changeme", "Olivia", "Owner", maxMembers, null));
    }

    private String ownerId() {
        return testRealm.resource().users().searchByEmail(OWNER_EMAIL, true).getFirst().getId();
    }

    private int userCount() {
        return testRealm.resource().users().list(0, 1000).size();
    }

    @Test
    @TestSecurity(user = "alice", roles = "org-" + ORG + ":org-admin")
    void thirdMemberOnALimitOfTwoReturns409AndCreatesNothing() {
        createOrg(2);
        memberService.add(testRealm.name(), ORG, new AddMemberRequest("bob@it-member-seats-org.test", "Bob", "Smith"));
        int usersBefore = userCount();

        given().contentType(ContentType.JSON)
                .body(new AddMemberRequest("carol@it-member-seats-org.test", "Carol", "Test"))
                .when().post(BASE, ORG)
                .then().statusCode(409)
                .body("message", containsString("no seat left"))
                .body("message", containsString(ORG));

        assertEquals(usersBefore, userCount());
    }

    @Test
    @TestSecurity(user = "alice", roles = "org-" + ORG + ":org-admin")
    void removingAMemberFreesASeat() {
        createOrg(2);
        String bobId = memberService.add(testRealm.name(), ORG,
                new AddMemberRequest("bob@it-member-seats-org.test", "Bob", "Smith")).id();

        given().contentType(ContentType.JSON)
                .body(new AddMemberRequest("carol@it-member-seats-org.test", "Carol", "Test"))
                .when().post(BASE, ORG)
                .then().statusCode(409);

        memberService.remove(testRealm.name(), ORG, bobId);

        given().contentType(ContentType.JSON)
                .body(new AddMemberRequest("carol@it-member-seats-org.test", "Carol", "Test"))
                .when().post(BASE, ORG)
                .then().statusCode(201);
    }

    /** A member disabled by an org admin, and one still pending its invitation (every member added here has no
     * password, hence pending), both keep their seat: the count does not drop, and a full org still refuses. */
    @Test
    @TestSecurity(user = "alice", roles = "org-" + ORG + ":org-admin")
    void disabledAndPendingMembersStillOccupyTheirSeat() {
        createOrg(2);
        String bobId = memberService.add(testRealm.name(), ORG,
                new AddMemberRequest("bob@it-member-seats-org.test", "Bob", "Smith")).id();
        memberService.setEnabled(testRealm.name(), ORG, bobId, false, ownerId());

        given().when().get(SEATS_BASE, ORG)
                .then().statusCode(200)
                .body("members.used", equalTo(2))
                .body("members.max", equalTo(2));

        given().contentType(ContentType.JSON)
                .body(new AddMemberRequest("carol@it-member-seats-org.test", "Carol", "Test"))
                .when().post(BASE, ORG)
                .then().statusCode(409);
    }

    @Test
    @TestSecurity(user = "alice", roles = "org-" + ORG + ":org-admin")
    void orgDisabledBeatsTheSeatLimit() {
        createOrg(1);
        orgService.setEnabled(testRealm.name(), ORG, false);

        given().contentType(ContentType.JSON)
                .body(new AddMemberRequest("carol@it-member-seats-org.test", "Carol", "Test"))
                .when().post(BASE, ORG)
                .then().statusCode(409)
                .body("message", containsString("disabled"));
    }

    @Test
    @TestSecurity(user = "alice", roles = "org-" + ORG + ":org-admin")
    void memberOfAnotherOrgBeatsTheSeatLimit() {
        createOrg(1);
        orgService.create(testRealm.name(), new CreateOrgRequest(OTHER_ORG, Set.of(), null, null));
        memberService.add(testRealm.name(), OTHER_ORG,
                new AddMemberRequest("dave@it-member-seats-org.test", "Dave", "Test"));

        given().contentType(ContentType.JSON)
                .body(new AddMemberRequest("dave@it-member-seats-org.test", "Dave", "Test"))
                .when().post(BASE, ORG)
                .then().statusCode(409)
                .body("message", containsString("cannot be added"));
    }

    // the privileged check (MemberEligibility.checkReusable) runs before the seat check: a full org still answers
    // "cannot be added", not "no seat left", the same ordering as a member of another org just above
    @Test
    @TestSecurity(user = "alice", roles = "org-" + ORG + ":org-admin")
    void privilegedUserBeatsTheSeatLimit() {
        createOrg(1);
        String bossId = testRealm.createUser("boss", "boss@it-member-seats-org.test");
        testRealm.createRealmRole("realm-owner");
        testRealm.grantRealmRole(bossId, "realm-owner");

        given().contentType(ContentType.JSON)
                .body(new AddMemberRequest("boss@it-member-seats-org.test", "Boss", "Test"))
                .when().post(BASE, ORG)
                .then().statusCode(409)
                .body("message", containsString("cannot be added"));
    }

    @Test
    @TestSecurity(user = "alice", roles = "org-" + ORG + ":org-admin")
    void alreadyAMemberOfThisFullOrgReturnsAlreadyAMemberMessage() {
        createOrg(1);

        given().contentType(ContentType.JSON)
                .body(new AddMemberRequest(OWNER_EMAIL, "Olivia", "Owner"))
                .when().post(BASE, ORG)
                .then().statusCode(409)
                .body("message", containsString("already a member"));
    }

    @Test
    @TestSecurity(user = "owner", roles = "realm-owner")
    void realmOwnerIsAlsoRefusedOnAFullOrg() {
        createOrg(1);

        given().contentType(ContentType.JSON)
                .body(new AddMemberRequest("carol@it-member-seats-org.test", "Carol", "Test"))
                .when().post(BASE, ORG)
                .then().statusCode(409)
                .body("message", containsString("no seat left"));
    }

    @Test
    @TestSecurity(user = "alice", roles = "org-" + ORG + ":org-admin")
    void settingMaxMembersToZeroRemovesNobodyButRefusesFurtherAdds() {
        createOrg(null);
        memberService.add(testRealm.name(), ORG,
                new AddMemberRequest("bob@it-member-seats-org.test", "Bob", "Smith"));
        memberService.add(testRealm.name(), ORG,
                new AddMemberRequest("carol@it-member-seats-org.test", "Carol", "Test"));

        seatService.update(testRealm.name(), ORG, new UpdateSeatsRequest(0, null));

        given().when().get(SEATS_BASE, ORG)
                .then().statusCode(200)
                .body("members.used", equalTo(3))
                .body("members.max", equalTo(0));

        given().contentType(ContentType.JSON)
                .body(new AddMemberRequest("dave@it-member-seats-org.test", "Dave", "Test"))
                .when().post(BASE, ORG)
                .then().statusCode(409);
    }

    @Test
    @TestSecurity(user = "alice", roles = "org-" + ORG + ":org-admin")
    void loweringTheLimitBelowTheCountRemovesNobodyAndRefusesUntilEnoughLeave() {
        createOrg(null);
        String bobId = memberService.add(testRealm.name(), ORG,
                new AddMemberRequest("bob@it-member-seats-org.test", "Bob", "Smith")).id();
        String carolId = memberService.add(testRealm.name(), ORG,
                new AddMemberRequest("carol@it-member-seats-org.test", "Carol", "Test")).id();
        String daveId = memberService.add(testRealm.name(), ORG,
                new AddMemberRequest("dave@it-member-seats-org.test", "Dave", "Test")).id();
        memberService.add(testRealm.name(), ORG,
                new AddMemberRequest("erin@it-member-seats-org.test", "Erin", "Test"));
        // owner + 4 members = 5

        seatService.update(testRealm.name(), ORG, new UpdateSeatsRequest(3, null));

        given().when().get(SEATS_BASE, ORG)
                .then().statusCode(200)
                .body("members.used", equalTo(5))
                .body("members.max", equalTo(3));

        given().contentType(ContentType.JSON)
                .body(new AddMemberRequest("frank@it-member-seats-org.test", "Frank", "Test"))
                .when().post(BASE, ORG)
                .then().statusCode(409);

        // 5 -> 4: still refused (4 >= 3)
        memberService.remove(testRealm.name(), ORG, bobId);
        given().contentType(ContentType.JSON)
                .body(new AddMemberRequest("frank@it-member-seats-org.test", "Frank", "Test"))
                .when().post(BASE, ORG)
                .then().statusCode(409);

        // 4 -> 3: below the limit, the next add succeeds
        memberService.remove(testRealm.name(), ORG, carolId);
        memberService.remove(testRealm.name(), ORG, daveId);
        given().contentType(ContentType.JSON)
                .body(new AddMemberRequest("frank@it-member-seats-org.test", "Frank", "Test"))
                .when().post(BASE, ORG)
                .then().statusCode(201);
    }

    /** A member limit does not block a service account creation: the two counts are separate (SeatGuard). */
    @Test
    @TestSecurity(user = "alice", roles = "org-" + ORG + ":org-admin")
    void aFullMemberLimitDoesNotBlockAServiceAccountCreation() {
        createOrg(1);

        given().contentType(ContentType.JSON)
                .body(new CreateServiceAccountRequest("batch", null, null))
                .when().post("/api/organisations/{org}/service-accounts", ORG)
                .then().statusCode(201);
    }
}
