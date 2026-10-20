package io.orgkeep.dashboard.organisation;

import java.util.Set;

/**
 * Copy of CreateOrgRequest of orgkeep-core. No Bean Validation here: the core validates the request
 * and its 400 is relayed to the front (see CoreApiException), so the rules live in one place.
 *
 * @param ownerEmail optional: an existing user is reused, otherwise the core creates one
 */
public record CreateOrgRequest(
        String name,
        Set<String> emailDomains,
        String ownerEmail,
        String ownerFirstName,
        String ownerLastName) { }
