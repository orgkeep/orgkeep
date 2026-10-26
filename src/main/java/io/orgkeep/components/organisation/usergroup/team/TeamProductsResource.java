package io.orgkeep.components.organisation.usergroup.team;

import io.orgkeep.api.TeamService;
import io.orgkeep.api.model.GrantProductRequest;
import io.orgkeep.api.model.TeamProduct;
import io.orgkeep.web.CurrentRealm;
import io.orgkeep.web.security.OrgPermissions;
import io.quarkus.security.PermissionsAllowed;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import lombok.RequiredArgsConstructor;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponseSchema;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * The products a team gives to its members, managed by the org admins of its organisation.
 * <p>
 * Every method has an "org" path parameter: OrgPermissions checks that the caller administers that org.
 */
@Path("/organisations/{org}/user-groups/{group}/products/{catalog}/{product}")
@PermissionsAllowed(OrgPermissions.ADMINISTER_ORG)
@Tag(name = "Teams", description = "What the teams of an organisation give, managed by its org admins")
@SecurityRequirement(name = "SecurityScheme")
@RequiredArgsConstructor
public class TeamProductsResource {
    private final TeamService teamService;
    private final CurrentRealm currentRealm;

    // PUT: giving the product again replaces what the team gives of it
    @PUT
    @Operation(summary = "Give a product of the organisation to a team",
            description = "With the features of the product given (absent: every feature the organisation bought, also those "
                    + "it buys later; empty: no feature) and an optional quota of team, whose cycle defaults to the one "
                    + "of the organisation. A product with metered features takes at least one feature, and optional "
                    + "quotas of team by metered feature given (featureQuotas, cycle defaulting to the one of the "
                    + "organisation for the feature) instead of a quota of product. A member is in at most one team "
                    + "with a quota for a given product, or a given metered feature.")
    @APIResponseSchema(value = TeamProduct.class, responseCode = "200", responseDescription = "Product given")
    @APIResponse(responseCode = "400", description = "Invalid request, feature the organisation did not buy, cycle without quota, "
            + "quota of product or no feature for a metered product, quota of feature for a simple product or for a "
            + "feature that is not a metered feature given by the team")
    @APIResponse(responseCode = "404", description = "Unknown organisation, user group or catalog, or product not assigned to the organisation")
    @APIResponse(responseCode = "409", description = "With a quota: a member of the team is in another team with a quota "
            + "for this product, or for one of these features")
    public TeamProduct grant(@PathParam("org") String org, @PathParam("group") String group,
                             @PathParam("catalog") String catalog, @PathParam("product") String product,
                             @Valid @NotNull GrantProductRequest request){
        return teamService.grantProduct(currentRealm.name(), org, group, catalog, product, request);
    }

    @DELETE
    @Operation(summary = "Take a product away from a team",
            description = "Its members lose the product, unless another team gives it. Idempotent.")
    @APIResponse(responseCode = "204", description = "Product no longer given by the team")
    @APIResponse(responseCode = "404", description = "Unknown organisation, user group or catalog")
    public void revoke(@PathParam("org") String org, @PathParam("group") String group,
                       @PathParam("catalog") String catalog, @PathParam("product") String product){
        teamService.revokeProduct(currentRealm.name(), org, group, catalog, product);
    }
}
