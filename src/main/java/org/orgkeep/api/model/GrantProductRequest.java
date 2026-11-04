package org.orgkeep.api.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.List;
import java.util.Map;

/**
 * What a team gives of a product of its org.
 *
 * @param features      optional, features of the product given (by name, without the product prefix), among those the
 *                      org bought. null: every feature the org bought, those it buys later included. Empty: the product
 *                      without any feature, refused for a metered product (without access role, it would give nothing)
 * @param quota         optional quota of the team: a budget its members share, on top of the pool of the org. Simple
 *                      product only: refused for a metered product
 * @param cycle         cycle of the quota of the team, only with a quota. Default: the cycle of the org for this product
 * @param featureQuotas metered product only, optional: quotas of the team by metered feature, only for features the
 *                      team gives. A feature without one only uses the pool of the org
 */
public record GrantProductRequest(
    List<@NotBlank String> features,
    @Positive
    Long quota,
    QuotaCycle cycle,
    Map<@NotBlank String, @NotNull @Valid TeamFeatureQuota> featureQuotas){

    public GrantProductRequest {
        // null keeps its meaning ("every feature bought"): not turned into an empty list
        features = features == null ? null : List.copyOf(features);
        featureQuotas = featureQuotas == null ? Map.of() : Map.copyOf(featureQuotas);
    }

    /** Without quotas of team by feature: a simple product, or a metered product whose members only use the org pools. */
    public GrantProductRequest(List<String> features, Long quota, QuotaCycle cycle) {
        this(features, quota, cycle, Map.of());
    }

    /** Every feature the org bought, no quota of the team. */
    public static GrantProductRequest allFeatures() {
        return new GrantProductRequest(null, null, null);
    }
}
