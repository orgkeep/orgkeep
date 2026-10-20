package io.orgkeep.api;

import io.orgkeep.api.model.Catalog;
import io.orgkeep.api.model.CreateCatalogRequest;
import io.orgkeep.api.model.UpdateCatalogRequest;

import java.util.List;

/**
 * Catalogs of products of the realm (see {@link io.orgkeep.api.model.Catalogs}).
 */
public interface CatalogService {
    /**
     * Creates the catalog client and its client scope, an optional scope of the realm and of the login client.
     *
     * @throws io.orgkeep.api.exceptions.CatalogAlreadyExistsException if a client or a client scope has this name
     */
    Catalog create(String realm, CreateCatalogRequest request);

    /** The catalogs of the realm, sorted by name. */
    List<Catalog> list(String realm);

    /** @throws io.orgkeep.api.exceptions.CatalogNotFoundException if no catalog has this name */
    Catalog get(String realm, String name);

    /** @throws io.orgkeep.api.exceptions.CatalogNotFoundException if no catalog has this name */
    Catalog update(String realm, String name, UpdateCatalogRequest request);

    /**
     * Deletes the catalog client and its client scope.
     *
     * @throws io.orgkeep.api.exceptions.CatalogNotFoundException if no catalog has this name
     * @throws io.orgkeep.api.exceptions.OperationNotAllowedException if the catalog still has products
     */
    void delete(String realm, String name);
}
