package org.orgkeep.api.model;

/**
 * A role valid for every product of a catalog ("searcher", "stats", "anonymous"): the API of the realm owner combines
 * it with the products of the user. In the tokens: its name in resource_access.&lt;catalog&gt;.roles.
 *
 * @param orgGrantable true: the org admins grant it through their teams. false: reserved to the realm owner, who grants
 *                     it directly to a user or a service account
 */
public record CatalogRole(
    String catalog,
    String name,
    String displayName,
    String description,
    boolean orgGrantable){ }
