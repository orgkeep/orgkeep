package io.orgkeep.dashboard.core;

import io.orgkeep.dashboard.product.Catalog;
import io.orgkeep.dashboard.product.CreateCatalogRequest;
import io.orgkeep.dashboard.product.CreateProductRequest;
import io.orgkeep.dashboard.product.Product;
import io.orgkeep.dashboard.product.UpdateCatalogRequest;
import io.orgkeep.dashboard.product.UpdateProductRequest;
import io.orgkeep.dashboard.product.UpdateProductRoleRequest;
import io.quarkus.oidc.token.propagation.common.AccessToken;
import io.quarkus.rest.client.reactive.ClientExceptionMapper;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import java.util.List;

/**
 * The catalogs of the core and their products (CatalogsResource and ProductsResource in orgkeep-core).
 * Same setup as {@link OrganisationsClient}: same configKey, so same base URL, and the access token of the user.
 */
@Path("/admin/catalogs")
@RegisterRestClient(configKey = "core")
@AccessToken
public interface CatalogsClient {

    @GET
    List<Catalog> list();

    @POST
    Catalog create(CreateCatalogRequest request);

    @GET
    @Path("/{catalog}")
    Catalog get(@PathParam("catalog") String catalog);

    @PUT
    @Path("/{catalog}")
    Catalog update(@PathParam("catalog") String catalog, UpdateCatalogRequest request);

    @DELETE
    @Path("/{catalog}")
    void delete(@PathParam("catalog") String catalog);

    @GET
    @Path("/{catalog}/products")
    List<Product> products(@PathParam("catalog") String catalog);

    @POST
    @Path("/{catalog}/products")
    Product createProduct(@PathParam("catalog") String catalog, CreateProductRequest request);

    @GET
    @Path("/{catalog}/products/{product}")
    Product getProduct(@PathParam("catalog") String catalog, @PathParam("product") String product);

    @PUT
    @Path("/{catalog}/products/{product}")
    Product updateProduct(@PathParam("catalog") String catalog, @PathParam("product") String product,
                          UpdateProductRequest request);

    @DELETE
    @Path("/{catalog}/products/{product}")
    void deleteProduct(@PathParam("catalog") String catalog, @PathParam("product") String product);

    // creates the role, or changes its display name: the core has a single PUT for both
    @PUT
    @Path("/{catalog}/products/{product}/roles/{role}")
    Product putProductRole(@PathParam("catalog") String catalog, @PathParam("product") String product,
                           @PathParam("role") String role, UpdateProductRoleRequest request);

    // answers the product without the role
    @DELETE
    @Path("/{catalog}/products/{product}/roles/{role}")
    Product deleteProductRole(@PathParam("catalog") String catalog, @PathParam("product") String product,
                              @PathParam("role") String role);

    @ClientExceptionMapper
    static RuntimeException toException(Response response) {
        return CoreApiException.from(response);
    }
}
