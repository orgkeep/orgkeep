package io.orgkeep.api.model;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;
import java.util.Optional;

/**
 * Period over which a quota applies. Orgkeep does not count the usage: the API of the realm owner
 * reads the quota and its cycle from the token and resets its own counters accordingly.
 */
@AllArgsConstructor
public enum QuotaCycle {
    DAILY("daily"),
    MONTHLY("monthly"),
    YEARLY("yearly");

    /** Value stored in the Keycloak attribute, hence in the token. */
    @Getter
    private final String value;

    public static Optional<QuotaCycle> fromValue(String value) {
        return Arrays.stream(values())
                .filter(cycle -> cycle.value.equals(value))
                .findFirst();
    }
}
