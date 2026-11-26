package org.orgkeep.components.organisation.suspension;

import org.orgkeep.api.model.Suspensions;
import org.junit.jupiter.api.Test;
import org.keycloak.representations.idm.UserRepresentation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit tests of the helper that reads the four attributes off a UserRepresentation: no Keycloak call, so a
 * plain test (unlike the other classes of this package, which need a real realm). Covers the leniency added for a
 * value nobody but a console edit could produce (suspended-at unparseable, suspension-org-choice unrecognized),
 * consistent with the guard against a crash on the members list.
 */
class SuspensionAttributesTest {

    @Test
    void absentWhenThereIsNoReason() {
        assertTrue(SuspensionAttributes.of(new UserRepresentation()).isEmpty());
    }

    @Test
    void readsEveryAttribute() {
        UserRepresentation user = userWith(Map.of(
                Suspensions.REASON, "Breached contract",
                Suspensions.SUSPENDED_AT, "1700000000",
                Suspensions.SUSPENDED_BY, "boss-id",
                Suspensions.ORG_CHOICE, "disabled"));

        SuspensionAttributes.SuspensionRecord record = SuspensionAttributes.of(user).orElseThrow();

        assertEquals("Breached contract", record.reason());
        assertEquals(1700000000L, record.suspendedAt().getEpochSecond());
        assertEquals("boss-id", record.suspendedBy());
        assertEquals(SuspensionAttributes.OrgChoice.DISABLED, record.orgChoice());
    }

    // a console edit, or anything other than this code: the safer default (stays off on lift) rather than a crash
    @Test
    void unrecognizedOrgChoiceDefaultsToDisabled() {
        UserRepresentation user = userWith(Map.of(Suspensions.REASON, "Reason", Suspensions.ORG_CHOICE, "whatever"));

        assertEquals(SuspensionAttributes.OrgChoice.DISABLED, SuspensionAttributes.of(user).orElseThrow().orgChoice());
    }

    @Test
    void missingOrgChoiceDefaultsToEnabled() {
        UserRepresentation user = userWith(Map.of(Suspensions.REASON, "Reason"));

        assertEquals(SuspensionAttributes.OrgChoice.ENABLED, SuspensionAttributes.of(user).orElseThrow().orgChoice());
    }

    // a malformed suspended-at (a console edit, or any value this code never wrote) must not crash a read of the
    // members list: treated as absent rather than throwing NumberFormatException
    @Test
    void malformedSuspendedAtIsTreatedAsAbsentInsteadOfCrashing() {
        UserRepresentation user = userWith(Map.of(Suspensions.REASON, "Reason", Suspensions.SUSPENDED_AT, "not-a-number"));

        SuspensionAttributes.SuspensionRecord record = SuspensionAttributes.of(user).orElseThrow();

        assertNull(record.suspendedAt());
        assertEquals("Reason", record.reason());
    }

    @Test
    void toSuspensionIsNullWhenNotSuspended() {
        assertNull(SuspensionAttributes.toSuspension(new UserRepresentation()));
    }

    @Test
    void toSuspensionNeverExposesTheOrgChoice() {
        UserRepresentation user = userWith(Map.of(Suspensions.REASON, "Reason", Suspensions.ORG_CHOICE, "disabled"));

        assertFalse(SuspensionAttributes.toSuspension(user).toString().contains("disabled"));
    }

    private static UserRepresentation userWith(Map<String, String> attributes) {
        UserRepresentation user = new UserRepresentation();
        Map<String, List<String>> values = new HashMap<>();
        attributes.forEach((name, value) -> values.put(name, List.of(value)));
        user.setAttributes(values);
        return user;
    }
}
