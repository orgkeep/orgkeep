package io.orgkeep.dashboard.product;

/** A catalog of products. Copy of Catalog of orgkeep-core. */
public record Catalog(
        String name,
        String displayName,
        String description,
        int productCount) { }
