package org.orgkeep.api.events;

/**
 * Published on the Vert.x event bus when Orgkeep creates a Keycloak user (a reused user is not created).
 * Consumed by the core to send the invitation email; other modules (dashboard audit trail) may listen to it too.
 *
 * @param orgName     the organisation the user was created for, null for a realm owner
 * @param owner       true when the user becomes the realm owner (orgName null) or the owner of orgName. Published
 *                    before the role is granted (org owner: before the client role, realm owner: with its realm
 *                    roles already set), which is why it travels here instead of being resolved later
 * @param passwordSet true when the caller gave a password, communicated by other means:
 *                    no invitation is needed. False for a user without password or with a generated one
 */
public record UserCreated(String realmName, String orgName, String userId, boolean owner, boolean passwordSet) {
    public static final String ADDRESS = "orgkeep.user-created";
}
