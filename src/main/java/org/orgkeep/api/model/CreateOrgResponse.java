package org.orgkeep.api.model;

import java.util.Set;

/**
 * @param id         Keycloak organisation id
 * @param clientId   clientId of the org client, e.g. "org-acme"
 * @param clientUuid internal id of the org client
 * @param groupId    id of the root group /{org}
 * @param ownerId    user id of the owner, null when no owner was given
 */
public record CreateOrgResponse(
    String id,
    String name,
    Set<String> emailDomains,
    String clientId,
    String clientUuid,
    String groupId,
    String ownerId){ }
