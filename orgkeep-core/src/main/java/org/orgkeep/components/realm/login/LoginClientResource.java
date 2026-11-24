package org.orgkeep.components.realm.login;

import org.orgkeep.api.LoginClientService;
import org.orgkeep.api.model.LoginClient;
import org.orgkeep.api.model.UpdateLoginClientRequest;
import org.orgkeep.web.CurrentRealm;
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
 * Login client of the realm, managed by the realm owner: where the users may be sent back after login and logout,
 * and which browser origins may call Keycloak.
 */
@Path("/admin/login-client")
@RolesAllowed("realm-owner")
@Tag(name = "Login client", description = "Client through which the users of the organisations log in, managed by the realm owner")
@RequiredArgsConstructor
public class LoginClientResource {
    private final LoginClientService loginClientService;
    private final CurrentRealm currentRealm;

    @GET
    @Operation(summary = "Get the login client")
    @APIResponseSchema(value = LoginClient.class, responseCode = "200", responseDescription = "The login client")
    @APIResponse(responseCode = "404", description = "The realm has no login client")
    public LoginClient get(){
        return loginClientService.get(currentRealm.name());
    }

    @PUT
    @Operation(summary = "Update the redirect URIs, web origins and home URL",
            description = "Replaces the three lists. Only https URLs are accepted (http on localhost only), "
                    + "and a wildcard only as the last path segment (https://app.example.com/*). "
                    + "defaultCatalogs, lightweightTokens and homeUrl are optional, absent they do not change "
                    + "(homeUrl: empty string removes it). "
                    + "With lightweightTokens, every API of the realm owner receives lightweight tokens at once "
                    + "(marked \"lightweight\": true): each must introspect them first, with the credentials of its catalog. "
                    + "homeUrl becomes the client's baseUrl: the \"Back to Application\" link on Keycloak's last page "
                    + "after an invitation.")
    @APIResponseSchema(value = LoginClient.class, responseCode = "200", responseDescription = "Login client updated")
    @APIResponse(responseCode = "400", description = "Invalid or unsafe URI, or a default catalog that is not a catalog")
    @APIResponse(responseCode = "404", description = "The realm has no login client")
    public LoginClient update(@Valid @NotNull UpdateLoginClientRequest request){
        return loginClientService.update(currentRealm.name(), request);
    }
}
