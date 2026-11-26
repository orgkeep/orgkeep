package org.orgkeep.components.organisation.suspension;

import io.quarkus.security.UnauthorizedException;
import jakarta.annotation.security.RolesAllowed;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import lombok.RequiredArgsConstructor;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponseSchema;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.orgkeep.api.AccountSuspensionService;
import org.orgkeep.api.model.OrgMember;
import org.orgkeep.api.model.ServiceAccount;
import org.orgkeep.api.model.Slug;
import org.orgkeep.api.model.SuspendRequest;
import org.orgkeep.web.CurrentRealm;

/**
 * Suspensions set and lifted by the realm owner only: an org admin, whatever the organisation, gets 403. A class of
 * its own under /admin/organisations, like OrgOwnersResource: a class at /admin/organisations/{org} would also
 * capture GET /admin/organisations/{org}.
 */
@Path("/admin/organisations/{org}")
@RolesAllowed("realm-owner")
@Tag(name = "Account suspension", description = "Suspensions set and lifted by the realm owner")
@RequiredArgsConstructor
public class SuspensionsResource {
    private final AccountSuspensionService suspensionService;
    private final CurrentRealm currentRealm;
    // a client proxy of a request scoped bean: each call reads the token of the current request
    private final JsonWebToken jwt;

    @PUT
    @Path("/members/{userId}/suspension")
    @Operation(summary = "Suspend a member",
            description = "Disables the account and ends its sessions; owners can be suspended, privileged "
                    + "accounts cannot. An org admin cannot enable a suspended account (409) and can still disable "
                    + "it. Suspending an already suspended account replaces the reason and keeps the original date, "
                    + "suspender and the organisation's choice for when it is lifted.")
    @APIResponseSchema(value = OrgMember.class, responseCode = "200", responseDescription = "The member, suspended")
    @APIResponse(responseCode = "400", description = "Invalid body")
    @APIResponse(responseCode = "401", description = "Not authenticated, or the token has no subject")
    @APIResponse(responseCode = "404", description = "Unknown organisation, or the user is not a person member of this organisation")
    @APIResponse(responseCode = "409", description = "The member is privileged, or a change is in progress (try again later)")
    public OrgMember suspendMember(@PathParam("org") @Slug String org, @PathParam("userId") String userId,
                                   @Valid @NotNull SuspendRequest request) {
        return suspensionService.suspendMember(currentRealm.name(), org, userId, request.reason(), callerId());
    }

    @DELETE
    @Path("/members/{userId}/suspension")
    @Operation(summary = "Lift the suspension of a member",
            description = "Idempotent: nothing changes when the member is not suspended. Enables the member again "
                    + "only if the organisation is enabled, the member is not listed as disabled by it, and its "
                    + "organisation had not disabled it before the suspension; otherwise it stays disabled.")
    @APIResponseSchema(value = OrgMember.class, responseCode = "200", responseDescription = "The member, up to date")
    @APIResponse(responseCode = "404", description = "Unknown organisation, or the user is not a person member of this organisation")
    @APIResponse(responseCode = "409", description = "A change is in progress, try again later")
    public OrgMember liftMember(@PathParam("org") @Slug String org, @PathParam("userId") String userId) {
        return suspensionService.liftMember(currentRealm.name(), org, userId);
    }

    @PUT
    @Path("/service-accounts/{sa}/suspension")
    @Operation(summary = "Suspend a service account", description = "Same rules as suspending a member.")
    @APIResponseSchema(value = ServiceAccount.class, responseCode = "200", responseDescription = "The service account, suspended")
    @APIResponse(responseCode = "400", description = "Invalid name or body")
    @APIResponse(responseCode = "401", description = "Not authenticated, or the token has no subject")
    @APIResponse(responseCode = "404", description = "Unknown organisation or service account")
    @APIResponse(responseCode = "409", description = "A change is in progress, try again later")
    public ServiceAccount suspendServiceAccount(@PathParam("org") @Slug String org, @PathParam("sa") @Slug String sa,
                                                @Valid @NotNull SuspendRequest request) {
        return suspensionService.suspendServiceAccount(currentRealm.name(), org, sa, request.reason(), callerId());
    }

    @DELETE
    @Path("/service-accounts/{sa}/suspension")
    @Operation(summary = "Lift the suspension of a service account", description = "Same rules as lifting a member's.")
    @APIResponseSchema(value = ServiceAccount.class, responseCode = "200", responseDescription = "The service account, up to date")
    @APIResponse(responseCode = "400", description = "Invalid name")
    @APIResponse(responseCode = "404", description = "Unknown organisation or service account")
    @APIResponse(responseCode = "409", description = "A change is in progress, try again later")
    public ServiceAccount liftServiceAccount(@PathParam("org") @Slug String org, @PathParam("sa") @Slug String sa) {
        return suspensionService.liftServiceAccount(currentRealm.name(), org, sa);
    }

    /**
     * The injected JsonWebToken is the token of the call, a request scoped proxy: read here, on the thread of the
     * request, never kept in a field (see OrgAdminResource.setEnabled).
     */
    private String callerId() {
        String callerId = jwt.getSubject();
        if (callerId == null || callerId.isBlank()) {
            throw new UnauthorizedException("The token has no subject");
        }
        return callerId;
    }
}
