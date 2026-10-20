package io.orgkeep.dashboard.core;

import io.orgkeep.dashboard.organisation.AssignProductRequest;
import io.orgkeep.dashboard.organisation.CreateOrgRequest;
import io.orgkeep.dashboard.organisation.CreateOrgResponse;
import io.orgkeep.dashboard.organisation.OrgDetails;
import io.orgkeep.dashboard.organisation.OrgProduct;
import io.orgkeep.dashboard.organisation.OrgSummary;
import io.quarkus.oidc.token.propagation.common.AccessToken;
import io.quarkus.rest.client.reactive.ClientExceptionMapper;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import java.util.List;

/**
 * The organisations endpoints of the core (OrganisationsResource, OrgProductsResource and OrgCatalogsResource in orgkeep-core),
 * as seen by the dashboard.
 * <p>
 * A declarative REST client: Quarkus generates the implementation at build time, like a Spring {@code @FeignClient}.
 * The base URL comes from {@code quarkus.rest-client.core.url} (the configKey).
 * <p>
 * {@code @AccessToken} adds {@code Authorization: Bearer <access token of the logged in user>} to every call:
 * the core checks the roles itself, the dashboard never calls it with more rights than the user has.
 */
@Path("/admin/organisations")
@RegisterRestClient(configKey = "core")
@AccessToken
public interface OrganisationsClient {

    @GET
    // a null query param is left out of the URL
    List<OrgSummary> list(@QueryParam("search") String search,
                          @QueryParam("catalog") String catalog,
                          @QueryParam("product") String product,
                          @QueryParam("first") int first,
                          @QueryParam("max") int max);

    @POST
    CreateOrgResponse create(CreateOrgRequest request);

    @GET
    @Path("/{org}")
    OrgDetails get(@PathParam("org") String org);

    @DELETE
    @Path("/{org}")
    void delete(@PathParam("org") String org);

    @GET
    @Path("/{org}/products")
    List<OrgProduct> listProducts(@PathParam("org") String org);

    @PUT
    @Path("/{org}/catalogs/{catalog}/products/{product}")
    OrgProduct assignProduct(@PathParam("org") String org, @PathParam("catalog") String catalog,
                             @PathParam("product") String product, AssignProductRequest request);

    @DELETE
    @Path("/{org}/catalogs/{catalog}/products/{product}")
    void unassignProduct(@PathParam("org") String org, @PathParam("catalog") String catalog,
                         @PathParam("product") String product);

    /**
     * Called by Quarkus for every answer with a status >= 400, instead of throwing a generic WebApplicationException:
     * keeps the status and the message of the core, see {@link CoreApiException}.
     */
    @ClientExceptionMapper
    static RuntimeException toException(Response response) {
        return CoreApiException.from(response);
    }
}
