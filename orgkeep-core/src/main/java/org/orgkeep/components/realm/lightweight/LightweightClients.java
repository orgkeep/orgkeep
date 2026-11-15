package org.orgkeep.components.realm.lightweight;

import jakarta.enterprise.context.ApplicationScoped;
import lombok.RequiredArgsConstructor;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.ClientScopeRepresentation;
import org.keycloak.representations.idm.ProtocolMapperRepresentation;
import org.orgkeep.api.model.LightweightTokens;
import org.orgkeep.repository.keycloak.KeycloakClientScopes;
import org.orgkeep.repository.keycloak.KeycloakClients;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The lightweight mode of a client (see {@link LightweightTokens}): the native attribute of Keycloak, and the scope of
 * the marker as a default scope. Shared by the login client and the service accounts.
 * <p>
 * Write order, so that a lightweight token never goes out without its marker: an API that tells the two kinds of token
 * apart by the marker would see no rights in it (403), while a full token carrying the marker only costs one
 * needless introspection.
 * <ul>
 *   <li>turning on: the marker scope first, then the attribute;</li>
 *   <li>turning off: the attribute first, then the marker scope is removed.</li>
 * </ul>
 */
@ApplicationScoped
@RequiredArgsConstructor
public class LightweightClients {
    private final KeycloakClients clients;
    private final KeycloakClientScopes scopes;

    /** Idempotent: only the differences reach Keycloak, so repeating it repairs a client edited in the console. */
    public void apply(String realm, String clientUuid, boolean lightweight) {
        if (lightweight) {
            clients.makeDefaultScope(realm, clientUuid, markerScopeId(realm));
            setAttribute(realm, clientUuid, true);
        } else {
            setAttribute(realm, clientUuid, false);
            // the scope itself stays in the realm
            scopes.findId(realm, LightweightTokens.MARKER_CLAIM)
                    .ifPresent(markerScopeId -> clients.removeScope(realm, clientUuid, markerScopeId));
        }
    }

    /** The attribute as a boolean, false when absent. */
    public static boolean isLightweight(ClientRepresentation client) {
        return client.getAttributes() != null
                && Boolean.parseBoolean(client.getAttributes().get(LightweightTokens.CLIENT_ATTRIBUTE));
    }

    /** Read, modify, write back: the admin API replaces the client with what it receives. */
    private void setAttribute(String realm, String clientUuid, boolean lightweight) {
        ClientRepresentation client = clients.get(realm, clientUuid);
        Map<String, String> attributes = client.getAttributes() == null ? new HashMap<>() : new HashMap<>(client.getAttributes());
        String value = Boolean.toString(lightweight);
        if (value.equals(attributes.get(LightweightTokens.CLIENT_ATTRIBUTE))) {
            return;
        }
        attributes.put(LightweightTokens.CLIENT_ATTRIBUTE, value);
        client.setAttributes(attributes);
        clients.update(realm, clientUuid, client);
    }

    private String markerScopeId(String realm) {
        return scopes.findId(realm, LightweightTokens.MARKER_CLAIM)
                // created the first time the mode is turned on, read back if a concurrent request just created it
                .or(() -> scopes.create(realm, markerScope()))
                .or(() -> scopes.findId(realm, LightweightTokens.MARKER_CLAIM))
                .orElseThrow(() -> new IllegalStateException("Client scope '%s' not found in realm '%s'"
                        .formatted(LightweightTokens.MARKER_CLAIM, realm)));
    }

    /**
     * The client scope of the marker of the lightweight tokens: a native "Hardcoded claim" mapper, in the lightweight
     * access token only. Neither in the full token (the scope is only on clients in lightweight mode), nor in the
     * introspection, which returns the full content; nor listed in the "scope" claim (white label).
     */
    static ClientScopeRepresentation markerScope() {
        ProtocolMapperRepresentation mapper = new ProtocolMapperRepresentation();
        mapper.setName(LightweightTokens.MARKER_CLAIM);
        mapper.setProtocol("openid-connect");
        mapper.setProtocolMapper("oidc-hardcoded-claim-mapper");
        mapper.setConfig(Map.of(
                "claim.name", LightweightTokens.MARKER_CLAIM,
                "claim.value", "true",
                "jsonType.label", "boolean",
                "access.token.claim", "true",
                "lightweight.claim", "true",
                "introspection.token.claim", "false",
                "id.token.claim", "false",
                "userinfo.token.claim", "false"));

        ClientScopeRepresentation scope = new ClientScopeRepresentation();
        scope.setName(LightweightTokens.MARKER_CLAIM);
        scope.setDescription("Marker of the lightweight access tokens");
        scope.setProtocol("openid-connect");
        scope.setAttributes(Map.of(
                "include.in.token.scope", "false",
                "display.on.consent.screen", "false"));
        scope.setProtocolMappers(List.of(mapper));
        return scope;
    }
}
