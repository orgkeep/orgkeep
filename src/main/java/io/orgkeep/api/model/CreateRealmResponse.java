package io.orgkeep.api.model;

/**
 * @param id      Keycloak realm id
 * @param ownerId user id of the realm owner
 */
public record CreateRealmResponse(
    String id,
    String realmName,
    String ownerId){ }
