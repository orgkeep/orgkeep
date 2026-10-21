package io.orgkeep.api.model;

import java.util.List;

/**
 * A product of a catalog.
 *
 * @param catalog  the catalog of the product: a product only exists in its catalog
 * @param name     name of the product role in the catalog client, hence in resource_access.&lt;catalog&gt;.roles
 * @param features features of the product, sorted by name. May be empty
 * @param params   parameter schema, sorted by name, besides the standard quota and cycle
 * @param orgCount number of organisations the product is assigned to
 */
public record Product(
    String catalog,
    String name,
    String displayName,
    String description,
    List<Feature> features,
    List<ParamDefinition> params,
    int orgCount){ }
