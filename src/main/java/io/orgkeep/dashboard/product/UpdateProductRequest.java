package io.orgkeep.dashboard.product;

import io.orgkeep.dashboard.product.Product.ParamDefinition;

import java.util.List;

/**
 * Copy of UpdateProductRequest of orgkeep-core. The name of a product cannot change, its roles are changed one by one.
 *
 * @param params the whole parameter schema: an empty list removes every parameter
 */
public record UpdateProductRequest(
        String displayName,
        String description,
        List<ParamDefinition> params) { }
