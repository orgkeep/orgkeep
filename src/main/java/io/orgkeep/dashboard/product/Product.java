package io.orgkeep.dashboard.product;

import java.util.List;

/**
 * A product of a catalog. Copy of Product of orgkeep-core.
 *
 * @param catalog  the catalog of the product: the same name may exist in several catalogs
 * @param name     the role name in the catalog client, e.g. "billing"
 * @param roles    roles of the product an org can buy, sorted by name
 * @param params   parameter schema, besides the standard quota and cycle
 * @param orgCount number of organisations the product is assigned to
 */
public record Product(
        String catalog,
        String name,
        String displayName,
        String description,
        List<ProductRole> roles,
        List<ParamDefinition> params,
        int orgCount) {

    /** Copy of ProductRole of orgkeep-core. */
    public record ProductRole(String name, String displayName) { }

    /**
     * Copy of ParamDefinition of orgkeep-core.
     *
     * @param type   NUMBER, STRING, ENUM or BOOLEAN
     * @param values the allowed values of an enum
     */
    public record ParamDefinition(String name, String type, boolean required, String defaultValue, List<String> values) { }
}
