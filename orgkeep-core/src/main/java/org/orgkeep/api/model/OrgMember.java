package org.orgkeep.api.model;

/**
 * @param id   Keycloak user id
 * @param role highest role of the member in the organisation, as Orgkeep grants it: org-owner if the role is
 *             granted directly to the user, org-admin if the user is in the group /{org}/user-groups/admin,
 *             org-user otherwise
 * @param invitationPending true while the member (not an owner) still has the UPDATE_PASSWORD required action,
 *                          i.e. has not set a password through the invitation link or "forgot password"; the
 *                          invitation can then be sent again. Only in the members of an organisation: null in the
 *                          owners of the organisation lists and details
 * @param suspension        set by the realm owner only, null when the account is not suspended. suspendedBy is
 *                          null for an org admin (see SuspensionViews)
 */
public record OrgMember(
    String id,
    String username,
    String email,
    String firstName,
    String lastName,
    Boolean enabled,
    OrgRole role,
    Boolean invitationPending,
    Suspension suspension){ }
