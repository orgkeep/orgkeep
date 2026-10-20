package io.orgkeep.dashboard.product;

import io.orgkeep.dashboard.product.Product.ParamDefinition;
import io.orgkeep.dashboard.product.Product.ProductRole;

import java.util.List;

/**
 * Copy of CreateProductRequest of orgkeep-core, without Bean Validation: the core checks the names and the parameter
 * schema, and its 400 is relayed to the front.
 *
 * @param roles  the roles of the product, may be empty
 * @param params the parameter schema besides the standard quota and cycle, may be empty
 */
public record CreateProductRequest(
        String name,
        String displayName,
        String description,
        List<ProductRole> roles,
        List<ParamDefinition> params) { }
