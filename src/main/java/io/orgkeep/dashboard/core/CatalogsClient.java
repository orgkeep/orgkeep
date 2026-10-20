package io.orgkeep.dashboard.core;

import io.orgkeep.dashboard.product.Catalog;
import io.orgkeep.dashboard.product.Product;
import io.quarkus.oidc.token.propagation.common.AccessToken;
import io.quarkus.rest.client.reactive.ClientExceptionMapper;
import jakarta.ws.rs.GET;
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

    @GET
    @Path("/{catalog}/products")
    List<Product> products(@PathParam("catalog") String catalog);

    @ClientExceptionMapper
    static RuntimeException toException(Response response) {
        return CoreApiException.from(response);
    }
}
