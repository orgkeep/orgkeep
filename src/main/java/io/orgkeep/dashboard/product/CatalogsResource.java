package io.orgkeep.dashboard.product;

import io.orgkeep.dashboard.core.CatalogsClient;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import lombok.RequiredArgsConstructor;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.resteasy.reactive.ResponseStatus;

import java.util.List;

/**
 * The catalogs screens of the realm owner (/api/catalogs): the catalogs, the products of a catalog, and one product
 * with its roles and its parameters.
 * <p>
 * A plain relay to the core, with the same paths: one endpoint of the dashboard per endpoint of the core.
 * The core checks the roles again: this check only answers early, without a call to the core.
 */
@Path("/catalogs")
@RolesAllowed("realm-owner")
@RequiredArgsConstructor
public class CatalogsResource {

    @RestClient
    private final CatalogsClient catalogsClient;

    @GET
    public List<Catalog> list() {
        return catalogsClient.list();
    }

    @POST
    @ResponseStatus(201)
    public Catalog create(CreateCatalogRequest request) {
        return catalogsClient.create(request);
    }

    @GET
    @Path("/{catalog}")
    public Catalog get(@PathParam("catalog") String catalog) {
        return catalogsClient.get(catalog);
    }

    @PUT
    @Path("/{catalog}")
    public Catalog update(@PathParam("catalog") String catalog, UpdateCatalogRequest request) {
        return catalogsClient.update(catalog, request);
    }

    // the core answers 409 while the catalog still has products or roles: relayed as is
    @DELETE
    @Path("/{catalog}")
    public void delete(@PathParam("catalog") String catalog) {
        catalogsClient.delete(catalog);
    }

    @GET
    @Path("/{catalog}/products")
    public List<Product> products(@PathParam("catalog") String catalog) {
        return catalogsClient.products(catalog);
    }

    @POST
    @Path("/{catalog}/products")
    @ResponseStatus(201)
    public Product createProduct(@PathParam("catalog") String catalog, CreateProductRequest request) {
        return catalogsClient.createProduct(catalog, request);
    }

    @GET
    @Path("/{catalog}/products/{product}")
    public Product getProduct(@PathParam("catalog") String catalog, @PathParam("product") String product) {
        return catalogsClient.getProduct(catalog, product);
    }

    @PUT
    @Path("/{catalog}/products/{product}")
    public Product updateProduct(@PathParam("catalog") String catalog, @PathParam("product") String product,
                                 UpdateProductRequest request) {
        return catalogsClient.updateProduct(catalog, product, request);
    }

    // the core answers 409 while the product is assigned to an organisation
    @DELETE
    @Path("/{catalog}/products/{product}")
    public void deleteProduct(@PathParam("catalog") String catalog, @PathParam("product") String product) {
        catalogsClient.deleteProduct(catalog, product);
    }

    @PUT
    @Path("/{catalog}/products/{product}/roles/{role}")
    public Product putProductRole(@PathParam("catalog") String catalog, @PathParam("product") String product,
                                  @PathParam("role") String role, UpdateProductRoleRequest request) {
        return catalogsClient.putProductRole(catalog, product, role, request);
    }

    @DELETE
    @Path("/{catalog}/products/{product}/roles/{role}")
    public Product deleteProductRole(@PathParam("catalog") String catalog, @PathParam("product") String product,
                                     @PathParam("role") String role) {
        return catalogsClient.deleteProductRole(catalog, product, role);
    }
}
