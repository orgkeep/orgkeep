package io.orgkeep.api.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

import java.util.List;

/**
 * What a team gives of a product of its org.
 *
 * @param features optional, features of the product given (by name, without the product prefix), among those the org
 *                 bought. null: every feature the org bought, those it buys later included. Empty: the product without
 *                 any feature, refused for a metered product (without access role, it would give nothing)
 * @param quota    optional quota of the team: a budget its members share, on top of the pool of the org. Simple
 *                 product only: refused for a metered product
 * @param cycle    cycle of the quota of the team, only with a quota. Default: the cycle of the org for this product
 */
public record GrantProductRequest(
    List<@NotBlank String> features,
    @Positive
    Long quota,
    QuotaCycle cycle){

    public GrantProductRequest {
        // null keeps its meaning ("every feature bought"): not turned into an empty list
        features = features == null ? null : List.copyOf(features);
    }

    /** Every feature the org bought, no quota of the team. */
    public static GrantProductRequest allFeatures() {
        return new GrantProductRequest(null, null, null);
    }
}
