package org.orgkeep.components.organisation.seats;

import org.orgkeep.api.SeatService;
import org.orgkeep.api.model.Seats;
import org.orgkeep.api.model.Slug;
import org.orgkeep.web.CurrentRealm;
import org.orgkeep.web.security.OrgPermissions;
import io.quarkus.security.PermissionsAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import lombok.RequiredArgsConstructor;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponseSchema;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Seats of an organisation, read by its own org admins (org-admin, so org-owner too) and by the realm owner.
 * Changing the limits is realm owner only, see OrgSeatsResource.
 * <p>
 * Every method has an "org" path parameter: OrgPermissions checks that the caller administers that org.
 */
@Path("/organisations/{org}/seats")
@PermissionsAllowed(OrgPermissions.ADMINISTER_ORG)
@Tag(name = "Organisation seats", description = "Seats of an organisation, read by its org admins")
// added automatically for @RolesAllowed, declared explicitly here for @PermissionsAllowed
// ("SecurityScheme" is the default name of the scheme set by quarkus.smallrye-openapi.security-scheme)
@SecurityRequirement(name = "SecurityScheme")
@RequiredArgsConstructor
public class SeatsResource {
    private final SeatService seatService;
    private final CurrentRealm currentRealm;

    @GET
    @Operation(summary = "Get the seats of the organisation",
            description = "Used and maximum (null = no limit) for members and for service accounts separately. "
                    + "Every member counts whatever their state (enabled, disabled, pending invitation), owners "
                    + "included; a service account never takes a member seat and a member never takes a "
                    + "service-account slot.")
    @APIResponseSchema(value = Seats.class, responseCode = "200", responseDescription = "The organisation's seats")
    @APIResponse(responseCode = "401", description = "Not authenticated")
    @APIResponse(responseCode = "403", description = "Not an admin of this organisation, nor the realm owner")
    @APIResponse(responseCode = "404", description = "Unknown organisation")
    public Seats get(@PathParam("org") @Slug String org) {
        return seatService.get(currentRealm.name(), org);
    }
}
