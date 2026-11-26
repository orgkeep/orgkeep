package org.orgkeep.api.model;

import java.util.List;
import java.util.Set;

/**
 * Full view of an organisation and of the Orgkeep resources attached to it.
 *
 * @param id                 Keycloak organisation id
 * @param clientId           clientId of the org client, e.g. "org-acme"
 * @param clientUuid         internal id of the org client, null if the client is missing
 * @param groupId            id of the root group /{org}, null if the group is missing
 * @param owners             users holding the org-owner role directly, sorted by username. Empty if the org has no
 *                           owner yet, or no org client (created outside of Orgkeep)
 * @param maxMembers         maximum number of members, null = no limit (see SeatService for the current counts)
 * @param maxServiceAccounts maximum number of service accounts, null = no limit
 */
public record OrgDetails(
    String id,
    String name,
    Set<String> emailDomains,
    boolean enabled,
    String clientId,
    String clientUuid,
    String groupId,
    List<OrgMember> owners,
    Integer maxMembers,
    Integer maxServiceAccounts){ }
