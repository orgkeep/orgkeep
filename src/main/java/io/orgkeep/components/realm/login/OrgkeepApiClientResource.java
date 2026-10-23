package io.orgkeep.components.realm.login;

import io.orgkeep.api.LoginClientService;
import io.orgkeep.api.model.OrgkeepApiClient;
import io.orgkeep.api.model.UpdateOrgkeepApiClientRequest;
import io.orgkeep.web.CurrentRealm;
import jakarta.annotation.security.RolesAllowed;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import lombok.RequiredArgsConstructor;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponseSchema;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * The client orgkeep-api, managed by the realm owner: the login client of the screens an integrator builds on the
 * API of Orgkeep. Only its URIs change, with the same rules as the login client of the realm.
 */
@Path("/admin/orgkeep-api-client")
@RolesAllowed("realm-owner")
@Tag(name = "Orgkeep API client", description = "Client to call the API of Orgkeep with, from the screens of an integrator")
@RequiredArgsConstructor
public class OrgkeepApiClientResource {
    private final LoginClientService loginClientService;
    private final CurrentRealm currentRealm;

    @GET
    @Operation(summary = "Get the client of the API of Orgkeep")
    @APIResponseSchema(value = OrgkeepApiClient.class, responseCode = "200", responseDescription = "The client")
    @APIResponse(responseCode = "404", description = "The realm has no such client")
    public OrgkeepApiClient get(){
        return loginClientService.getOrgkeepApiClient(currentRealm.name());
    }

    @PUT
    @Operation(summary = "Update the redirect URIs and web origins of the client of the API of Orgkeep",
            description = "Replaces the three lists, with the rules of the login client: https URLs only (http on "
                    + "localhost only), and a wildcard only as the last path segment (https://app.example.com/*).")
    @APIResponseSchema(value = OrgkeepApiClient.class, responseCode = "200", responseDescription = "Client updated")
    @APIResponse(responseCode = "400", description = "Invalid or unsafe URI")
    @APIResponse(responseCode = "404", description = "The realm has no such client")
    public OrgkeepApiClient update(@Valid @NotNull UpdateOrgkeepApiClientRequest request){
        return loginClientService.updateOrgkeepApiClient(currentRealm.name(), request);
    }
}
