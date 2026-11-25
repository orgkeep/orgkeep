package org.orgkeep.components.organisation.member;

import org.orgkeep.api.OrgOwnerService;
import org.orgkeep.api.model.OrgMember;
import org.orgkeep.api.model.Slug;
import org.orgkeep.web.CurrentRealm;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import lombok.RequiredArgsConstructor;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponseSchema;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Ownership of an organisation, changed by the realm owner only: an org admin or an owner (of this organisation or
 * another) gets 403. A transfer is two calls: PUT for the new owner, then DELETE for the former one.
 * <p>
 * Sub path of /admin/organisations declared in its own class, like TokenPreviewResource: a class at
 * /admin/organisations/{org} would also capture GET /admin/organisations/{org}.
 */
@Path("/admin/organisations/{org}/owners/{userId}")
@RolesAllowed("realm-owner")
@Tag(name = "Organisation owners", description = "Ownership of an organisation, changed by the realm owner")
@RequiredArgsConstructor
public class OrgOwnersResource {
    private final OrgOwnerService ownerService;
    private final CurrentRealm currentRealm;

    @PUT
    @Operation(summary = "Grant ownership of the organisation",
            description = "Grants the client role org-owner directly to an existing person member of the "
                    + "organisation, who leaves the default group user-groups/users if in it. Idempotent.")
    @APIResponseSchema(value = OrgMember.class, responseCode = "200", responseDescription = "The member, now an owner")
    @APIResponse(responseCode = "404", description = "Unknown organisation, or the user is not a person member of this organisation")
    @APIResponse(responseCode = "409", description = "The member is disabled by its own org admin, cannot be made "
            + "an owner (a privileged member), or a change is in progress on the organisation or the member (try again later)")
    public OrgMember grant(@PathParam("org") @Slug String org, @PathParam("userId") String userId) {
        return ownerService.grant(currentRealm.name(), org, userId);
    }

    @DELETE
    @Operation(summary = "Revoke ownership of the organisation",
            description = "Removes the direct client role org-owner: the member joins the default group "
                    + "user-groups/users if not in it already, and becomes a plain member who can then be removed "
                    + "or disabled. Its other groups and teams, admin included, are kept. Idempotent, also on a "
                    + "member who is not an owner. An organisation can end with zero owners.")
    @APIResponse(responseCode = "204", description = "Ownership revoked")
    @APIResponse(responseCode = "404", description = "Unknown organisation, or the user is not a person member of this organisation")
    @APIResponse(responseCode = "409", description = "A change is in progress on the organisation or the member, try again later")
    public void revoke(@PathParam("org") @Slug String org, @PathParam("userId") String userId) {
        ownerService.revoke(currentRealm.name(), org, userId);
    }
}
