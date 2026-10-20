package io.orgkeep.dashboard.organisation;

import java.util.List;
import java.util.Set;

/**
 * An organisation in the list. Copy of OrgSummary of orgkeep-core: the dashboard does not depend on the core code.
 *
 * @param id          Keycloak organisation id
 * @param owners      sorted by username, empty if the org has no owner yet
 * @param memberCount owners included
 * @param products    the assigned products as "catalog/product", sorted
 */
public record OrgSummary(
        String id,
        String name,
        Set<String> emailDomains,
        boolean enabled,
        List<OrgOwner> owners,
        long memberCount,
        List<String> products) {

    /** The part of OrgMember of orgkeep-core the list shows. */
    public record OrgOwner(String id, String username, String email, String firstName, String lastName) { }
}
