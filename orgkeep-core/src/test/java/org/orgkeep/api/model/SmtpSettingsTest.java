package org.orgkeep.api.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SmtpSettingsTest {

    @Test
    void toStringMasksThePassword() {
        SmtpSettings settings = new SmtpSettings("smtp.example.com", 587, "no-reply@example.com", null, "mailer",
                "s3cret", true, false);

        String text = settings.toString();

        assertFalse(text.contains("s3cret"));
        assertTrue(text.contains("password=***"));
        assertTrue(text.contains("host=smtp.example.com"));
    }

    @Test
    void toStringShowsAnAbsentPasswordAsNull() {
        SmtpSettings settings = new SmtpSettings("smtp.example.com", 25, "no-reply@example.com", null, null, null,
                false, false);

        assertTrue(settings.toString().contains("password=null"));
    }
}
