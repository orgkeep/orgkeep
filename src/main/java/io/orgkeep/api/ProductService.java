package io.orgkeep.api;

import io.orgkeep.api.model.AssignProductRequest;
import io.orgkeep.api.model.CreateProductRequest;
import io.orgkeep.api.model.OrgProduct;
import io.orgkeep.api.model.Product;
import io.orgkeep.api.model.UpdateProductRequest;
import io.orgkeep.api.model.UpdateProductRoleRequest;

import java.util.List;

/**
 * Products of the catalogs, their roles and parameters, and their assignment to organisations
 * (see {@link io.orgkeep.api.model.CatalogRoles}).
 * <p>
 * Every method throws {@link io.orgkeep.api.exceptions.CatalogNotFoundException} for an unknown catalog, and
 * {@link io.orgkeep.api.exceptions.ProductNotFoundException} for an unknown product.
 */
public interface ProductService {
    /**
     * Creates the product role, its roles, and the mappers of its claims on the scope of the catalog.
     *
     * @throws io.orgkeep.api.exceptions.RoleNameTakenException if a product or a catalog role has this name
     * @throws io.orgkeep.api.exceptions.InvalidRequestException if the parameter schema is inconsistent
     */
    Product create(String realm, String catalog, CreateProductRequest request);

    /** The products of the catalog, sorted by name. */
    List<Product> list(String realm, String catalog);

    Product get(String realm, String catalog, String product);

    /**
     * Replaces the display name, the description and the parameter schema. The values of the orgs the product is
     * assigned to follow: a removed parameter is removed from them, a new one gets its default.
     *
     * @throws io.orgkeep.api.exceptions.InvalidRequestException if the schema is inconsistent, or does not fit the
     *                                                             values of an org (a new required parameter without default)
     */
    Product update(String realm, String catalog, String product, UpdateProductRequest request);

    /**
     * Deletes the product and its roles.
     *
     * @throws io.orgkeep.api.exceptions.OperationNotAllowedException if the product is still assigned to an org
     */
    void delete(String realm, String catalog, String product);

    /** Creates the role of the product, or updates its display name. */
    Product putRole(String realm, String catalog, String product, String role, UpdateProductRoleRequest request);

    /**
     * Deletes the role of the product: the orgs that bought it lose it.
     *
     * @throws io.orgkeep.api.exceptions.ProductRoleNotFoundException if the product has no such role
     */
    Product deleteRole(String realm, String catalog, String product, String role);

    /**
     * Assigns the product to the org with what it negotiated. Idempotent: assigning it again replaces these values.
     *
     * @throws io.orgkeep.api.exceptions.OrgNotFoundException if no organisation has this name
     * @throws io.orgkeep.api.exceptions.InvalidRequestException if a bought role is not a role of the product,
     *                                                             or the parameters do not fit its schema
     */
    OrgProduct assign(String realm, String orgName, String catalog, String product, AssignProductRequest request);

    /**
     * Deletes the assignment group of the org.
     *
     * @throws io.orgkeep.api.exceptions.OrgNotFoundException if no organisation has this name
     * @throws io.orgkeep.api.exceptions.ProductNotAssignedException if the org does not have this product
     */
    void unassign(String realm, String orgName, String catalog, String product);

    /**
     * Every product assigned to the org, sorted by catalog then product.
     *
     * @throws io.orgkeep.api.exceptions.OrgNotFoundException if no organisation has this name
     */
    List<OrgProduct> listAssigned(String realm, String orgName);
}
