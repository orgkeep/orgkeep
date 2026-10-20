package io.orgkeep.components.organisation;

import io.orgkeep.api.OrgService;
import io.orgkeep.api.model.CreateOrgRequest;
import io.orgkeep.api.model.CreateOrgResponse;
import io.orgkeep.api.model.OrgDetails;
import io.orgkeep.api.model.OrgSummary;
import io.orgkeep.web.CurrentRealm;
import jakarta.annotation.security.RolesAllowed;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import lombok.RequiredArgsConstructor;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponseSchema;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.resteasy.reactive.ResponseStatus;

import java.util.List;

/**
 * Management of the organisations of the realm, by the realm owner.
 */
@Path("/admin/organisations")
@RolesAllowed("realm-owner")
@Tag(name = "Organisations", description = "Organisations of the realm, managed by the realm owner")
@RequiredArgsConstructor
public class OrganisationsResource {
    private final OrgService orgService;
    private final CurrentRealm currentRealm;

    // @Valid triggers Bean Validation on the body: a violation answers 400 before the method runs
    @POST
    @ResponseStatus(201)
    @Operation(summary = "Create an organisation",
            description = "Creates the organisation, its client org-{name} with the org roles, its group tree "
                    + "and, if an owner email is given, makes that user its owner.")
    @APIResponseSchema(value = CreateOrgResponse.class, responseCode = "201", responseDescription = "Organisation created")
    @APIResponse(responseCode = "400", description = "Invalid request")
    @APIResponse(responseCode = "409", description = "An organisation or a client with this name already exists")
    public CreateOrgResponse create(@Valid @NotNull CreateOrgRequest request){
        return orgService.create(currentRealm.name(), request);
    }

    @GET
    @Operation(summary = "List organisations",
            description = "Paginated, optionally filtered on the name and on an assigned product: catalog and product "
                    + "together, or a catalog alone (any of its products), or a product alone (in any catalog). "
                    + "An unknown catalog or product gives an empty list.")
    public List<OrgSummary> list(@QueryParam("search") String search,
                                 @QueryParam("catalog") String catalog,
                                 @QueryParam("product") String product,
                                 @QueryParam("first") @DefaultValue("0") @Min(0) int first,
                                 @QueryParam("max") @DefaultValue("100") @Min(1) @Max(100) int max){
        return orgService.list(currentRealm.name(), search, catalog, product, first, max);
    }

    @GET
    @Path("/{org}")
    @Operation(summary = "Get an organisation", description = "With its client, root group and owners.")
    @APIResponseSchema(value = OrgDetails.class, responseCode = "200", responseDescription = "The organisation")
    @APIResponse(responseCode = "404", description = "Unknown organisation")
    public OrgDetails get(@PathParam("org") String org){
        return orgService.get(currentRealm.name(), org);
    }

    // void return type: Quarkus REST answers 204 No Content
    @DELETE
    @Path("/{org}")
    @Operation(summary = "Delete an organisation", description = "Also deletes its client and group tree. Users are kept.")
    @APIResponse(responseCode = "204", description = "Organisation deleted")
    @APIResponse(responseCode = "404", description = "Unknown organisation")
    public void delete(@PathParam("org") String org){
        orgService.delete(currentRealm.name(), org);
    }
}
