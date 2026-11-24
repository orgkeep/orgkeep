package org.orgkeep.components.realm.smtp;

import org.orgkeep.api.RealmProvisioner;
import org.orgkeep.api.model.SmtpSettings;
import org.orgkeep.api.model.UpdateSmtpRequest;
import org.orgkeep.fixtures.TestRealm;
import org.orgkeep.repository.keycloak.KeycloakRealms;
import org.orgkeep.web.CurrentRealm;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mockito.Mockito;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * SMTP server, as the default test configuration sees it: orgkeep.smtp.host=mailpit (ON_PREM), so the realm's
 * server is managed by the configuration and the API is read-only (PUT / DELETE answer 409). The writable case
 * (orgkeep.smtp.host empty) is a separate class, SmtpResourceWritableTest: both need a different value of the same
 * property, which Quarkus can only apply by restarting the application under a different test profile.
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SmtpResourceTest {
    private static final String BASE = "/api/admin/smtp";

    @Inject RealmProvisioner realmProvisioner;
    @Inject KeycloakRealms realms;
    @InjectMock CurrentRealm currentRealm;

    private TestRealm testRealm;

    @BeforeAll
    void createRealm() {
        testRealm = TestRealm.create();
        realmProvisioner.configureSmtp(testRealm.name(),
                new SmtpSettings("smtp.acme.test", 587, "no-reply@acme.test", "Acme", "mailer", "s3cret", true, false));
    }

    @BeforeEach
    void stubCurrentRealm() {
        Mockito.when(currentRealm.name()).thenReturn(testRealm.name());
    }

    @AfterAll
    void deleteRealm() {
        testRealm.close();
    }

    @Test
    @TestSecurity(user = "owner", roles = "realm-owner")
    void getReflectsTheConfiguredServerAndIsManagedByConfiguration() {
        given().when().get(BASE)
                .then().statusCode(200)
                .body("host", equalTo("smtp.acme.test"))
                .body("port", equalTo(587))
                .body("from", equalTo("no-reply@acme.test"))
                .body("fromDisplayName", equalTo("Acme"))
                .body("username", equalTo("mailer"))
                .body("passwordSet", equalTo(true))
                .body("starttls", equalTo(true))
                .body("ssl", equalTo(false))
                .body("managedByConfiguration", equalTo(true));
    }

    @Test
    @TestSecurity(user = "owner", roles = "realm-owner")
    void putReturns409AndLeavesTheRealmUnchanged() {
        UpdateSmtpRequest request = new UpdateSmtpRequest("other.example.com", 25, "other@acme.test", null, null, null, false, false);

        given().contentType(ContentType.JSON).body(request)
                .when().put(BASE)
                .then().statusCode(409)
                .body("message", containsString("ORGKEEP_SMTP_HOST"));

        assertEquals("smtp.acme.test", realms.smtpServer(testRealm.name()).get("host"));
    }

    @Test
    @TestSecurity(user = "owner", roles = "realm-owner")
    void deleteReturns409AndLeavesTheRealmUnchanged() {
        given().when().delete(BASE)
                .then().statusCode(409)
                .body("message", containsString("ORGKEEP_SMTP_HOST"));

        assertEquals("smtp.acme.test", realms.smtpServer(testRealm.name()).get("host"));
    }

    @Test
    @TestSecurity(user = "alice", roles = "org-acme:org-admin")
    void orgAdminGets403OnGet() {
        given().when().get(BASE).then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "alice", roles = "org-acme:org-admin")
    void orgAdminGets403OnPut() {
        given().contentType(ContentType.JSON)
                .body(new UpdateSmtpRequest("smtp.acme.test", 25, "no-reply@acme.test", null, null, null, false, false))
                .when().put(BASE).then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "alice", roles = "org-acme:org-admin")
    void orgAdminGets403OnDelete() {
        given().when().delete(BASE).then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "alice", roles = "org-acme:org-user")
    void orgUserGets403OnGet() {
        given().when().get(BASE).then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "alice", roles = "org-acme:org-user")
    void orgUserGets403OnPut() {
        given().contentType(ContentType.JSON)
                .body(new UpdateSmtpRequest("smtp.acme.test", 25, "no-reply@acme.test", null, null, null, false, false))
                .when().put(BASE).then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "alice", roles = "org-acme:org-user")
    void orgUserGets403OnDelete() {
        given().when().delete(BASE).then().statusCode(403);
    }

    @Test
    void anonymousGets401OnGet() {
        given().when().get(BASE).then().statusCode(401);
    }

    @Test
    void anonymousGets401OnPut() {
        given().contentType(ContentType.JSON)
                .body(new UpdateSmtpRequest("smtp.acme.test", 25, "no-reply@acme.test", null, null, null, false, false))
                .when().put(BASE).then().statusCode(401);
    }

    @Test
    void anonymousGets401OnDelete() {
        given().when().delete(BASE).then().statusCode(401);
    }
}
