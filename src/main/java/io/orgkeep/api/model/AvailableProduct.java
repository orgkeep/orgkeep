package io.orgkeep.api.model;

import java.util.List;

/**
 * A product assigned to an organisation, as its org admins see it to configure their teams: the org admins cannot read
 * the catalogs of the realm owner, the display names come with it.
 *
 * @param quota          quota of the org (its pool), the default cycle of a quota of team being {@code cycle}
 * @param boughtFeatures features of the product the org bought, sorted by name: the most a team can give
 */
public record AvailableProduct(
    String catalog,
    String product,
    String displayName,
    Long quota,
    QuotaCycle cycle,
    List<Feature> boughtFeatures){ }
