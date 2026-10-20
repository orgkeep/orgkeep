package io.orgkeep.dashboard.auth;

import io.orgkeep.api.OrgMemberService;
import io.orgkeep.api.OrgService;
import io.orgkeep.api.UserGroupService;
import io.orgkeep.api.model.AddMemberRequest;
import io.orgkeep.api.model.CreateOrgRequest;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.Cookie;
import io.restassured.http.Cookies;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.htmlunit.WebClient;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.html.HtmlPasswordInput;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.CredentialRepresentation;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.util.List;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.startsWith;

/**
 * The two ways in: a bearer token (the applications of the realm owner) and the session cookie of the browser login
 * (the front), in the application that embeds the core. Against a real Keycloak (Dev Services, realm acme created by
 * the OnPremiseBootstrap of the core): these rules are security, a mock would only test the mock.
 * <p>
 * The session is obtained like a user would: HtmlUnit, a headless browser, follows the redirects to the Keycloak
 * login page and fills in the form. Its cookies are then given to RestAssured.
 */
@QuarkusTest
class SessionAuthenticationTest {
    // %test.orgkeep.realm-name and %test.orgkeep.owner.* of orgkeep-core, created at startup
    private static final String REALM = "acme";
    private static final String OWNER = "admin@acme.com";
    private static final String OWNER_PASSWORD = "changeme";
    // an org admin, who only gets in through the roles of its org client (resource_access)
    private static final String ORG = "session-org";
    private static final String ORG_ADMIN = "olga@session-org.test";
    private static final String ORG_ADMIN_PASSWORD = "olga-password";
    private static final String CLIENT_ID = "orgkeep-dashboard";
    // %dev,test.quarkus.oidc.dashboard.credentials.secret
    private static final String CLIENT_SECRET = "dashboard-dev-secret";
    private static final String CATALOGS = "/api/admin/catalogs";

    // created once for the class: the realm lives as long as the Keycloak of Dev Services
    private static boolean dataReady;

    @Inject
    Keycloak keycloak;
    @Inject
    OrgService orgService;
    @Inject
    OrgMemberService orgMemberService;
    @Inject
    UserGroupService userGroupService;

    // the URL the test application listens on: the redirect URI of the client must match it
    @TestHTTPResource("/")
    URL root;

    @ConfigProperty(name = "quarkus.oidc.auth-server-url")
    String realmUrl;

    @BeforeEach
    void prepare() {
        if (dataReady) {
            return;
        }
        createDashboardClient();
        createOrgAdmin();
        dataReady = true;
    }

    // ---- Bearer: the default tenant, as on the core alone ----

    @Test
    void acceptsABearerToken() {
        given().auth().oauth2(passwordGrant(OWNER, OWNER_PASSWORD))
                .when().get(CATALOGS)
                .then().statusCode(200);
    }

    // no credentials at all: 401, not a redirect to Keycloak (that is for the browser login only)
    @Test
    void answers401WithoutCredentials() {
        given().redirects().follow(false)
                .when().get(CATALOGS)
                .then().statusCode(401);
    }

    // ---- Session: the "dashboard" tenant ----

    @Test
    void acceptsTheSessionWithTheCsrfHeader() {
        given().cookies(login(OWNER, OWNER_PASSWORD))
                .header(DashboardSession.CSRF_HEADER, DashboardSession.CSRF_HEADER_VALUE)
                .when().get(CATALOGS)
                .then().statusCode(200);
    }

    // what a page of another site gets when it makes the browser call the API: the cookie, but no header
    @Test
    void refusesTheSessionWithoutTheCsrfHeader() {
        given().cookies(login(OWNER, OWNER_PASSWORD))
                .when().get(CATALOGS)
                .then().statusCode(403)
                .body("message", startsWith("Missing header"));
    }

    // a refused token never falls back to the session sent along with it
    @Test
    void neverFallsBackFromABadTokenToTheSession() {
        given().cookies(login(OWNER, OWNER_PASSWORD))
                .header(DashboardSession.CSRF_HEADER, DashboardSession.CSRF_HEADER_VALUE)
                .auth().oauth2("not-a-token")
                .when().get(CATALOGS)
                .then().statusCode(401);
    }

