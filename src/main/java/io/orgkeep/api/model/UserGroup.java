package io.orgkeep.api.model;

import java.util.List;
import java.util.Set;

/**
 * A group of members of an organisation, under /{org}/user-groups. A team: it gives products of the org and catalog
 * roles to its members.
 *
 * @param id           Keycloak group id
 * @param predefined   true for the groups created with the organisation (admin, users): they cannot be deleted
 * @param memberIds    Keycloak ids of the direct members
 * @param products     products given by the group, sorted by catalog then product
 * @param catalogRoles catalog roles given by the group, sorted by catalog then role
 */
public record UserGroup(
    String id,
    String name,
    boolean predefined,
    Set<String> memberIds,
    List<TeamProduct> products,
    List<TeamCatalogRole> catalogRoles){ }
