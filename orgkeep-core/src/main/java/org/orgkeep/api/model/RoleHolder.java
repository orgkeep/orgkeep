package org.orgkeep.api.model;

/**
 * A user or a service account holding a role directly (not through a group).
 *
 * @param email          null for a service account
 * @param serviceAccount the clientId of the service account, null for a user
 * @param org            the org of the service account (the attribute of its client), null for a user
 */
public record RoleHolder(
    String userId,
    String username,
    String email,
    String serviceAccount,
    String org){ }
