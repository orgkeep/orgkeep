package io.orgkeep.dashboard.organisation;

import java.util.List;
import java.util.Map;

/**
 * A product assigned to an organisation, with what it negotiated. The part of OrgProduct of orgkeep-core the page
 * shows. The display name of the product is not here: the front reads it from the catalogs (/api/products).
 *
 * @param quota       null if the group was changed outside of Orgkeep
 * @param cycle       DAILY, MONTHLY or YEARLY, null if the group was changed outside of Orgkeep
 * @param boughtRoles roles of the product the org bought, sorted
 * @param params      values of the parameters of the product, defaults applied
 */
public record OrgProduct(
        String catalog,
        String product,
        Long quota,
        String cycle,
        List<String> boughtRoles,
        Map<String, String> params) { }