    // The org roles come from resource_access of the access token (OrgRolesAugmentor of the core), while the
    // principal of a session is the ID token: the org admin must still reach the endpoints of its org
    @Test
    void givesTheOrgRolesToASession() {
        Cookies session = login(ORG_ADMIN, ORG_ADMIN_PASSWORD);

        given().cookies(session)
                .header(DashboardSession.CSRF_HEADER, DashboardSession.CSRF_HEADER_VALUE)
                .when().get("/api/organisations/{org}/members", ORG)
                .then().statusCode(200)
                .body("email", hasItem(ORG_ADMIN));
        // and only those: the realm owner endpoints stay closed
        given().cookies(session)
                .header(DashboardSession.CSRF_HEADER, DashboardSession.CSRF_HEADER_VALUE)
                .when().get(CATALOGS)
                .then().statusCode(403);
    }

    @Test
    void tellsTheFrontWhoIsLoggedIn() {
        given().cookies(login(ORG_ADMIN, ORG_ADMIN_PASSWORD))
                .header(DashboardSession.CSRF_HEADER, DashboardSession.CSRF_HEADER_VALUE)
                .when().get("/api/me")
                .then().statusCode(200)
                .body("authenticated", equalTo(true))
                .body("orgRoles." + ORG, hasItem("org-admin"));
    }

    @Test
    void sendsTheSecurityHeaders() {
        given().when().get("/api/me")
                .then().statusCode(200)
                .header("Content-Security-Policy", startsWith("default-src 'self'"))
                .header("X-Content-Type-Options", "nosniff");
    }

    // ---- Helpers ----

    /** Logs in through the browser flow, @return the cookies of the session. */
    private Cookies login(String username, String password) {
        try (WebClient browser = new WebClient()) {
            // the Keycloak pages work without JavaScript; the end of the flow (the front on /) is not served in tests
            browser.getOptions().setJavaScriptEnabled(false);
            browser.getOptions().setCssEnabled(false);
            browser.getOptions().setThrowExceptionOnFailingStatusCode(false);

            // /api/auth/login -> redirect to the Keycloak login page
            HtmlPage page = browser.getPage(root + "api/auth/login");
            HtmlForm form = page.getForms().getFirst();
            form.getInputByName("username").type(username);
            // Keycloak shows the password on a second page when the realm has organisations (identity-first login)
            if (form.getInputsByName("password").isEmpty()) {
                page = page.getHtmlElementById("kc-login").click();
                form = page.getForms().getFirst();
            }
            ((HtmlPasswordInput) form.getInputByName("password")).type(password);
            // Keycloak -> /api/auth/login with the code -> session created -> redirect to /
            page.getHtmlElementById("kc-login").click();

            List<Cookie> cookies = browser.getCookieManager().getCookies().stream()
                    .filter(cookie -> cookie.getName().startsWith("q_session"))
                    .map(cookie -> new Cookie.Builder(cookie.getName(), cookie.getValue()).build())
                    .toList();
            if (cookies.isEmpty()) {
                throw new IllegalStateException("No session after the login of " + username);
            }
            return new Cookies(cookies);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** An access token of the realm, the way an application of the realm owner gets one. */
    private String passwordGrant(String username, String password) {
        return given().formParam("grant_type", "password")
                .formParam("client_id", CLIENT_ID)
                .formParam("client_secret", CLIENT_SECRET)
                .formParam("username", username)
                .formParam("password", password)
                .when().post(realmUrl + "/protocol/openid-connect/token")
                .then().statusCode(200)
                .extract().path("access_token");
    }

    /** The client of the browser login, created in dev by the DemoSeeder of the core. */
    private void createDashboardClient() {
        ClientRepresentation client = new ClientRepresentation();
        client.setClientId(CLIENT_ID);
        client.setSecret(CLIENT_SECRET);
        client.setPublicClient(false);
        client.setStandardFlowEnabled(true);
        // only for passwordGrant() above: the tests need a bearer token too
        client.setDirectAccessGrantsEnabled(true);
        client.setRedirectUris(List.of(root + "*"));
        keycloak.realm(REALM).clients().create(client).close();
    }

    /** An organisation, and a member put in its admin group, with a password to log in. */
    private void createOrgAdmin() {
        orgService.create(REALM, new CreateOrgRequest(ORG, Set.of(), null, null));
        String userId = orgMemberService.add(REALM, ORG, new AddMemberRequest(ORG_ADMIN, "Olga", "Admin")).id();
        userGroupService.addMember(REALM, ORG, "admin", userId);

        CredentialRepresentation credential = new CredentialRepresentation();
        credential.setType(CredentialRepresentation.PASSWORD);
        credential.setValue(ORG_ADMIN_PASSWORD);
        credential.setTemporary(false);
        keycloak.realm(REALM).users().get(userId).resetPassword(credential);
    }
}
