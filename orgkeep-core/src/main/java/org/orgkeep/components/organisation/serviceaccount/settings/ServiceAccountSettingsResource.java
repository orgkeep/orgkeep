package org.orgkeep.components.organisation.serviceaccount.settings;

import jakarta.annotation.security.RolesAllowed;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import lombok.RequiredArgsConstructor;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.orgkeep.api.ServiceAccountSettingsService;
import org.orgkeep.api.model.ServiceAccountSettings;
import org.orgkeep.api.model.Slug;
import org.orgkeep.api.model.UpdateServiceAccountSettingsRequest;
import org.orgkeep.web.CurrentRealm;

/**
 * The settings of one service account, for the realm owner only: under /admin, not under the org path that the org
 * admins administer. Sub path of /admin/organisations declared in its own class, like TokenPreviewResource.
 */
@Path("/admin/organisations/{org}/service-accounts/{sa}/settings")
@RolesAllowed("realm-owner")
@Tag(name = "Service account settings", description = "The mode of the tokens of the service accounts, managed by the realm owner")
@RequiredArgsConstructor
public class ServiceAccountSettingsResource {
    private final ServiceAccountSettingsService settingsService;
    private final CurrentRealm currentRealm;

    @GET
    @Operation(summary = "Get the settings of a service account")
    @APIResponse(responseCode = "400", description = "Invalid name")
    @APIResponse(responseCode = "404", description = "Unknown organisation or service account")
    public ServiceAccountSettings get(@PathParam("org") @Slug String org, @PathParam("sa") @Slug String sa) {
        return settingsService.get(currentRealm.name(), org, sa);
    }

    @PUT
    @Operation(summary = "Change the settings of a service account",
            description = "With lightweightTokens, every API receiving the tokens of this service account gets "
                    + "lightweight tokens (marked \"lightweight\": true): each must introspect them first, with the "
                    + "credentials of its catalog.")
    @APIResponse(responseCode = "400", description = "Invalid name, or lightweightTokens is missing")
    @APIResponse(responseCode = "404", description = "Unknown organisation or service account")
    @APIResponse(responseCode = "409", description = "A change is in progress on the organisation or the service account")
    public ServiceAccountSettings update(@PathParam("org") @Slug String org, @PathParam("sa") @Slug String sa,
                                         @Valid @NotNull UpdateServiceAccountSettingsRequest request) {
        return settingsService.update(currentRealm.name(), org, sa, request);
    }
}
