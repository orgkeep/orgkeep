package org.orgkeep.components.realm.smtp;

import org.orgkeep.api.RealmProvisioner;
import org.orgkeep.api.model.UpdateSmtpRequest;
import org.orgkeep.fixtures.TestRealm;
import org.orgkeep.repository.keycloak.KeycloakRealms;
import org.orgkeep.web.CurrentRealm;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

import java.util.HashMap;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SMTP server when orgkeep.smtp.host is empty (SAAS behaves the same way: never config-managed): the API is
 * writable. A separate class from SmtpResourceTest (which measures the opposite, config-managed, setting), since
 * Quarkus can only apply one value of a configuration property per restart (@TestProfile), like
 * DashboardInvitationTest for orgkeep.dashboard.url.
 */
@QuarkusTest
@TestProfile(SmtpResourceWritableTest.WritableProfile.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SmtpResourceWritableTest {
    private static final String BASE = "/api/admin/smtp";

    public static class WritableProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("orgkeep.smtp.host", "");
        }
    }

    @Inject RealmProvisioner realmProvisioner;
    @Inject KeycloakRealms realms;
    @InjectMock CurrentRealm currentRealm;

    private TestRealm testRealm;

    @BeforeAll
    void createRealm() {
        testRealm = TestRealm.create();
        realmProvisioner.ensureDefaults(testRealm.name());
    }

    @BeforeEach
    void stubCurrentRealm() {
        Mockito.when(currentRealm.name()).thenReturn(testRealm.name());
    }

    @AfterEach
    void resetSmtpServer() {
        // tests stay independent: back to a realm without SMTP server
        realms.setSmtpServer(testRealm.name(), new HashMap<>());
    }

    @AfterAll
    void deleteRealm() {
        testRealm.close();
    }

    @Test
    @TestSecurity(user = "owner", roles = "realm-owner")
    void getOnARealmWithoutServerGivesAllNullFields() {
        given().when().get(BASE)
                .then().statusCode(200)
                .body("host", nullValue())
                .body("port", nullValue())
                .body("from", nullValue())
                .body("fromDisplayName", nullValue())
                .body("username", nullValue())
                .body("passwordSet", equalTo(false))
                .body("starttls", equalTo(false))
                .body("ssl", equalTo(false))
                .body("managedByConfiguration", equalTo(false));
    }

    @Test
    @TestSecurity(user = "owner", roles = "realm-owner")
    void putFullBodySetsTheServerAndKeepsThePasswordMasked() {
        UpdateSmtpRequest request = new UpdateSmtpRequest("smtp.acme.test", 587, "no-reply@acme.test", "Acme",
                "mailer", "s3cret", true, false);

        put(request)
                .then().statusCode(200)
                .body("host", equalTo("smtp.acme.test"))
                .body("passwordSet", equalTo(true))
                .body("$", org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasKey("password")));

        Map<String, String> smtp = realms.smtpServer(testRealm.name());
        assertEquals("true", smtp.get("auth"));
        assertEquals("mailer", smtp.get("user"));
        assertEquals("587", smtp.get("port"));
        // the admin API never sends the password back: Keycloak stores and returns a mask
        assertTrue(smtp.get("password") != null && !smtp.get("password").isEmpty() && !"s3cret".equals(smtp.get("password")));
    }

    @Test
    @TestSecurity(user = "owner", roles = "realm-owner")
    void putAgainWithoutPasswordKeepsTheStoredOne() {
        put(new UpdateSmtpRequest("smtp.acme.test", 587, "no-reply@acme.test", "Acme", "mailer", "s3cret", true, false))
                .then().statusCode(200);
        String maskedPassword = realms.smtpServer(testRealm.name()).get("password");

        put(new UpdateSmtpRequest("smtp.acme.test", 587, "no-reply@acme.test", "Acme", "mailer", null, true, false))
                .then().statusCode(200)
                .body("passwordSet", equalTo(true));

        // Note: this only proves the HTTP round-trip and status codes, not that the merge logic really keeps the
        // stored password. Keycloak returns the same "**********" mask whether the password was genuinely kept or
        // whether that literal mask string was itself stored as the password, so this assertion cannot tell the
        // two cases apart. SmtpMapperTest covers the actual forwarding logic (the mask is never sent back as a
        // real password).
        assertEquals(maskedPassword, realms.smtpServer(testRealm.name()).get("password"));
    }

    @Test
    @TestSecurity(user = "owner", roles = "realm-owner")
    void putWithoutUsernameDisablesAuthentication() {
        put(new UpdateSmtpRequest("smtp.acme.test", 587, "no-reply@acme.test", null, "mailer", "s3cret", false, false))
                .then().statusCode(200);

        put(new UpdateSmtpRequest("smtp.acme.test", 587, "no-reply@acme.test", null, null, null, false, false))
                .then().statusCode(200)
                .body("username", nullValue())
                .body("passwordSet", equalTo(false));

        Map<String, String> smtp = realms.smtpServer(testRealm.name());
        assertEquals("false", smtp.get("auth"));
        assertFalse(smtp.containsKey("user"));
        assertFalse(smtp.containsKey("password"));
    }

    // console-only keys (not owned by Orgkeep) survive a PUT: the merge keeps them
    @Test
    @TestSecurity(user = "owner", roles = "realm-owner")
    void putKeepsUnknownKeysSetInTheConsole() {
        put(new UpdateSmtpRequest("smtp.acme.test", 587, "no-reply@acme.test", null, null, null, false, false))
                .then().statusCode(200);
        // set in the Keycloak console, outside of Orgkeep: added to the stored map, not sent alone (Keycloak
        // rejects a smtpServer map without a host)
        Map<String, String> withConsoleKeys = new HashMap<>(realms.smtpServer(testRealm.name()));
        withConsoleKeys.put("replyTo", "support@acme.test");
        withConsoleKeys.put("envelopeFrom", "bounce@acme.test");
        withConsoleKeys.put("allowutf8", "true");
        realms.setSmtpServer(testRealm.name(), withConsoleKeys);

        put(new UpdateSmtpRequest("smtp.acme.test", 587, "no-reply@acme.test", null, null, null, false, false))
                .then().statusCode(200);

        Map<String, String> smtp = realms.smtpServer(testRealm.name());
        assertEquals("support@acme.test", smtp.get("replyTo"));
        assertEquals("bounce@acme.test", smtp.get("envelopeFrom"));
        assertEquals("true", smtp.get("allowutf8"));
    }

    @Test
    @TestSecurity(user = "owner", roles = "realm-owner")
    void putWithBlankFromDisplayNameRemovesTheKey() {
        put(new UpdateSmtpRequest("smtp.acme.test", 587, "no-reply@acme.test", "Acme", null, null, false, false))
                .then().statusCode(200);

        put(new UpdateSmtpRequest("smtp.acme.test", 587, "no-reply@acme.test", "  ", null, null, false, false))
                .then().statusCode(200)
                .body("fromDisplayName", nullValue());

        assertFalse(realms.smtpServer(testRealm.name()).containsKey("fromDisplayName"));
    }

    @Test
    @TestSecurity(user = "owner", roles = "realm-owner")
    void putWithStarttlsAndSslBothTrueIsAccepted() {
        put(new UpdateSmtpRequest("smtp.acme.test", 587, "no-reply@acme.test", null, null, null, true, true))
                .then().statusCode(200)
                .body("starttls", equalTo(true))
                .body("ssl", equalTo(true));
    }

    @ParameterizedTest
    @ValueSource(strings = {"missingHost", "blankHost", "port0", "port65536", "missingPort", "missingFrom", "invalidFrom"})
    @TestSecurity(user = "owner", roles = "realm-owner")
    void invalidBodyReturns400(String scenario) {
        Map<String, Object> body = new HashMap<>();
        body.put("host", "smtp.acme.test");
        body.put("port", 587);
        body.put("from", "no-reply@acme.test");
        switch (scenario) {
            case "missingHost" -> body.remove("host");
            case "blankHost" -> body.put("host", "");
            case "port0" -> body.put("port", 0);
            case "port65536" -> body.put("port", 65536);
            case "missingPort" -> body.remove("port");
            case "missingFrom" -> body.remove("from");
            case "invalidFrom" -> body.put("from", "not-an-email");
            default -> throw new IllegalArgumentException(scenario);
        }

        given().contentType(ContentType.JSON).body(body)
                .when().put(BASE)
                .then().statusCode(400);
    }

    @Test
    @TestSecurity(user = "owner", roles = "realm-owner")
    void deleteEmptiesTheServerAndIsIdempotent() {
        put(new UpdateSmtpRequest("smtp.acme.test", 587, "no-reply@acme.test", null, null, null, false, false))
                .then().statusCode(200);

        given().when().delete(BASE).then().statusCode(204);

        given().when().get(BASE)
                .then().statusCode(200)
                .body("host", nullValue());
        assertFalse(realms.hasSmtpServer(testRealm.name()));
        assertTrue(realms.smtpServer(testRealm.name()).isEmpty());

        // idempotent
        given().when().delete(BASE).then().statusCode(204);
    }

    @Test
    @TestSecurity(user = "alice", roles = "org-acme:org-admin")
    void orgAdminGets403OnPut() {
        put(new UpdateSmtpRequest("smtp.acme.test", 587, "no-reply@acme.test", null, null, null, false, false))
                .then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "alice", roles = "org-acme:org-admin")
    void orgAdminGets403OnDelete() {
        given().when().delete(BASE).then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "alice", roles = "org-acme:org-user")
    void orgUserGets403OnPut() {
        put(new UpdateSmtpRequest("smtp.acme.test", 587, "no-reply@acme.test", null, null, null, false, false))
                .then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "alice", roles = "org-acme:org-user")
    void orgUserGets403OnDelete() {
        given().when().delete(BASE).then().statusCode(403);
    }

    private io.restassured.response.Response put(UpdateSmtpRequest request) {
        return given().contentType(ContentType.JSON).body(request).when().put(BASE);
    }
}
