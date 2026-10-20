package io.orgkeep.api.model;

import java.util.List;
import java.util.Map;

/**
 * A product assigned to an organisation, with what the org negotiated.
 *
 * @param groupId     id of the group /{org}/catalogs/{catalog}/{product}
 * @param quota       null if the attribute is missing or not a number (group changed outside of Orgkeep)
 * @param cycle       null if the attribute is missing or unknown
 * @param boughtRoles roles of the product the org bought, sorted: the ceiling of what its teams can give
 * @param params      values of the parameters of the product, defaults applied
 */
public record OrgProduct(
    String catalog,
    String product,
    String groupId,
    Long quota,
    QuotaCycle cycle,
    List<String> boughtRoles,
    Map<String, String> params){ }
