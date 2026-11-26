package org.orgkeep.components.organisation.suspension;

import org.keycloak.representations.idm.UserRepresentation;
import org.orgkeep.api.model.Suspension;
import org.orgkeep.api.model.Suspensions;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads and prepares the four suspension attributes of a Keycloak user (see {@link Suspensions}): the record
 * belongs to the account, not to the organisation, so it survives removal from the org, re-adding, and the
 * deletion of the org. Single valued; never emitted in a token (not mapped by any client scope). Used by the
 * members and service accounts services, the org enable/disable loops, and {@link AccountSuspensionServiceImpl}.
 */
public final class SuspensionAttributes {

    /** What the organisation had decided about the account when it was suspended, restored when it is lifted. */
    public enum OrgChoice {
        ENABLED("enabled"),
        DISABLED("disabled");

        private final String value;

        OrgChoice(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }

        public static OrgChoice fromValue(String value) {
            for (OrgChoice choice : values()) {
                if (choice.value.equals(value)) {
                    return choice;
                }
            }
            // an org-choice set by anything other than this code (a console edit): treated as the safer default
            // (stays off on lift, rather than being re-enabled unexpectedly)
            return DISABLED;
        }
    }

    /** The suspension record read from the user. orgChoice is never exposed outside this package (see {@link Suspension}). */
    public record SuspensionRecord(String reason, Instant suspendedAt, String suspendedBy, OrgChoice orgChoice) { }

    private SuspensionAttributes() {
    }

    /** Empty when the user carries no suspension-reason: the account is not suspended. */
    public static Optional<SuspensionRecord> of(UserRepresentation user) {
        Map<String, List<String>> attributes = user.getAttributes();
        String reason = singleValue(attributes, Suspensions.REASON);
        if (reason == null) {
            return Optional.empty();
        }
        Instant suspendedAt = parseEpochSeconds(singleValue(attributes, Suspensions.SUSPENDED_AT));
        String suspendedBy = singleValue(attributes, Suspensions.SUSPENDED_BY);
        OrgChoice orgChoice = Optional.ofNullable(singleValue(attributes, Suspensions.ORG_CHOICE))
                .map(OrgChoice::fromValue)
                .orElse(OrgChoice.ENABLED);
        return Optional.of(new SuspensionRecord(reason, suspendedAt, suspendedBy, orgChoice));
    }

    /** The public {@link Suspension} (no org choice), null when the user is not suspended. */
    public static Suspension toSuspension(UserRepresentation user) {
        return of(user).map(found -> new Suspension(found.reason(), found.suspendedAt(), found.suspendedBy())).orElse(null);
    }

    /**
     * All four attributes, for a first suspension: suspended-at and suspended-by are set now, kept unchanged by a
     * later suspension of the same account (see {@link #reasonOnly}).
     */
    public static Map<String, String> newSuspension(String reason, String suspenderId, OrgChoice orgChoice) {
        Map<String, String> values = new HashMap<>();
        values.put(Suspensions.REASON, reason);
        values.put(Suspensions.SUSPENDED_AT, String.valueOf(Instant.now().getEpochSecond()));
        values.put(Suspensions.SUSPENDED_BY, suspenderId);
        values.put(Suspensions.ORG_CHOICE, orgChoice.value());
        return values;
    }

    /** Suspending an already suspended account again: only the reason is replaced. */
    public static Map<String, String> reasonOnly(String reason) {
        return Map.of(Suspensions.REASON, reason);
    }

    /** Null when absent or unparseable (e.g. a value set by a console edit): lenient, like {@link OrgChoice#fromValue}. */
    private static Instant parseEpochSeconds(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Instant.ofEpochSecond(Long.parseLong(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String singleValue(Map<String, List<String>> attributes, String name) {
        if (attributes == null) {
            return null;
        }
        List<String> values = attributes.get(name);
        return values == null || values.isEmpty() ? null : values.getFirst();
    }
}
