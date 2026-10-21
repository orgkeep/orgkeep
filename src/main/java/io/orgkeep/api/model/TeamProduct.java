package io.orgkeep.api.model;

import java.util.List;

/**
 * A product given by a team.
 *
 * @param roles          roles asked for by the org admin, sorted. null: every role the org bought
 * @param effectiveRoles roles the members get: those asked for that the org bought, sorted
 * @param quota          quota of the team, null if the team has none (its members only use the pool of the org)
 * @param cycle          cycle of the quota of the team, null without quota
 */
public record TeamProduct(
    String catalog,
    String product,
    List<String> roles,
    List<String> effectiveRoles,
    Long quota,
    QuotaCycle cycle){ }
