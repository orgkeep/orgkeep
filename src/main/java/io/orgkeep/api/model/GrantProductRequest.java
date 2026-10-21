package io.orgkeep.api.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

import java.util.List;

/**
 * What a team gives of a product of its org.
 *
 * @param roles optional, roles of the product given (by name, without the product prefix), among those the org bought.
 *              null: every role the org bought, those it buys later included. Empty: the product without any role
 * @param quota optional quota of the team: a budget its members share, on top of the pool of the org
 * @param cycle cycle of the quota of the team, only with a quota. Default: the cycle of the org for this product
 */
public record GrantProductRequest(
    List<@NotBlank String> roles,
    @Positive
    Long quota,
    QuotaCycle cycle){

    public GrantProductRequest {
        // null keeps its meaning ("every role bought"): not turned into an empty list
        roles = roles == null ? null : List.copyOf(roles);
    }

    /** Every role the org bought, no quota of the team. */
    public static GrantProductRequest allRoles() {
        return new GrantProductRequest(null, null, null);
    }
}
