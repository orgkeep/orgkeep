package io.orgkeep.dashboard.product;

import io.orgkeep.dashboard.core.CatalogsClient;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import lombok.RequiredArgsConstructor;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.util.List;

/**
 * Every product of every catalog (/api/products), read by the product filter of the organisations screen and the
 * assignment of a product to an organisation. The catalogs screens have their own endpoints, see {@link CatalogsResource}.
 */
@Path("/products")
@RolesAllowed("realm-owner")
@RequiredArgsConstructor
public class ProductsResource {

    @RestClient
    private final CatalogsClient catalogsClient;

    /**
     * An aggregation for the screens, not a rule: the core lists the products of one catalog, the screens want them
     * all. One call for the catalogs, then one per catalog (a realm has a handful). Sorted by catalog, then product.
     */
    @GET
    public List<Product> list() {
        return catalogsClient.list().stream()
                .flatMap(catalog -> catalogsClient.products(catalog.name()).stream())
                .toList();
    }
}
