package org.orgkeep.api.model;

import java.util.List;

/**
 * Naming of the four user attributes a suspension by the realm owner is stored in (see docs/design/members.md,
 * "Suspension par le realm owner"), declared in the realm's user profile at realm creation (admin-only, never in a
 * token: white label). The presence of {@link #REASON} says the account is suspended.
 */
public final class Suspensions {
    /** Free text, 1 to 500 characters: presence of this attribute means the account is suspended. */
    public static final String REASON = "suspension-reason";
    /** Epoch seconds, decimal string: set at the first suspension, kept when suspending again. */
    public static final String SUSPENDED_AT = "suspended-at";
    /** User id (sub) of the realm owner who suspended: set at the first suspension, kept when suspending again. */
    public static final String SUSPENDED_BY = "suspended-by";
    /** "enabled" | "disabled": the organisation's choice, restored when the suspension is lifted. */
    public static final String ORG_CHOICE = "suspension-org-choice";

    public static final int MAX_REASON_LENGTH = 500;

    /** Every suspension attribute: what a lift removes. */
    public static final List<String> ATTRIBUTE_NAMES = List.of(REASON, SUSPENDED_AT, SUSPENDED_BY, ORG_CHOICE);

    private Suspensions() {
    }
}
