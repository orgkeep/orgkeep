package io.orgkeep.components.catalog;

import io.orgkeep.api.CatalogService;
import io.orgkeep.api.model.Catalog;
import io.orgkeep.api.model.CatalogCredentials;
import io.orgkeep.api.model.CreateCatalogRequest;
import io.orgkeep.api.model.UpdateCatalogRequest;
import io.orgkeep.web.CurrentRealm;
import jakarta.annotation.security.RolesAllowed;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import lombok.RequiredArgsConstructor;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponseSchema;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.resteasy.reactive.ResponseStatus;

import java.util.List;

/**
 * Catalogs of products of the realm, managed by the realm owner.
 */
@Path("/admin/catalogs")
@RolesAllowed("realm-owner")
@Tag(name = "Catalogs", description = "Catalogs of products of the realm, managed by the realm owner")
@RequiredArgsConstructor
public class CatalogsResource {
    private final CatalogService catalogService;
    private final CurrentRealm currentRealm;

    @POST
    @ResponseStatus(201)
    @Operation(summary = "Create a catalog", description = "Creates a client of this name, container of the product roles, and a client scope of the same name, which will carry the claim of the catalog in the access tokens.")
    @APIResponseSchema(value = Catalog.class, responseCode = "201", responseDescription = "Catalog created")
    @APIResponse(responseCode = "400", description = "Invalid or reserved name")
    @APIResponse(responseCode = "409", description = "A client or a client scope already has this name")
    public Catalog create(@Valid @NotNull CreateCatalogRequest request){
        return catalogService.create(currentRealm.name(), request);
    }

    @GET
    @Operation(summary = "List the catalogs", description = "Sorted by name, with the number of products of each.")
    public List<Catalog> list(){
        return catalogService.list(currentRealm.name());
    }

    @GET
    @Path("/{catalog}")
    @Operation(summary = "Get a catalog")
    @APIResponseSchema(value = Catalog.class, responseCode = "200", responseDescription = "The catalog")
    @APIResponse(responseCode = "404", description = "Unknown catalog")
    public Catalog get(@PathParam("catalog") String name){
        return catalogService.get(currentRealm.name(), name);
    }

    @PUT
    @Path("/{catalog}")
    @Operation(summary = "Update a catalog", description = "Replaces its display name and description. Its name cannot change: it is the claim name the applications read.")
    @APIResponseSchema(value = Catalog.class, responseCode = "200", responseDescription = "Catalog updated")
    @APIResponse(responseCode = "400", description = "Invalid request")
    @APIResponse(responseCode = "404", description = "Unknown catalog")
    public Catalog update(@PathParam("catalog") String name, @Valid @NotNull UpdateCatalogRequest request){
        return catalogService.update(currentRealm.name(), name, request);
    }

    @POST
    @Path("/{catalog}/secret")
    @Operation(summary = "Regenerate the secret of a catalog",
            description = "The credentials the APIs of the catalog introspect the tokens with (RFC 7662). The secret is "
                    + "only shown in this response, and the previous one stops working at once: every API of the "
                    + "catalog must get the new one.")
    @APIResponseSchema(value = CatalogCredentials.class, responseCode = "200", responseDescription = "The new credentials")
    @APIResponse(responseCode = "404", description = "Unknown catalog")
    public CatalogCredentials regenerateSecret(@PathParam("catalog") String name){
        return catalogService.regenerateSecret(currentRealm.name(), name);
    }

    @DELETE
    @Path("/{catalog}")
    @Operation(summary = "Delete a catalog", description = "Refused while the catalog has products.")
    @APIResponse(responseCode = "204", description = "Catalog deleted")
    @APIResponse(responseCode = "404", description = "Unknown catalog")
    @APIResponse(responseCode = "409", description = "The catalog still has products")
    public void delete(@PathParam("catalog") String name){
        catalogService.delete(currentRealm.name(), name);
    }
}
