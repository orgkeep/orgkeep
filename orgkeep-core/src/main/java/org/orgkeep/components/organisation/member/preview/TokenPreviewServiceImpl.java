package org.orgkeep.components.organisation.member.preview;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.orgkeep.api.TokenPreviewService;
import org.orgkeep.api.exceptions.InvalidRequestException;
import org.orgkeep.api.exceptions.LoginClientNotFoundException;
import org.orgkeep.api.exceptions.MemberNotFoundException;
import org.orgkeep.api.model.LightweightTokens;
import org.orgkeep.api.model.TokenPreview;
import org.orgkeep.components.catalog.CatalogSetup;
import org.orgkeep.repository.keycloak.KeycloakClientScopes;
import org.orgkeep.repository.keycloak.KeycloakClients;
import org.orgkeep.repository.keycloak.KeycloakOrgs;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.RequiredArgsConstructor;
import org.keycloak.representations.AccessToken;
import org.keycloak.representations.idm.ClientRepresentation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
@RequiredArgsConstructor
public class TokenPreviewServiceImpl implements TokenPreviewService {
    private final KeycloakOrgs orgs;
    private final KeycloakClients clients;
    private final KeycloakClientScopes scopes;
    private final CatalogSetup catalogSetup;
    // the ObjectMapper of Quarkus (quarkus-jackson), the one the REST layer uses
    private final ObjectMapper objectMapper;

    @Override
    public TokenPreview preview(String realm, String orgName, String userId, List<String> catalogs) {
        String orgId = orgs.getByName(realm, orgName).getId();
        if (!orgs.isMember(realm, orgId, userId)) {
            throw new MemberNotFoundException(orgName, userId);
        }
        // the names go into the scope parameter: only real catalogs, never another client scope of the realm
        Set<String> realmCatalogs = catalogSetup.catalogScopeIds(realm).keySet();
        List<String> unknown = catalogs.stream().filter(catalog -> !realmCatalogs.contains(catalog)).distinct().toList();
        if (!unknown.isEmpty()) {
            throw new InvalidRequestException("Not catalogs of the realm: %s".formatted(unknown));
        }

        // the login client (clientId = realm name): the tokens the applications of the realm owner get
        String loginClientUuid = clients.findUuid(realm, realm)
                .orElseThrow(() -> new LoginClientNotFoundException(realm));
        StringBuilder scope = new StringBuilder("openid");
        catalogs.stream().distinct().forEach(catalog -> scope.append(' ').append(catalog));
        ClientRepresentation loginClient = clients.get(realm, loginClientUuid);
        boolean lightweight = loginClient.getAttributes() != null
                && Boolean.parseBoolean(loginClient.getAttributes().get(LightweightTokens.CLIENT_ATTRIBUTE));

        // Keycloak applies the lightweight mode of the client: a lightweight token in lightweight mode
        Map<String, Object> token = claims(clients.exampleAccessToken(realm, loginClientUuid, userId, scope.toString()));
        if (!lightweight) {
            // the introspection of a full token returns its claims (checked by LightweightTokenTest)
            return new TokenPreview(false, token, token);
        }
        return new TokenPreview(true, token, introspection(realm, loginClientUuid, userId, scope.toString()));
    }

    /**
     * What the introspection of a lightweight token returns. Keycloak has no "evaluate" for the introspection, and a
     * real introspection needs a real token, so a logged in user. The introspection recomputes the full token of the
     * login client: it is evaluated on a twin of the login client, never in lightweight mode, aligned with it first
     * (its scopes, without the marker, and its scope mappings: what decides the content of the token). Aligned at each
     * preview rather than at each change of the login client: nothing else to keep in step, and a change made in the
     * Keycloak console is caught too. The login client has no protocol mapper of its own to copy.
     */
    private Map<String, Object> introspection(String realm, String loginClientUuid, String userId, String scope) {
        String twinUuid = clients.findUuid(realm, TokenPreview.CLIENT_ID)
                // read back if a concurrent preview just created it
                .or(() -> clients.create(realm, twin()))
                .or(() -> clients.findUuid(realm, TokenPreview.CLIENT_ID))
                .orElseThrow(() -> new IllegalStateException("Client '%s' not found in realm '%s'"
                        .formatted(TokenPreview.CLIENT_ID, realm)));
        Set<String> marker = scopes.findId(realm, LightweightTokens.MARKER_CLAIM).map(Set::of).orElse(Set.of());
        clients.copyScopes(realm, loginClientUuid, twinUuid, marker);
        clients.copyScopeMappings(realm, loginClientUuid, twinUuid);

        Map<String, Object> claims = new HashMap<>(claims(clients.exampleAccessToken(realm, twinUuid, userId, scope)));
        // the introspection names the client the token was issued to: the login client, not the twin
        claims.put("azp", realm);
        return claims;
    }

    /** The twin of the login client: no flow (nobody gets a token from it), only its scope decides what it shows. */
    private static ClientRepresentation twin() {
        ClientRepresentation client = new ClientRepresentation();
        client.setClientId(TokenPreview.CLIENT_ID);
        client.setName("Token preview");
        client.setDescription("Used by Orgkeep to preview what the introspection of a lightweight token returns");
        client.setEnabled(true);
        client.setPublicClient(true);
        client.setStandardFlowEnabled(false);
        client.setImplicitFlowEnabled(false);
        client.setDirectAccessGrantsEnabled(false);
        client.setServiceAccountsEnabled(false);
        // like the login client: only the roles of its scope
        client.setFullScopeAllowed(false);
        return client;
    }

    /**
     * A plain JSON object for the API: the Keycloak representation stays in the core (the api package does not
     * depend on Keycloak). Its unknown claims (the claims of the catalogs) are kept by Jackson (otherClaims).
     * Without null fields: the representation has one per standard claim (nonce, middle_name...), a real token
     * has none of them, and the preview must look like what the APIs receive.
     */
    private Map<String, Object> claims(AccessToken token) {
        return objectMapper.copy()
                .setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL)
                .convertValue(token, new TypeReference<>() { });
    }
}
