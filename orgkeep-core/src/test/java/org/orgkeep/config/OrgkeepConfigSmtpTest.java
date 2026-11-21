package org.orgkeep.config;

import io.smallrye.config.SmallRyeConfigBuilder;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Binds OrgkeepConfig the way a prod start does, without Quarkus: only the variables given here exist, none of the
 * %dev,test values of application.properties. A plain unit test is enough, the bug was in the config mapping.
 */
class OrgkeepConfigSmtpTest {

    private static OrgkeepConfig bind(String... properties) {
        SmallRyeConfigBuilder builder = new SmallRyeConfigBuilder().withMapping(OrgkeepConfig.class)
                // Quarkus registers the Duration converter, plain SmallRye does not
                .withConverter(Duration.class, 100, value -> Duration.parse("PT" + value));
        for (int i = 0; i < properties.length; i += 2) {
            builder.withDefaultValue(properties[i], properties[i + 1]);
        }
        return builder.build().getConfigMapping(OrgkeepConfig.class);
    }

    @Test
    void noSmtpVariableBindsWithoutHost() {
        OrgkeepConfig config = bind();

        assertTrue(config.smtp().host().isEmpty());
        assertTrue(config.smtp().from().isEmpty());
    }

    @Test
    void hostAndFromAreBoundWithTheDefaults() {
        OrgkeepConfig config = bind("orgkeep.smtp.host", "smtp.example.com", "orgkeep.smtp.from", "no-reply@example.com");

        assertEquals("smtp.example.com", config.smtp().host().orElseThrow());
        assertEquals("no-reply@example.com", config.smtp().from().orElseThrow());
        assertEquals(25, config.smtp().port());
    }
}
