package org.orgkeep.components.organisation.member.invitation;

import org.orgkeep.api.OrgMemberService;
import org.orgkeep.api.OrgService;
import org.orgkeep.api.RealmProvisioner;
import org.orgkeep.api.events.UserCreated;
import org.orgkeep.api.exceptions.MemberNotFoundException;
import org.orgkeep.api.exceptions.OperationNotAllowedException;
import org.orgkeep.api.model.AddMemberRequest;
import org.orgkeep.api.model.CreateOrgRequest;
import org.orgkeep.api.model.OrgMember;
import org.orgkeep.api.model.SmtpSettings;
import org.orgkeep.fixtures.TestRealm;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.idm.CredentialRepresentation;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End to end: Keycloak really sends the email, to the Mailpit of compose-devservices.yml,
 * and the test reads it back through the Mailpit API.
 */
@QuarkusTest
class InvitationMailerTest {
    // the Mailpit API as seen from the test (host port), Keycloak sends to mailpit:1025 inside the Docker network
    private static final String MAILPIT_API = "http://localhost:8025/api/v1";
    private static final String ORG = "acme";

    @Inject Keycloak keycloak;
    @Inject OrgService orgService;
    @Inject OrgMemberService memberService;
    @Inject RealmProvisioner realmProvisioner;
    @Inject InvitationMailer mailer;

    private TestRealm testRealm;
    // Mailpit is shared by all tests (and the dev mode): a unique address per test isolates its emails
    private String email;

    @BeforeEach
    void setUp() {
        testRealm = TestRealm.create(keycloak);
        email = "user-" + UUID.randomUUID().toString().substring(0, 8) + "@acme.test";
    }

    @AfterEach
    void tearDown() {
        testRealm.close();
    }

    @Test
    void newMemberReceivesAnInvitation() {
        configureSmtp();
        orgService.create(testRealm.name(), new CreateOrgRequest(ORG, Set.of("acme.test"), null, null));

        memberService.add(testRealm.name(), ORG, new AddMemberRequest(email, "Jane", "Doe"));

        // the email is sent by an event bus consumer, after add() has returned: poll until it arrives
        await().atMost(Duration.ofSeconds(10)).until(() -> emailsTo(email) == 1);
    }

    @Test
    void noInvitationWhenThePasswordIsSet() {
        configureSmtp();
        String userId = testRealm.createUser(email, email);

        // called directly, not through the event bus: synchronous, so "no email" can be checked right away
        mailer.onUserCreated(new UserCreated(testRealm.name(), ORG, userId, true));

        assertEquals(0, emailsTo(email));
    }

    @Test
    void noInvitationWithoutSmtpServer() {
        String userId = testRealm.createUser(email, email);

        // only logs a warning: creating the user must not fail because the realm cannot send emails
        mailer.invite(testRealm.name(), userId);

        assertEquals(0, emailsTo(email));
    }

    @Test
    void resendSendsTheInvitationAgain() {
        configureSmtp();
        orgService.create(testRealm.name(), new CreateOrgRequest(ORG, Set.of("acme.test"), null, null));
        String userId = memberService.add(testRealm.name(), ORG, new AddMemberRequest(email, "Jane", "Doe")).id();
        await().atMost(Duration.ofSeconds(10)).until(() -> emailsTo(email) == 1);

        memberService.resendInvitation(testRealm.name(), ORG, userId);

        // synchronous this time: the email is in Mailpit when resendInvitation returns
        assertEquals(2, emailsTo(email));
    }

    @Test
    void resendWithoutSmtpServerIsRefused() {
        orgService.create(testRealm.name(), new CreateOrgRequest(ORG, Set.of("acme.test"), null, null));
        String userId = memberService.add(testRealm.name(), ORG, new AddMemberRequest(email, "Jane", "Doe")).id();

        // unlike the invitation sent at the creation, the admin who asks must know why nothing is sent
        OperationNotAllowedException error = assertThrows(OperationNotAllowedException.class,
                () -> memberService.resendInvitation(testRealm.name(), ORG, userId));
        assertTrue(error.getMessage().contains("SMTP"));
    }

    @Test
    void resendToAMemberWithAPasswordIsRefused() {
        configureSmtp();
        // the owner is created with a password: nothing to invite (it would be a password reset)
        orgService.create(testRealm.name(), new CreateOrgRequest(ORG, Set.of("acme.test"), email, "changeme", "Olivia", "Owner"));
        String ownerId = userIdOf(email);

        assertThrows(OperationNotAllowedException.class,
                () -> memberService.resendInvitation(testRealm.name(), ORG, ownerId));
        assertEquals(0, emailsTo(email));
    }

    @Test
    void resendToANonMemberIsRefused() {
        orgService.create(testRealm.name(), new CreateOrgRequest(ORG, Set.of("acme.test"), null, null));
        String userId = testRealm.createUser(email, email);

        assertThrows(MemberNotFoundException.class,
                () -> memberService.resendInvitation(testRealm.name(), ORG, userId));
    }

    @Test
    void invitationIsPendingUntilThePasswordIsChosen() {
        orgService.create(testRealm.name(), new CreateOrgRequest(ORG, Set.of("acme.test"), "owner@acme.test", "changeme", "Olivia", "Owner"));
        String userId = memberService.add(testRealm.name(), ORG, new AddMemberRequest(email, "Jane", "Doe")).id();

        assertTrue(memberOf(userId).invitationPending());
        // the owner has a password but has not verified their email: not pending either
        assertFalse(memberOf(userIdOf("owner@acme.test")).invitationPending());

        // what the invitation link does: a password, and the email verified
        CredentialRepresentation password = new CredentialRepresentation();
        password.setType(CredentialRepresentation.PASSWORD);
        password.setValue("chosen-password");
        password.setTemporary(false);
        testRealm.resource().users().get(userId).resetPassword(password);

        assertFalse(memberOf(userId).invitationPending());
    }

    private OrgMember memberOf(String userId) {
        return memberService.list(testRealm.name(), ORG).stream()
                .filter(member -> member.id().equals(userId))
                .findFirst().orElseThrow();
    }

    private String userIdOf(String address) {
        return testRealm.resource().users().searchByEmail(address, true).getFirst().getId();
    }

    private void configureSmtp() {
        realmProvisioner.configureSmtp(testRealm.name(),
                new SmtpSettings("mailpit", 1025, "no-reply@orgkeep.test", null, null, null, false, false));
    }

    private int emailsTo(String address) {
        return given()
                .queryParam("query", "to:" + address)
                .get(MAILPIT_API + "/search")
                .then().statusCode(200)
                .extract().path("messages_count");
    }
}
