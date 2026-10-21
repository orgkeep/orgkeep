package io.orgkeep.components.organisation.usergroup.team;

import io.orgkeep.api.TeamService;
import io.orgkeep.api.model.CatalogRole;
import io.orgkeep.web.CurrentRealm;
import io.orgkeep.web.security.OrgPermissions;
import io.quarkus.security.PermissionsAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import lombok.RequiredArgsConstructor;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;

/**
 * The catalogs of an organisation and their catalog roles, as its org admins see them to configure their teams.
 */
@Path("/organisations/{org}/catalogs")
@PermissionsAllowed(OrgPermissions.ADMINISTER_ORG)
@Tag(name = "Teams", description = "What the teams of an organisation give, managed by its org admins")
@SecurityRequirement(name = "SecurityScheme")
@RequiredArgsConstructor
public class AvailableCatalogsResource {
    private final TeamService teamService;
    private final CurrentRealm currentRealm;

    @GET
    @Operation(summary = "List the catalogs of the organisation",
            description = "The names of the catalogs where the organisation has at least one product, sorted.")
    @APIResponse(responseCode = "404", description = "Unknown organisation")
    public List<String> list(@PathParam("org") String org){
        return teamService.catalogs(currentRealm.name(), org);
    }

    @GET
    @Path("/{catalog}/roles")
    @Operation(summary = "List the catalog roles the org admins can give",
            description = "The roles of the catalog the realm owner made grantable by the org admins.")
    @APIResponse(responseCode = "404", description = "Unknown organisation or catalog, or no product of the organisation in it")
    public List<CatalogRole> roles(@PathParam("org") String org, @PathParam("catalog") String catalog){
        return teamService.grantableRoles(currentRealm.name(), org, catalog);
    }
}
