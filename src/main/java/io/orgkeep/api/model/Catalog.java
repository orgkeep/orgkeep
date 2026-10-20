package io.orgkeep.api.model;

/**
 * A catalog of products.
 *
 * @param name         clientId of the catalog client, also the name of its claim in the access tokens
 * @param productCount number of products of the catalog
 */
public record Catalog(
    String name,
    String displayName,
    String description,
    int productCount){ }
