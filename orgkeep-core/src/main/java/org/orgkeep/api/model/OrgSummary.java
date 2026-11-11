package org.orgkeep.api.model;

import java.util.List;
import java.util.Set;

/**
 * Light view of an organisation, as returned by the list endpoint: what a list screen shows on each row.
 *
 * @param id          Keycloak organisation id
 * @param owners      users holding the org-owner role directly, sorted by username. Empty if the org has no owner
 *                    yet, or no org client (created outside of Orgkeep)
 * @param memberCount members of the Keycloak organisation, owners included
 * @param products    products assigned to the org, as "catalog/product" (groups under /{org}/catalogs), sorted
 */
public record OrgSummary(
    String id,
    String name,
    Set<String> emailDomains,
    boolean enabled,
    List<OrgMember> owners,
    long memberCount,
    List<String> products){ }
