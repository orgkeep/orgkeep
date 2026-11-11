package org.orgkeep.api;

import org.orgkeep.api.model.Catalog;
import org.orgkeep.api.model.CatalogCredentials;
import org.orgkeep.api.model.CreateCatalogRequest;
import org.orgkeep.api.model.UpdateCatalogRequest;

import java.util.List;

/**
 * Catalogs of products of the realm (see {@link org.orgkeep.api.model.Catalogs}).
 */
public interface CatalogService {
    /**
     * Creates the catalog client and its client scope, an optional scope of the realm and of the login client.
     *
     * @throws org.orgkeep.api.exceptions.CatalogAlreadyExistsException if a client or a client scope has this name
     */
    Catalog create(String realm, CreateCatalogRequest request);

    /** The catalogs of the realm, sorted by name. */
    List<Catalog> list(String realm);

    /** @throws org.orgkeep.api.exceptions.CatalogNotFoundException if no catalog has this name */
    Catalog get(String realm, String name);

    /** @throws org.orgkeep.api.exceptions.CatalogNotFoundException if no catalog has this name */
    Catalog update(String realm, String name, UpdateCatalogRequest request);

    /**
     * Generates a new secret for the catalog client, the credentials of its APIs to introspect the tokens.
     * The previous secret stops working at once.
     *
     * @throws org.orgkeep.api.exceptions.CatalogNotFoundException if no catalog has this name
     */
    CatalogCredentials regenerateSecret(String realm, String name);

    /**
     * Deletes the catalog client and its client scope.
     *
     * @throws org.orgkeep.api.exceptions.CatalogNotFoundException if no catalog has this name
     * @throws org.orgkeep.api.exceptions.OperationNotAllowedException if the catalog still has products
     */
    void delete(String realm, String name);
}
