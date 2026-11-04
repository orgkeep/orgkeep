package org.orgkeep.api.model;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.List;

import static org.orgkeep.api.model.UpdateLoginClientRequest.REDIRECT_URI;
import static org.orgkeep.api.model.UpdateLoginClientRequest.REDIRECT_URI_MESSAGE;
import static org.orgkeep.api.model.UpdateLoginClientRequest.WEB_ORIGIN;
import static org.orgkeep.api.model.UpdateLoginClientRequest.WEB_ORIGIN_MESSAGE;

/**
 * Replaces the redirect URIs and web origins of the client orgkeep-api: the three lists are required, an empty list
 * removes them all. The same safe values as for the login client (see {@link UpdateLoginClientRequest}): an unsafe
 * redirect URI would let the authorization code leave for a host the realm owner does not control.
 */
public record UpdateOrgkeepApiClientRequest(
    @NotNull
    List<@NotNull @Pattern(regexp = REDIRECT_URI, message = REDIRECT_URI_MESSAGE) String> redirectUris,
    @NotNull
    List<@NotNull @Pattern(regexp = REDIRECT_URI, message = REDIRECT_URI_MESSAGE) String> postLogoutRedirectUris,
    @NotNull
    List<@NotNull @Pattern(regexp = WEB_ORIGIN, message = WEB_ORIGIN_MESSAGE) String> webOrigins){ }
