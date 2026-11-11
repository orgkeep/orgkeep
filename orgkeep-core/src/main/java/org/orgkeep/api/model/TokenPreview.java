package org.orgkeep.api.model;

import java.util.Map;

/**
 * The token of a member as the login client of the realm would issue it, and what the APIs of the realm owner read
 * by introspection. Claims only, as JSON objects and values: never a signed token.
 *
 * @param lightweight   the login client issues lightweight tokens (see {@link LightweightTokens})
 * @param token         the claims of the access token an application receives: lightweight or full
 * @param introspection the claims an API gets back by introspecting this token (RFC 7662, without "active" and the
 *                      fields the introspection adds). Without lightweight tokens, the same claims as the token
 */
public record TokenPreview(
    boolean lightweight,
    Map<String, Object> token,
    Map<String, Object> introspection){

    /**
     * clientId of a hidden client of the realm, kept like the login client but never in lightweight mode: Keycloak
     * computes on it what the introspection of a lightweight token returns (see TokenPreviewService). No flow:
     * nobody gets a token from it.
     */
    public static final String CLIENT_ID = "token-preview";
}
