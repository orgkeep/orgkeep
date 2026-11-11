package org.orgkeep.api.model;

import java.util.List;
import java.util.Map;

/**
 * A product given by a team.
 *
 * @param features          features asked for by the org admin, sorted. null: every feature the org bought
 * @param effectiveFeatures features the members get: those asked for that the org bought, sorted
 * @param quota             quota of the team, null if the team has none (its members only use the pool of the org)
 * @param cycle             cycle of the quota of the team, null without quota
 * @param featureQuotas     metered product: the quotas of the team by metered feature, sorted by feature. Empty for a
 *                          simple product, or when the members only use the pools of the org
 */
public record TeamProduct(
    String catalog,
    String product,
    List<String> features,
    List<String> effectiveFeatures,
    Long quota,
    QuotaCycle cycle,
    Map<String, FeatureQuota> featureQuotas){ }
