package org.orgkeep.components.organisation.serviceaccount.settings;

import jakarta.annotation.security.RolesAllowed;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import lombok.RequiredArgsConstructor;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.orgkeep.api.ServiceAccountSettingsService;
import org.orgkeep.api.model.ServiceAccountSettings;
import org.orgkeep.api.model.UpdateServiceAccountSettingsRequest;
import org.orgkeep.web.CurrentRealm;

/** The default settings of the service accounts created afterwards, for the realm owner only. */
@Path("/admin/service-accounts/settings")
@RolesAllowed("realm-owner")
@Tag(name = "Service account settings", description = "The mode of the tokens of the service accounts, managed by the realm owner")
@RequiredArgsConstructor
public class ServiceAccountDefaultsResource {
    private final ServiceAccountSettingsService settingsService;
    private final CurrentRealm currentRealm;

    @GET
    @Operation(summary = "Get the default settings of the new service accounts")
    public ServiceAccountSettings get() {
        return settingsService.realmDefaults(currentRealm.name());
    }

    @PUT
    @Operation(summary = "Set the default settings of the new service accounts",
            description = "The default of the service accounts created afterwards. The existing ones do not change.")
    @APIResponse(responseCode = "400", description = "The body or lightweightTokens is missing")
    public ServiceAccountSettings update(@Valid @NotNull UpdateServiceAccountSettingsRequest request) {
        return settingsService.updateRealmDefaults(currentRealm.name(), request);
    }
}
