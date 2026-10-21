package io.orgkeep.api.model;

/**
 * A feature an organisation bought, as its org admins see it to configure their teams.
 *
 * @param metered true if the org bought it with a quota
 * @param quota   quota of the org for a metered feature (its pool), null otherwise
 * @param cycle   its cycle, null otherwise
 */
public record AvailableFeature(
    String name,
    String displayName,
    boolean metered,
    Long quota,
    QuotaCycle cycle){ }
