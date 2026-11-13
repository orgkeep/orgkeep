package org.orgkeep.api;

import org.orgkeep.api.model.AssignProductRequest;
import org.orgkeep.api.model.CreateProductRequest;
import org.orgkeep.api.model.OrgProduct;
import org.orgkeep.api.model.Product;
import org.orgkeep.api.model.UpdateFeatureRequest;
import org.orgkeep.api.model.UpdateProductRequest;

import java.util.List;

/**
 * Products of the catalogs, their features and parameters, and their assignment to organisations
 * (see {@link org.orgkeep.api.model.CatalogRoles}).
 * <p>
 * Every method throws {@link org.orgkeep.api.exceptions.CatalogNotFoundException} for an unknown catalog, and
 * {@link org.orgkeep.api.exceptions.ProductNotFoundException} for an unknown product.
 */
public interface ProductService {
    /**
     * Creates the product role, its features, and the mappers of its claims on the scope of the catalog.
     *
     * @throws org.orgkeep.api.exceptions.RoleNameTakenException if a product or a catalog role has this name
     * @throws org.orgkeep.api.exceptions.InvalidRequestException if the parameter schema is inconsistent
     */
    Product create(String realm, String catalog, CreateProductRequest request);

    /** The products of the catalog, sorted by name. */
    List<Product> list(String realm, String catalog);

    Product get(String realm, String catalog, String product);

    /**
     * Replaces the display name, the description and the parameter schema. The values of the orgs the product is
     * assigned to follow: a removed parameter is removed from them, a new one gets its default.
     *
     * @throws org.orgkeep.api.exceptions.InvalidRequestException if the schema is inconsistent, or does not fit the
     *                                                             values of an org (a new required parameter without default)
     * @throws org.orgkeep.api.exceptions.ResourceBusyException if a change is in progress on some of the orgs the
     *                                                         product is assigned to: the others are updated, repeat
     *                                                         the call
     */
    Product update(String realm, String catalog, String product, UpdateProductRequest request);

    /**
     * Deletes the product and its features.
     *
     * @throws org.orgkeep.api.exceptions.OperationNotAllowedException if the product is still assigned to an org
     */
    void delete(String realm, String catalog, String product);

    /**
     * Creates the feature of the product, or updates its display name. Whether it is metered is set at creation.
     *
     * @throws org.orgkeep.api.exceptions.OperationNotAllowedException for another metered value on an existing feature,
     *                                                               or a first metered feature on an assigned product
     *                                                               (its kind would change)
     */
    Product putFeature(String realm, String catalog, String product, String feature, UpdateFeatureRequest request);

    /**
     * Deletes the feature of the product: the orgs that bought it lose it.
     *
     * @throws org.orgkeep.api.exceptions.FeatureNotFoundException if the product has no such feature
     * @throws org.orgkeep.api.exceptions.OperationNotAllowedException for a metered feature bought by an org, or the last
     *                                                               metered feature of an assigned product
     * @throws org.orgkeep.api.exceptions.ResourceBusyException if a change is in progress on some of the orgs the
     *                                                         product is assigned to: the others are done and the
     *                                                         feature is kept, repeat the call
     */
    Product deleteFeature(String realm, String catalog, String product, String feature);

    /**
     * Assigns the product to the org with what it negotiated. Idempotent: assigning it again replaces these values.
     * A simple product takes a quota of product; a metered product a quota per metered feature bought, and at least
     * one feature bought.
     *
     * @throws org.orgkeep.api.exceptions.OrgNotFoundException if no organisation has this name
     * @throws org.orgkeep.api.exceptions.InvalidRequestException if a bought feature is not a feature of the product,
     *                                                             the quotas do not fit its kind, or the parameters do
     *                                                             not fit its schema
     * @throws org.orgkeep.api.exceptions.ProductAlreadyAssignedException if another call assigned the product to the
     *                                                                   org between the check and the creation
     * @throws org.orgkeep.api.exceptions.ResourceBusyException if a change is in progress on the org (a single try)
     */
    OrgProduct assign(String realm, String orgName, String catalog, String product, AssignProductRequest request);

    /**
     * Deletes the assignment group of the org.
     *
     * @throws org.orgkeep.api.exceptions.OrgNotFoundException if no organisation has this name
     * @throws org.orgkeep.api.exceptions.ProductNotAssignedException if the org does not have this product
     * @throws org.orgkeep.api.exceptions.ResourceBusyException if a change is in progress on the org (a single try)
     */
    void unassign(String realm, String orgName, String catalog, String product);

    /**
     * Every product assigned to the org, sorted by catalog then product.
     *
     * @throws org.orgkeep.api.exceptions.OrgNotFoundException if no organisation has this name
     */
    List<OrgProduct> listAssigned(String realm, String orgName);
}
