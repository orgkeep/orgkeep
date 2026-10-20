package io.orgkeep.api.model;

/**
 * @param id   Keycloak user id
 * @param role highest role of the member in the organisation, as Orgkeep grants it: org-owner if the role is
 *             granted directly to the user, org-admin if the user is in the group /{org}/user-groups/admin,
 *             org-user otherwise
 */
public record OrgMember(
    String id,
    String username,
    String email,
    String firstName,
    String lastName,
    Boolean enabled,
    OrgRole role){ }
