package io.orgkeep.api.model;

import java.util.List;

/**
 * A product assigned to an organisation, as its org admins see it to configure their teams: the org admins cannot read
 * the catalogs of the realm owner, the display names come with it.
 *
 * @param metered        true for a product with metered features: no quota of product, a quota per metered feature
 *                       (in {@code boughtFeatures}), and no quota of team
 * @param quota          simple product: quota of the org (its pool), the default cycle of a quota of team being
 *                       {@code cycle}. Null for a metered product
 * @param boughtFeatures features of the product the org bought, sorted by name: the most a team can give
 */
public record AvailableProduct(
    String catalog,
    String product,
    String displayName,
    boolean metered,
    Long quota,
    QuotaCycle cycle,
    List<AvailableFeature> boughtFeatures){ }
