package io.orgkeep.components.catalog.role;

import io.orgkeep.api.CatalogRoleService;
import io.orgkeep.api.model.CatalogRole;
import io.orgkeep.api.model.CreateCatalogRoleRequest;
import io.orgkeep.api.model.RoleHolder;
import io.orgkeep.api.model.UpdateCatalogRoleRequest;
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
 * Roles valid for every product of a catalog, managed by the realm owner.
 * <p>
 * Sub path of /admin/catalogs declared in its own class: JAX-RS picks the class with the most specific path.
 */
@Path("/admin/catalogs/{catalog}/roles")
@RolesAllowed("realm-owner")
@Tag(name = "Catalog roles", description = "Roles valid for every product of a catalog, managed by the realm owner")
@RequiredArgsConstructor
public class CatalogRolesResource {
    private final CatalogRoleService catalogRoleService;
    private final CurrentRealm currentRealm;

    @POST
    @ResponseStatus(201)
    @Operation(summary = "Create a catalog role",
            description = "orgGrantable: the org admins grant it through their teams. Otherwise reserved to the realm owner, who grants it directly.")
    @APIResponseSchema(value = CatalogRole.class, responseCode = "201", responseDescription = "Catalog role created")
    @APIResponse(responseCode = "400", description = "Invalid request")
    @APIResponse(responseCode = "404", description = "Unknown catalog")
    @APIResponse(responseCode = "409", description = "A product or a catalog role already has this name")
    public CatalogRole create(@PathParam("catalog") String catalog, @Valid @NotNull CreateCatalogRoleRequest request){
        return catalogRoleService.create(currentRealm.name(), catalog, request);
    }

    @GET
    @Operation(summary = "List the catalog roles", description = "Sorted by name.")
    @APIResponse(responseCode = "404", description = "Unknown catalog")
    public List<CatalogRole> list(@PathParam("catalog") String catalog){
        return catalogRoleService.list(currentRealm.name(), catalog);
    }

    @GET
    @Path("/{role}")
    @Operation(summary = "Get a catalog role")
    @APIResponseSchema(value = CatalogRole.class, responseCode = "200", responseDescription = "The catalog role")
    @APIResponse(responseCode = "404", description = "Unknown catalog or role")
    public CatalogRole get(@PathParam("catalog") String catalog, @PathParam("role") String role){
        return catalogRoleService.get(currentRealm.name(), catalog, role);
    }

    @PUT
    @Path("/{role}")
    @Operation(summary = "Update a catalog role", description = "Replaces its display name, description and orgGrantable.")
    @APIResponseSchema(value = CatalogRole.class, responseCode = "200", responseDescription = "Catalog role updated")
    @APIResponse(responseCode = "400", description = "Invalid request")
    @APIResponse(responseCode = "404", description = "Unknown catalog or role")
    public CatalogRole update(@PathParam("catalog") String catalog, @PathParam("role") String role,
                              @Valid @NotNull UpdateCatalogRoleRequest request){
        return catalogRoleService.update(currentRealm.name(), catalog, role, request);
    }

    @DELETE
    @Path("/{role}")
    @Operation(summary = "Delete a catalog role", description = "The teams and users holding it lose it.")
    @APIResponse(responseCode = "204", description = "Catalog role deleted")
    @APIResponse(responseCode = "404", description = "Unknown catalog or role")
    public void delete(@PathParam("catalog") String catalog, @PathParam("role") String role){
        catalogRoleService.delete(currentRealm.name(), catalog, role);
    }

    @GET
    @Path("/{role}/users")
    @Operation(summary = "List the users holding the role directly", description = "Sorted by username. Not those getting it through a team.")
    @APIResponse(responseCode = "404", description = "Unknown catalog or role")
    public List<RoleHolder> holders(@PathParam("catalog") String catalog, @PathParam("role") String role){
        return catalogRoleService.holders(currentRealm.name(), catalog, role);
    }

    @PUT
    @Path("/{role}/users/{userId}")
    @Operation(summary = "Grant the role directly to a user", description = "Idempotent. The user must be a member of an organisation.")
    @APIResponse(responseCode = "204", description = "Role granted")
    @APIResponse(responseCode = "404", description = "Unknown catalog, role or user")
    @APIResponse(responseCode = "409", description = "The user is not a member of an organisation")
    public void grant(@PathParam("catalog") String catalog, @PathParam("role") String role, @PathParam("userId") String userId){
        catalogRoleService.grant(currentRealm.name(), catalog, role, userId);
    }

    @DELETE
    @Path("/{role}/users/{userId}")
    @Operation(summary = "Revoke the role granted directly to a user", description = "Idempotent.")
    @APIResponse(responseCode = "204", description = "Role revoked")
    @APIResponse(responseCode = "404", description = "Unknown catalog, role or user")
    public void revoke(@PathParam("catalog") String catalog, @PathParam("role") String role, @PathParam("userId") String userId){
        catalogRoleService.revoke(currentRealm.name(), catalog, role, userId);
    }
}
