package io.orgkeep.dashboard.organisation;

import java.util.Set;

/**
 * The part of CreateOrgResponse of orgkeep-core the front needs: Jackson ignores the other properties
 * (client and group ids).
 *
 * @param ownerId null when no owner was given
 */
public record CreateOrgResponse(
        String id,
        String name,
        Set<String> emailDomains,
        String ownerId) { }
