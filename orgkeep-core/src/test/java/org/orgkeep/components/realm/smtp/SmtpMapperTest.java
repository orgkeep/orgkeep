package org.orgkeep.components.realm.smtp;

import org.orgkeep.api.model.SmtpServer;
import org.orgkeep.api.model.SmtpSettings;
import org.orgkeep.api.model.UpdateSmtpRequest;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SmtpMapperTest {
    private final SmtpMapper mapper = new SmtpMapperImpl();

    @Test
    void mergeKeepsUnknownKeysAndReplacesOwnedOnes() {
        Map<String, String> current = new HashMap<>(Map.of(
                "host", "old.example.com", "port", "25", "replyTo", "support@acme.test",
                "envelopeFrom", "bounce@acme.test", "allowutf8", "true"));

        Map<String, String> merged = mapper.merge(current,
                new SmtpSettings("smtp.acme.test", 587, "no-reply@acme.test", "Acme", "mailer", "s3cret", true, false));

        assertEquals("smtp.acme.test", merged.get("host"));
        assertEquals("587", merged.get("port"));
        assertEquals("no-reply@acme.test", merged.get("from"));
        assertEquals("Acme", merged.get("fromDisplayName"));
        assertEquals("true", merged.get("auth"));
        assertEquals("mailer", merged.get("user"));
        assertEquals("s3cret", merged.get("password"));
        assertEquals("true", merged.get("starttls"));
        assertEquals("false", merged.get("ssl"));
        // unknown keys, untouched
        assertEquals("support@acme.test", merged.get("replyTo"));
        assertEquals("bounce@acme.test", merged.get("envelopeFrom"));
        assertEquals("true", merged.get("allowutf8"));
    }

    @Test
    void mergeWithNullCurrentStartsFromAnEmptyMap() {
        Map<String, String> merged = mapper.merge(null,
                new SmtpSettings("smtp.acme.test", 25, "no-reply@acme.test", null, null, null, false, false));

        assertEquals("smtp.acme.test", merged.get("host"));
        assertEquals("false", merged.get("auth"));
    }

    @Test
    void mergeWithoutUsernameRemovesAuthenticationAndNeverPutsANullValue() {
        Map<String, String> current = new HashMap<>(Map.of("auth", "true", "user", "mailer", "password", "s3cret"));

        Map<String, String> merged = mapper.merge(current,
                new SmtpSettings("smtp.acme.test", 25, "no-reply@acme.test", null, null, null, false, false));

        assertEquals("false", merged.get("auth"));
        assertFalse(merged.containsKey("user"));
        assertFalse(merged.containsKey("password"));
        assertFalse(merged.containsValue(null));
    }

    @Test
    void mergeWithoutFromDisplayNameRemovesIt() {
        Map<String, String> current = new HashMap<>(Map.of("fromDisplayName", "Old Acme"));

        Map<String, String> merged = mapper.merge(current,
                new SmtpSettings("smtp.acme.test", 25, "no-reply@acme.test", null, null, null, false, false));

        assertFalse(merged.containsKey("fromDisplayName"));
    }

    @Test
    void toSmtpServerWithNullMapGivesAllNullServer() {
        SmtpServer server = mapper.toSmtpServer(null, false);

        assertNull(server.host());
        assertNull(server.port());
        assertNull(server.from());
        assertNull(server.fromDisplayName());
        assertNull(server.username());
        assertFalse(server.passwordSet());
        assertFalse(server.starttls());
        assertFalse(server.ssl());
        assertFalse(server.managedByConfiguration());
    }

    @Test
    void toSmtpServerWithEmptyMapGivesAllNullServer() {
        SmtpServer server = mapper.toSmtpServer(Map.of(), true);

        assertNull(server.host());
        assertTrue(server.managedByConfiguration());
    }

    @Test
    void toSmtpServerWithNonNumericPortGivesNullPort() {
        SmtpServer server = mapper.toSmtpServer(Map.of("host", "smtp.acme.test", "port", "not-a-number"), false);

        assertEquals("smtp.acme.test", server.host());
        assertNull(server.port());
    }

    @Test
    void toSmtpServerWithAuthFalseIgnoresALeftoverUser() {
        // console-only drift: auth was turned off but the user key was left in the map
        SmtpServer server = mapper.toSmtpServer(Map.of("host", "smtp.acme.test", "auth", "false", "user", "mailer"), false);

        assertNull(server.username());
        assertFalse(server.passwordSet());
    }

    @Test
    void toSmtpServerReadsUsernameAndPasswordSetWhenAuthenticationIsOn() {
        SmtpServer server = mapper.toSmtpServer(
                Map.of("host", "smtp.acme.test", "port", "587", "auth", "true", "user", "mailer", "password", "**********"),
                false);

        assertEquals(587, server.port());
        assertEquals("mailer", server.username());
        assertTrue(server.passwordSet());
    }

    @Test
    void toSettingsNormalizesBlankFieldsToNull() {
        UpdateSmtpRequest request = new UpdateSmtpRequest("smtp.acme.test", 25, "no-reply@acme.test", "  ", "  ", null, false, false);

        SmtpSettings settings = mapper.toSettings(request, "oldPassword");

        assertNull(settings.fromDisplayName());
        assertNull(settings.username());
        // no username: no password is kept either
        assertNull(settings.password());
    }

    @Test
    void toSettingsKeepsStoredPasswordWhenAbsentFromTheRequest() {
        UpdateSmtpRequest request = new UpdateSmtpRequest("smtp.acme.test", 25, "no-reply@acme.test", null, "mailer", null, false, false);

        SmtpSettings settings = mapper.toSettings(request, "keptMask");

        assertEquals("mailer", settings.username());
        assertEquals("keptMask", settings.password());
    }

    @Test
    void toSettingsReplacesTheStoredPasswordWhenGiven() {
        UpdateSmtpRequest request = new UpdateSmtpRequest("smtp.acme.test", 25, "no-reply@acme.test", null, "mailer", "newPassword", false, false);

        SmtpSettings settings = mapper.toSettings(request, "keptMask");

        assertEquals("newPassword", settings.password());
    }
}
