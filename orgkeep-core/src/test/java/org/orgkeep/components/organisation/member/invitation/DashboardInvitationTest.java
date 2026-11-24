package org.orgkeep.components.organisation.member.invitation;

import org.orgkeep.api.OrgMemberService;
import org.orgkeep.api.OrgService;
import org.orgkeep.api.RealmProvisioner;
import org.orgkeep.api.events.UserCreated;
import org.orgkeep.api.exceptions.OrgNotFoundException;
import org.orgkeep.api.model.AddMemberRequest;
import org.orgkeep.api.model.CreateOrgRequest;
import org.orgkeep.api.model.DashboardClientSettings;
import org.orgkeep.api.model.SmtpSettings;
import org.orgkeep.fixtures.TestRealm;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The invitation targets the dashboard client when it is deployed (orgkeep.dashboard.url): a realm owner, or an org
 * owner, lands there instead of the login client. A separate test class (and so a separate application restart,
 * see QuarkusTestProfile) since the other InvitationMailerTest measures the opposite setting (core alone).
 */
@QuarkusTest
@TestProfile(DashboardInvitationTest.DashboardProfile.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DashboardInvitationTest {
    private static final String MAILPIT_API = "http://localhost:8025/api/v1";
    private static final String ORG = "acme";
    private static final String DASHBOARD_CLIENT_ID = "orgkeep-dashboard";
    private static final String DASHBOARD_URL = "https://dashboard.acme.test";

    public static class DashboardProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("orgkeep.dashboard.url", DASHBOARD_URL, "orgkeep.dashboard.client-id", DASHBOARD_CLIENT_ID);
        }
    }

    @Inject OrgService orgService;
    @Inject OrgMemberService memberService;
    @Inject RealmProvisioner realmProvisioner;
    @Inject InvitationMailer mailer;

    private TestRealm testRealm;
    private String email;

    @BeforeAll
    void createRealm() {
        testRealm = TestRealm.create();
        realmProvisioner.ensureDefaults(testRealm.name());
        // the dashboard client, as OnPremiseBootstrap creates it at every startup when orgkeep.dashboard.url is set
        realmProvisioner.ensureDashboardClient(testRealm.name(),
                new DashboardClientSettings(DASHBOARD_CLIENT_ID, DASHBOARD_URL, null));
        realmProvisioner.configureSmtp(testRealm.name(),
                new SmtpSettings("mailpit", 1025, "no-reply@orgkeep.test", null, null, null, false, false));
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
    }

    @AfterAll
    void deleteRealm() {
        testRealm.close();
    }

    @Test
    void orgOwnerInvitationLinksToTheDashboard() {
        // ownerPassword null: the owner has none yet, the invitation fires
        orgService.create(testRealm.name(), new CreateOrgRequest(ORG, Set.of("acme.test"), email, null, "Olivia", "Owner"));
        await().atMost(Duration.ofSeconds(10)).until(() -> emailsTo(email) == 1);

        String lastPage = followInvitationLink(invitationLinkFor(email), "chosen-password");

        assertEquals(DASHBOARD_URL + "/", backToApplicationLink(lastPage));
    }

    @Test
    void realmOwnerInvitationLinksToTheDashboard() {
        // created directly, like a bootstrap would, and invited synchronously (not through the event bus): this
        // measures the choice of client, not the bootstrap sequencing (RealmBootstrap creates the dashboard client
        // after the realm owner, see OnPremiseBootstrap; InvitationMailer's own fallback absorbs that race)
        String userId = testRealm.createUser(email, email);

        mailer.onUserCreated(new UserCreated(testRealm.name(), null, userId, true, false));
        await().atMost(Duration.ofSeconds(10)).until(() -> emailsTo(email) == 1);

        String lastPage = followInvitationLink(invitationLinkFor(email), "chosen-password");

        assertEquals(DASHBOARD_URL + "/", backToApplicationLink(lastPage));
    }

    @Test
    void resendToAnOrgOwnerLinksToTheDashboard() {
        String ownerId = orgService.create(testRealm.name(),
                new CreateOrgRequest(ORG, Set.of("acme.test"), email, null, "Olivia", "Owner")).ownerId();
        await().atMost(Duration.ofSeconds(10)).until(() -> emailsTo(email) == 1);

        memberService.resendInvitation(testRealm.name(), ORG, ownerId);
        assertEquals(2, emailsTo(email));

        String lastPage = followInvitationLink(invitationLinkFor(email), "chosen-password");
        assertEquals(DASHBOARD_URL + "/", backToApplicationLink(lastPage));
    }

    // org-admin alone (the admin group, not org-owner) does not count: back to the login client, not the dashboard
    @Test
    void resendToAnOrgAdminThatIsNotAnOwnerLinksToTheLoginClient() {
        orgService.create(testRealm.name(), new CreateOrgRequest(ORG, Set.of("acme.test"), null, null));
        String userId = memberService.add(testRealm.name(), ORG, new AddMemberRequest(email, "Jane", "Doe")).id();
        testRealm.joinGroup(userId, "/" + ORG + "/user-groups/admin");
        await().atMost(Duration.ofSeconds(10)).until(() -> emailsTo(email) == 1);

        memberService.resendInvitation(testRealm.name(), ORG, userId);
        assertEquals(2, emailsTo(email));

        String lastPage = followInvitationLink(invitationLinkFor(email), "chosen-password");
        // the login client has no home URL here: no link at all, which is enough to prove it is not the dashboard
        assertNull(backToApplicationLink(lastPage));
    }

    /**
     * Reads the invitation link Keycloak put in the latest email sent to that address, through the Mailpit API.
     * A resend does not invalidate the first email's link (members.md), but both target the same client here:
     * reading the latest message is enough.
     */
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

    /** Follows the invitation link and submits the new password, like InvitationMailerTest does. */
    private String followInvitationLink(String link, String newPassword) {
        Response response = given().get(link);
        response.then().statusCode(200);
        Map<String, String> cookies = new HashMap<>(response.getCookies());
        String page = response.asString();

        Matcher proceed = Pattern.compile("href=\"([^\"]*login-actions/action-token[^\"]*)\"").matcher(page);
        if (proceed.find()) {
            response = given().cookies(cookies).get(proceed.group(1).replace("&amp;", "&"));
            response.then().statusCode(200);
            cookies.putAll(response.getCookies());
            page = response.asString();
        }

        String formAction = formAction(page, "kc-passwd-update-form");
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

    /** The href of the "Back to Application" link on Keycloak's last page, if any. */
    private String backToApplicationLink(String html) {
        // the base theme prefixes the text with an arrow ("« Back to Application"): matched loosely, up to "<"
        Matcher link = Pattern.compile("<a href=\"([^\"]*)\"[^>]*>[^<]*Back to Application").matcher(html);
        return link.find() ? link.group(1).replace("&amp;", "&") : null;
    }

    private int emailsTo(String address) {
        return given()
                .queryParam("query", "to:" + address)
                .get(MAILPIT_API + "/search")
                .then().statusCode(200)
                .extract().path("messages_count");
    }
}
