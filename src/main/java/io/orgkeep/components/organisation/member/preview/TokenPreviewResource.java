package io.orgkeep.components.organisation.member.preview;

import io.orgkeep.api.TokenPreviewService;
import io.orgkeep.api.model.TokenPreview;
import io.orgkeep.web.CurrentRealm;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import lombok.RequiredArgsConstructor;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;

/**
 * The token a member of an organisation would get, for the realm owner only: it checks what its APIs receive.
 * The org admins and the users do not need the shape of a token.
 * <p>
 * Sub path of /admin/organisations declared in its own class, like OrgProductsResource.
 */
@Path("/admin/organisations/{org}/members/{userId}/token-preview")
@RolesAllowed("realm-owner")
@Tag(name = "Token preview", description = "What the APIs of the realm owner receive in the tokens of a member")
@RequiredArgsConstructor
public class TokenPreviewResource {
    private final TokenPreviewService tokenPreviewService;
    private final CurrentRealm currentRealm;

    // ?catalog=prod&catalog=sandbox: a repeated query parameter gives the list, absent gives an empty list
    @GET
    @Operation(summary = "Preview the access token of a member",
            description = "The claims the login client of the realm would put in the access token of the member, for an "
                    + "application asking for scope=openid plus these catalogs, and the claims an API gets back by "
                    + "introspecting it (the same, unless the login client issues lightweight tokens). Computed by "
                    + "Keycloak without the member logging in; the claims only, never a signed token.")
    @APIResponse(responseCode = "200", description = "The claims of the token and of its introspection")
    @APIResponse(responseCode = "400", description = "A name that is not a catalog of the realm")
    @APIResponse(responseCode = "404", description = "Unknown organisation, or the user is not one of its members")
    public TokenPreview preview(@PathParam("org") String org, @PathParam("userId") String userId,
                                       @QueryParam("catalog") List<String> catalogs){
        return tokenPreviewService.preview(currentRealm.name(), org, userId, catalogs == null ? List.of() : catalogs);
    }
}
