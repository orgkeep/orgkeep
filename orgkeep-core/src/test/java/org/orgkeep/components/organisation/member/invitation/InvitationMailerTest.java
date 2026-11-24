package org.orgkeep.components.organisation.member.invitation;

import org.orgkeep.api.LoginClientService;
import org.orgkeep.api.OrgMemberService;
import org.orgkeep.api.OrgService;
import org.orgkeep.api.RealmProvisioner;
import org.orgkeep.api.events.UserCreated;
import org.orgkeep.api.exceptions.MemberNotFoundException;
import org.orgkeep.api.exceptions.OperationNotAllowedException;
import org.orgkeep.api.exceptions.OrgNotFoundException;
import org.orgkeep.api.model.AddMemberRequest;
import org.orgkeep.api.model.CreateOrgRequest;
import org.orgkeep.api.model.OrgMember;
import org.orgkeep.api.model.SmtpSettings;
import org.orgkeep.api.model.UpdateLoginClientRequest;
import org.orgkeep.fixtures.TestRealm;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.eventbus.MessageConsumer;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.keycloak.representations.idm.RealmRepresentation;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End to end: Keycloak really sends the email, to the Mailpit of compose-devservices.yml,
 * and the test reads it back through the Mailpit API.
 * <p>
 * One realm for the whole class: the org "acme" is deleted after each test (@AfterEach). Its members keep their
 * unique random address (see {@code email} below), so they never need cleaning up for Mailpit's sake.
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InvitationMailerTest {
    // the Mailpit API as seen from the test (host port), Keycloak sends to mailpit:1025 inside the Docker network
    private static final String MAILPIT_API = "http://localhost:8025/api/v1";
    private static final String ORG = "acme";

    @Inject OrgService orgService;
    @Inject OrgMemberService memberService;
    @Inject RealmProvisioner realmProvisioner;
    @Inject LoginClientService loginClientService;
    @Inject InvitationMailer mailer;
    @Inject EventBus eventBus;

    private TestRealm testRealm;
    // Mailpit is shared by all tests (and the dev mode): a unique address per test isolates its emails
    private String email;

    @BeforeAll
    void createRealm() {
        testRealm = TestRealm.create();
        // creates the login client (named after the realm): InvitationMailer always passes a client id, this one
        // when the user is not sent to the dashboard
        realmProvisioner.ensureDefaults(testRealm.name());
    }

    @BeforeEach
    void setUp() {
        email = "user-" + UUID.randomUUID().toString().substring(0, 8) + "@acme.test";
    }

    @AfterEach
    void cleanUp() {
        try {
            orgService.delete(testRealm.name(), ORG);
        } catch (OrgNotFoundException ignored) {
            // not created by this test
        }
        // a test that sets the home URL leaves the next one a clean client (a test that disables the client
        // re-enables it itself, once its own assertions are done)
        loginClientService.update(testRealm.name(), new UpdateLoginClientRequest(List.of(), List.of(), List.of(), null, null, ""));
        // some tests rely on the realm having no SMTP server configured: undo configureSmtp() directly, the
        // service has no "remove" operation. An empty map, not null: Keycloak only changes the fields that are
        // present in the representation it receives, and a null field is left out by Jackson
        RealmRepresentation update = new RealmRepresentation();
        update.setSmtpServer(Map.of());
        testRealm.resource().update(update);
    }

    @AfterAll
    void deleteRealm() {
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

    // a member's invitation always uses the login client (orgkeep.dashboard.url is not set in this test profile):
    // Keycloak's last page links to its home URL, once one is set (decision: client_id is passed even without one,
    // see InvitationMailer.targetClientId)
    @Test
    void memberInvitationLinksToTheLoginClientHomeUrl() {
        configureSmtp();
        loginClientService.update(testRealm.name(),
                new UpdateLoginClientRequest(List.of(), List.of(), List.of(), null, null, "https://app.example.com/home"));
        orgService.create(testRealm.name(), new CreateOrgRequest(ORG, Set.of("acme.test"), null, null));
        memberService.add(testRealm.name(), ORG, new AddMemberRequest(email, "Jane", "Doe"));
        await().atMost(Duration.ofSeconds(10)).until(() -> emailsTo(email) == 1);

        String lastPage = followInvitationLink(invitationLinkFor(email), "chosen-password");

        assertEquals("https://app.example.com/home", backToApplicationLink(lastPage));
    }

    // without a home URL on the login client, Keycloak's last page is as it is today: no link
    @Test
    void memberInvitationHasNoLinkWithoutAHomeUrl() {
        configureSmtp();
        orgService.create(testRealm.name(), new CreateOrgRequest(ORG, Set.of("acme.test"), null, null));
        memberService.add(testRealm.name(), ORG, new AddMemberRequest(email, "Jane", "Doe"));
        await().atMost(Duration.ofSeconds(10)).until(() -> emailsTo(email) == 1);

        String lastPage = followInvitationLink(invitationLinkFor(email), "chosen-password");

        assertNull(backToApplicationLink(lastPage));
    }

    // Keycloak rejects a disabled client (400): the invitation still goes out, sent again without a client
    @Test
    void fallbackSendsWithoutClientWhenTheLoginClientIsDisabled() {
        configureSmtp();
        orgService.create(testRealm.name(), new CreateOrgRequest(ORG, Set.of("acme.test"), null, null));
        setLoginClientEnabled(false);
        try {
            memberService.add(testRealm.name(), ORG, new AddMemberRequest(email, "Jane", "Doe"));
            // the invitation is not lost: it still arrives, sent again without the (disabled) client
            await().atMost(Duration.ofSeconds(10)).until(() -> emailsTo(email) == 1);
        } finally {
            setLoginClientEnabled(true);
        }

        String lastPage = followInvitationLink(invitationLinkFor(email), "chosen-password");
        assertNull(backToApplicationLink(lastPage));
    }

    @Test
    void noInvitationWhenThePasswordIsSet() {
        configureSmtp();
        String userId = testRealm.createUser(email, email);

        // called directly, not through the event bus: synchronous, so "no email" can be checked right away
        mailer.onUserCreated(new UserCreated(testRealm.name(), ORG, userId, false, true));

        assertEquals(0, emailsTo(email));
    }

    @Test
    void noInvitationWithoutSmtpServer() {
        String userId = testRealm.createUser(email, email);

        // only logs a warning: creating the user must not fail because the realm cannot send emails
        mailer.invite(testRealm.name(), userId, false);

        assertEquals(0, emailsTo(email));
    }

    // core alone (orgkeep.dashboard.url not set in this test profile): an owner's invitation uses the login client
    // too, like a member's
    @Test
    void ownerInvitationLinksToTheLoginClientWithoutADashboard() {
        configureSmtp();
        loginClientService.update(testRealm.name(),
                new UpdateLoginClientRequest(List.of(), List.of(), List.of(), null, null, "https://app.example.com/home"));
        orgService.create(testRealm.name(), new CreateOrgRequest(ORG, Set.of("acme.test"), email, null, "Olivia", "Owner"));
        await().atMost(Duration.ofSeconds(10)).until(() -> emailsTo(email) == 1);

        String lastPage = followInvitationLink(invitationLinkFor(email), "chosen-password");

        assertEquals("https://app.example.com/home", backToApplicationLink(lastPage));
    }

    // the owner flag drives the choice of client for the invitation (InvitationMailer): false for a member added by
    // an org admin
    @Test
    void addPublishesUserCreatedWithOwnerFalse() throws InterruptedException {
        orgService.create(testRealm.name(), new CreateOrgRequest(ORG, Set.of("acme.test"), null, null));
        BlockingQueue<UserCreated> events = new ArrayBlockingQueue<>(1);
        MessageConsumer<UserCreated> consumer = eventBus.consumer(UserCreated.ADDRESS, message -> events.offer(message.body()));
        try {
            memberService.add(testRealm.name(), ORG, new AddMemberRequest(email, "Jane", "Doe"));

            UserCreated event = events.poll(5, TimeUnit.SECONDS);
            assertNotNull(event, "UserCreated not published");
            assertFalse(event.owner());
            assertEquals(ORG, event.orgName());
        } finally {
            consumer.unregister();
        }
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

    // Pending = emailVerified not true AND requiredActions contains UPDATE_PASSWORD (members.md): a member added by
    // add() carries both, a temporary-password owner carries the required action too but is created with
    // emailVerified=true (OrgMapper.toOwnerRepresentation), so it is never pending. Keycloak removes the required
    // action once it is completed through the invitation link (spike of #55): followed here for real (Mailpit, then
    // the "choose a password" page Keycloak serves), rather than simulated, so the test proves Keycloak's own
    // behavior and not an assumption about it.
    @Test
    void invitationIsPendingUntilTheRequiredActionIsDone() {
        configureSmtp();
        orgService.create(testRealm.name(), new CreateOrgRequest(ORG, Set.of("acme.test"), "owner@acme.test", "changeme", "Olivia", "Owner"));
        String userId = memberService.add(testRealm.name(), ORG, new AddMemberRequest(email, "Jane", "Doe")).id();
        await().atMost(Duration.ofSeconds(10)).until(() -> emailsTo(email) == 1);

        assertTrue(memberOf(userId).invitationPending());
        assertFalse(memberOf(userIdOf("owner@acme.test")).invitationPending());

        followInvitationLink(invitationLinkFor(email), "chosen-password");

        assertFalse(memberOf(userId).invitationPending());
    }

    /** Reads the invitation link Keycloak put in the latest email sent to that address, through the Mailpit API. */
    private String invitationLinkFor(String address) {
        String messageId = given()
                .queryParam("query", "to:" + address)
                .get(MAILPIT_API + "/search")
                .then().statusCode(200)
                .extract().path("messages[0].ID");
        String html = given()
                .get(MAILPIT_API + "/message/" + messageId)
                .then().statusCode(200)
                .extract().path("HTML");
        Matcher link = Pattern.compile("href=\"([^\"]*login-actions/action-token[^\"]*)\"").matcher(html);
        if (!link.find()) {
            throw new IllegalStateException("No invitation link found in the email to " + address);
        }
        return link.group(1).replace("&amp;", "&");
    }

    /**
     * Follows the invitation link like a browser would: the action token already identifies the user, so Keycloak
     * serves its "choose a password" page directly, with no login step. The page is read with RestAssured instead
     * of a real browser, and the form it shows is submitted the same way.
     * <p>
     * Cookies are carried by hand (not RestAssured's CookieFilter): Keycloak's session cookies here are {@code
     * Secure}, which a strict HTTP client drops again on the next plain {@code http://localhost} call (unlike a
     * real browser, which treats localhost as a secure context). Carrying the name/value pairs ourselves, ignoring
     * the cookie attributes, works the same as a browser would.
     *
     * @return the HTML of the page Keycloak answers with once the password is set (its last page: no client, the
     *         "account updated" info page; with a client, the same page with a "Back to Application" link, or a
     *         redirect it never sends in this flow, since no redirect_uri is ever given, see KeycloakUsers)
     */
    private String followInvitationLink(String link, String newPassword) {
        Response response = given().get(link);
        response.then().statusCode(200);
        Map<String, String> cookies = new HashMap<>(response.getCookies());
        String page = response.asString();

        // Keycloak shows a "click here to proceed" confirmation first (against email scanners prefetching the
        // link): the session cookie set by that GET makes the very same link start the required action for real
        Matcher proceed = Pattern.compile("href=\"([^\"]*login-actions/action-token[^\"]*)\"").matcher(page);
        if (proceed.find()) {
            response = given().cookies(cookies).get(proceed.group(1).replace("&amp;", "&"));
            response.then().statusCode(200);
            cookies.putAll(response.getCookies());
            page = response.asString();
        }

        String formAction = formAction(page, "kc-passwd-update-form");

        // The invitation link carries no redirect_uri, so Keycloak has nowhere to send the browser once the
        // required action is done: it answers 200 with its own "account updated" info page instead of a redirect.
        // A rejected password (e.g. the two fields do not match) would answer 200 too, with the same form again:
        // the real proof is the assertion on invitationPending() once this method returns.
        String result = given().cookies(cookies)
                .formParam("password-new", newPassword)
                .formParam("password-confirm", newPassword)
                .post(formAction)
                .then().statusCode(200)
                .extract().asString();
        if (result.contains("kc-passwd-update-form")) {
            throw new IllegalStateException("The password was rejected: " + result);
        }
        return result;
    }

    /**
     * The href of the "Back to Application" link on Keycloak's last page, if any (Keycloak's base theme puts it
     * there when the client has a baseUrl, see info.ftl). Null when the page has no such link (no client, or a
     * client without a home URL).
     */
    private String backToApplicationLink(String html) {
        // the base theme prefixes the text with an arrow ("« Back to Application"): matched loosely, up to "<"
        Matcher link = Pattern.compile("<a href=\"([^\"]*)\"[^>]*>[^<]*Back to Application").matcher(html);
        return link.find() ? link.group(1).replace("&amp;", "&") : null;
    }

    /** The action URL of the named form, whatever the order of its attributes (the realm's theme may differ). */
    private String formAction(String html, String formId) {
        Matcher tags = Pattern.compile("<form\\b[^>]*>").matcher(html);
        while (tags.find()) {
            String tag = tags.group();
            if (tag.contains("id=\"" + formId + "\"")) {
                Matcher action = Pattern.compile("action=\"([^\"]*)\"").matcher(tag);
                if (action.find()) {
                    return action.group(1).replace("&amp;", "&");
                }
            }
        }
        throw new IllegalStateException("No form '" + formId + "' found in the page: " + html);
    }

    // An existing user, not created by add(): it keeps its own required actions, none here, so it is never pending
    @Test
    void reusedMemberIsNotPending() {
        orgService.create(testRealm.name(), new CreateOrgRequest(ORG, Set.of("acme.test"), null, null));
        testRealm.createUser(email, email);

        String userId = memberService.add(testRealm.name(), ORG, new AddMemberRequest(email, "Jane", "Doe")).id();

        assertFalse(memberOf(userId).invitationPending());
    }

    private OrgMember memberOf(String userId) {
        return memberService.list(testRealm.name(), ORG, null, null, 0, 100).items().stream()
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

    /** Disables (or re-enables) the login client, to measure the fallback of InvitationMailer on Keycloak's 400. */
    private void setLoginClientEnabled(boolean enabled) {
        var client = testRealm.resource().clients().findByClientId(testRealm.name()).get(0);
        client.setEnabled(enabled);
        testRealm.resource().clients().get(client.getId()).update(client);
    }

    private int emailsTo(String address) {
        return given()
                .queryParam("query", "to:" + address)
                .get(MAILPIT_API + "/search")
                .then().statusCode(200)
                .extract().path("messages_count");
    }
}
