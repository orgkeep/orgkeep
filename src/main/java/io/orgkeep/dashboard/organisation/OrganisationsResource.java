package io.orgkeep.dashboard.organisation;

import io.orgkeep.dashboard.core.OrgMembersClient;
import io.orgkeep.dashboard.core.OrganisationsClient;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import lombok.RequiredArgsConstructor;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.resteasy.reactive.ResponseStatus;

import java.util.List;

/**
 * The organisations screens of the realm owner (/api/organisations): the list and the detail page of an organisation.
 * <p>
 * A plain relay to the core for now: one endpoint of the dashboard per endpoint of the core.
 * <p>
 * The core checks the roles again: this check only answers early, without a call to the core.
 */
@Path("/organisations")
@RolesAllowed("realm-owner")
@RequiredArgsConstructor
public class OrganisationsResource {
    // the core caps a page at 100: enough for the first version of the screen, there is no paging yet
    static final int MAX_ORGS = 100;

    // @RestClient is the qualifier of the generated client, mandatory to inject it
    @RestClient
    private final OrganisationsClient organisationsClient;
    @RestClient
    private final OrgMembersClient orgMembersClient;

    /**
     * @param catalog optional, with product: keeps the organisations this product of this catalog is assigned to.
     *                Either one alone is passed on as is (the core filters on a catalog alone, or a product alone)
     */
    @GET
    public List<OrgSummary> list(@QueryParam("search") String search, @QueryParam("catalog") String catalog,
                                 @QueryParam("product") String product) {
        return organisationsClient.list(search, catalog, product, 0, MAX_ORGS);
    }

    @POST
    @ResponseStatus(201)
    public CreateOrgResponse create(CreateOrgRequest request) {
        return organisationsClient.create(request);
    }

    @GET
    @Path("/{org}")
    public OrgDetails get(@PathParam("org") String org) {
        return organisationsClient.get(org);
    }

    // void return type: Quarkus REST answers 204 No Content
    @DELETE
    @Path("/{org}")
    public void delete(@PathParam("org") String org) {
        organisationsClient.delete(org);
    }

    @GET
    @Path("/{org}/members")
    public List<OrgMember> members(@PathParam("org") String org) {
        return orgMembersClient.list(org);
    }

    @GET
    @Path("/{org}/products")
    public List<OrgProduct> products(@PathParam("org") String org) {
        return organisationsClient.listProducts(org);
    }

    // PUT like the core: assigning a product again replaces what the org negotiated
    @PUT
    @Path("/{org}/catalogs/{catalog}/products/{product}")
    public OrgProduct assignProduct(@PathParam("org") String org, @PathParam("catalog") String catalog,
                                    @PathParam("product") String product, AssignProductRequest request) {
        return organisationsClient.assignProduct(org, catalog, product, request);
    }

    @DELETE
    @Path("/{org}/catalogs/{catalog}/products/{product}")
    public void unassignProduct(@PathParam("org") String org, @PathParam("catalog") String catalog,
                                @PathParam("product") String product) {
        organisationsClient.unassignProduct(org, catalog, product);
    }
}
