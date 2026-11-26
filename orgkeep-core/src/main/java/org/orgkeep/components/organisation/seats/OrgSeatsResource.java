package org.orgkeep.components.organisation.seats;

import org.orgkeep.api.SeatService;
import org.orgkeep.api.model.Seats;
import org.orgkeep.api.model.Slug;
import org.orgkeep.api.model.UpdateSeatsRequest;
import org.orgkeep.web.CurrentRealm;
import jakarta.annotation.security.RolesAllowed;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import lombok.RequiredArgsConstructor;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponseSchema;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Seat limits of an organisation, set by the realm owner only: an org admin or an owner can read them
 * (see SeatsResource) but never change them.
 * <p>
 * Sub path of /admin/organisations declared in its own class, like OrgOwnersResource: a class at
 * /admin/organisations/{org} would also capture GET /admin/organisations/{org}.
 */
@Path("/admin/organisations/{org}/seats")
@RolesAllowed("realm-owner")
@Tag(name = "Organisation seats", description = "Seat limits of an organisation, set by the realm owner")
@RequiredArgsConstructor
public class OrgSeatsResource {
    private final SeatService seatService;
    private final CurrentRealm currentRealm;

    @PUT
    @Operation(summary = "Set the seat limits of the organisation",
            description = "Replaces both limits: a null value (or absent) removes the corresponding limit (no "
                    + "limit). 0 is allowed and freezes the organisation for that kind (no new member, or no new "
                    + "service account). Lowering a limit below the current count removes nobody: only new "
                    + "additions are refused until the count drops below it.")
    @APIResponseSchema(value = Seats.class, responseCode = "200", responseDescription = "The organisation's seats, with the new limits")
    @APIResponse(responseCode = "400", description = "A negative value, or the body is missing")
    @APIResponse(responseCode = "404", description = "Unknown organisation")
    @APIResponse(responseCode = "409", description = "A change is in progress on the organisation, try again later")
    public Seats update(@PathParam("org") @Slug String org, @Valid @NotNull UpdateSeatsRequest request) {
        return seatService.update(currentRealm.name(), org, request);
    }
}
