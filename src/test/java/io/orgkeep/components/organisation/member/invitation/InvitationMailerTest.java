package io.orgkeep.components.organisation.member.invitation;

import io.orgkeep.api.OrgMemberService;
import io.orgkeep.api.OrgService;
import io.orgkeep.api.RealmProvisioner;
import io.orgkeep.api.events.UserCreated;
import io.orgkeep.api.model.AddMemberRequest;
import io.orgkeep.api.model.CreateOrgRequest;
import io.orgkeep.api.model.SmtpSettings;
import io.orgkeep.fixtures.TestRealm;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

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
