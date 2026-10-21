package io.orgkeep.api.model;

import java.util.List;
import java.util.Map;

/**
 * A product assigned to an organisation, with what the org negotiated.
 *
 * @param groupId        id of the group /{org}/catalogs/{catalog}/{product}
 * @param quota          quota of the org for a simple product. Null for a metered product, or if the attribute is
 *                       missing or not a number (group changed outside of Orgkeep)
 * @param cycle          its cycle, null for a metered product or if the attribute is missing or unknown
 * @param boughtFeatures features of the product the org bought, sorted: the ceiling of what its teams can give
 * @param featureQuotas  metered product: the quota of the org for each metered feature it bought, sorted by feature.
 *                       Empty for a simple product
 * @param params         values of the parameters of the product, defaults applied
 */
public record OrgProduct(
    String catalog,
    String product,
    String groupId,
    Long quota,
    QuotaCycle cycle,
    List<String> boughtFeatures,
    Map<String, FeatureQuota> featureQuotas,
    Map<String, String> params){ }
