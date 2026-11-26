package org.orgkeep.components.organisation.seats;

import lombok.extern.slf4j.Slf4j;
import org.keycloak.representations.idm.OrganizationRepresentation;

import java.util.HashMap;
import java.util.List;
import java.util.Optional;

/**
 * The seat limits of an organisation, listed in the attributes {@value #ATTRIBUTE_MAX_MEMBERS} and
 * {@value #ATTRIBUTE_MAX_SERVICE_ACCOUNTS} of the Keycloak organisation (one decimal value each). No attribute
 * means no limit: there is no default. Never output in a token (neutral names, white label).
 * <p>
 * These methods only change the representation: the caller saves it (KeycloakOrgs.update), under the lock of the
 * organisation (an exclusive ResourceLocks lock on it).
 */
@Slf4j
public final class SeatLimits {
    public static final String ATTRIBUTE_MAX_MEMBERS = "max-members";
    public static final String ATTRIBUTE_MAX_SERVICE_ACCOUNTS = "max-service-accounts";

    private SeatLimits() {
    }

    public static Optional<Integer> maxMembers(OrganizationRepresentation org) {
        return read(org, ATTRIBUTE_MAX_MEMBERS);
    }

    public static Optional<Integer> maxServiceAccounts(OrganizationRepresentation org) {
        return read(org, ATTRIBUTE_MAX_SERVICE_ACCOUNTS);
    }

    /** Replaces both limits on the representation; a null value removes the corresponding attribute. */
    public static void set(OrganizationRepresentation org, Integer maxMembers, Integer maxServiceAccounts) {
        write(org, ATTRIBUTE_MAX_MEMBERS, maxMembers);
        write(org, ATTRIBUTE_MAX_SERVICE_ACCOUNTS, maxServiceAccounts);
    }

    /** A value that is not a non-negative integer (only possible through the console) is read as no limit. */
    private static Optional<Integer> read(OrganizationRepresentation org, String attribute) {
        List<String> values = org.getAttributes() == null
                ? List.of() : org.getAttributes().getOrDefault(attribute, List.of());
        if (values.isEmpty() || values.getFirst() == null) {
            return Optional.empty();
        }
        try {
            int value = Integer.parseInt(values.getFirst());
            if (value < 0) {
                log.warn("Organisation '{}' has a negative {}: treated as no limit", org.getName(), attribute);
                return Optional.empty();
            }
            return Optional.of(value);
        } catch (NumberFormatException e) {
            log.warn("Organisation '{}' has a malformed {}: treated as no limit", org.getName(), attribute);
            return Optional.empty();
        }
    }

    private static void write(OrganizationRepresentation org, String attribute, Integer value) {
        if (value == null) {
            if (org.getAttributes() != null) {
                org.getAttributes().remove(attribute);
            }
            return;
        }
        if (org.getAttributes() == null) {
            org.setAttributes(new HashMap<>());
        }
        org.getAttributes().put(attribute, List.of(String.valueOf(value)));
    }
}
