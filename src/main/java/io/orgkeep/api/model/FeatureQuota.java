package io.orgkeep.api.model;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * What an organisation negotiated for a metered feature it bought: the pool of the org for this feature. Both required,
 * like the quota of a simple product: an org never gets unlimited access by mistake.
 */
public record FeatureQuota(
    @NotNull
    @Positive
    Long quota,
    @NotNull
    QuotaCycle cycle){ }
