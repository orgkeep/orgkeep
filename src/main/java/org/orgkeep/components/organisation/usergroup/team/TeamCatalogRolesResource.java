package org.orgkeep.components.organisation.usergroup.team;

import org.orgkeep.api.TeamService;
import org.orgkeep.web.CurrentRealm;
import org.orgkeep.web.security.OrgPermissions;
import io.quarkus.security.PermissionsAllowed;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import lombok.RequiredArgsConstructor;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * The catalog roles a team gives to its members, managed by the org admins of its organisation.
 * <p>
 * Every method has an "org" path parameter: OrgPermissions checks that the caller administers that org.
 */
@Path("/organisations/{org}/user-groups/{group}/catalogs/{catalog}/roles/{role}")
@PermissionsAllowed(OrgPermissions.ADMINISTER_ORG)
@Tag(name = "Teams", description = "What the teams of an organisation give, managed by its org admins")
@SecurityRequirement(name = "SecurityScheme")
@RequiredArgsConstructor
public class TeamCatalogRolesResource {
    private final TeamService teamService;
    private final CurrentRealm currentRealm;

    @PUT
    @Operation(summary = "Give a catalog role to a team",
            description = "Only a role the realm owner made grantable by the org admins, in a catalog where the "
                    + "organisation has a product. Idempotent.")
    @APIResponse(responseCode = "204", description = "Catalog role given")
    @APIResponse(responseCode = "404", description = "Unknown organisation, user group, catalog or catalog role")
    @APIResponse(responseCode = "409", description = "Role reserved to the realm owner, or no product of the organisation in this catalog")
    public void grant(@PathParam("org") String org, @PathParam("group") String group,
                      @PathParam("catalog") String catalog, @PathParam("role") String role){
        teamService.grantCatalogRole(currentRealm.name(), org, group, catalog, role);
    }

    @DELETE
    @Operation(summary = "Take a catalog role away from a team", description = "Idempotent.")
    @APIResponse(responseCode = "204", description = "Catalog role no longer given by the team")
    @APIResponse(responseCode = "404", description = "Unknown organisation, user group, catalog or catalog role")
    public void revoke(@PathParam("org") String org, @PathParam("group") String group,
                       @PathParam("catalog") String catalog, @PathParam("role") String role){
        teamService.revokeCatalogRole(currentRealm.name(), org, group, catalog, role);
    }
}
