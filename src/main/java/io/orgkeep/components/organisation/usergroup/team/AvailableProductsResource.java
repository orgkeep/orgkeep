package io.orgkeep.components.organisation.usergroup.team;

import io.orgkeep.api.TeamService;
import io.orgkeep.api.model.AvailableProduct;
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
 * The products of an organisation, as its org admins see them to configure their teams.
 */
@Path("/organisations/{org}/products")
@PermissionsAllowed(OrgPermissions.ADMINISTER_ORG)
@Tag(name = "Teams", description = "What the teams of an organisation give, managed by its org admins")
@SecurityRequirement(name = "SecurityScheme")
@RequiredArgsConstructor
public class AvailableProductsResource {
    private final TeamService teamService;
    private final CurrentRealm currentRealm;

    @GET
    @Operation(summary = "List the products of the organisation",
            description = "The products assigned to it, with the quota of the organisation and the roles it bought: "
                    + "what its teams can give.")
    @APIResponse(responseCode = "404", description = "Unknown organisation")
    public List<AvailableProduct> list(@PathParam("org") String org){
        return teamService.availableProducts(currentRealm.name(), org);
    }
}
