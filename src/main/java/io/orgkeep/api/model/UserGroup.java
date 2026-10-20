package io.orgkeep.api.model;

import java.util.Set;

/**
 * A group of members of an organisation, under /{org}/user-groups.
 *
 * @param id         Keycloak group id
 * @param predefined true for the groups created with the organisation (admin, users): they cannot be deleted
 * @param memberIds  Keycloak ids of the direct members
 */
public record UserGroup(
    String id,
    String name,
    boolean predefined,
    Set<String> memberIds){ }
