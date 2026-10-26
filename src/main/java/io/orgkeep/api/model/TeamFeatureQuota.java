package io.orgkeep.api.model;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * The quota of a team for a metered feature it gives: a budget its members share, on top of the pool of the org for
 * this feature.
 *
 * @param cycle optional. Default: the cycle of the org for this feature, copied when written
 */
public record TeamFeatureQuota(
    @NotNull
    @Positive
    Long quota,
    QuotaCycle cycle){ }
