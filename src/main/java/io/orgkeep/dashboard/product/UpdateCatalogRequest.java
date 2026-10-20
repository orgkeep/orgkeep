package io.orgkeep.dashboard.product;

/** Copy of UpdateCatalogRequest of orgkeep-core: the name of a catalog cannot change. */
public record UpdateCatalogRequest(
        String displayName,
        String description) { }
