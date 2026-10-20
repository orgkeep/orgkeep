package io.orgkeep.dashboard.organisation;

import java.util.List;
import java.util.Set;

/**
 * The header of the detail page of an organisation. The part of OrgDetails of orgkeep-core the page shows:
 * the other properties of the core answer are ignored when it is read.
 *
 * @param clientId the org client, e.g. "org-acme"
 * @param owners   sorted by username, empty if the org has no owner yet
 */
public record OrgDetails(
        String id,
        String name,
        Set<String> emailDomains,
        boolean enabled,
        String clientId,
        List<OrgSummary.OrgOwner> owners) { }
