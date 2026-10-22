package io.orgkeep.components.realm.login;

import io.orgkeep.api.LoginClientService;
import io.orgkeep.api.exceptions.InvalidRequestException;
import io.orgkeep.api.exceptions.LoginClientNotFoundException;
import io.orgkeep.api.model.LightweightTokens;
import io.orgkeep.api.model.LoginClient;
import io.orgkeep.api.model.UpdateLoginClientRequest;
import io.orgkeep.components.catalog.CatalogSetup;
import io.orgkeep.config.KeycloakUrls;
import io.orgkeep.config.OrgkeepConfig;
import io.orgkeep.repository.keycloak.KeycloakClientScopes;
import io.orgkeep.repository.keycloak.KeycloakClients;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.representations.idm.ClientRepresentation;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Slf4j
@ApplicationScoped
@RequiredArgsConstructor
public class LoginClientServiceImpl implements LoginClientService {
    private final KeycloakClients clients;
    private final KeycloakClientScopes scopes;
    private final CatalogSetup catalogSetup;
    private final LoginClientMapper loginClientMapper;
    private final KeycloakUrls keycloakUrls;
    private final OrgkeepConfig config;

    @Override
    public LoginClient get(String realm) {
        String clientUuid = loginClientUuid(realm);
        // the console is open to the realm owner in ON_PREM only (realm-owner includes realm-management/realm-admin)
        String consoleUrl = config.mode() == OrgkeepConfig.Mode.ON_PREM ? keycloakUrls.console(realm) : null;
        return loginClientMapper.toLoginClient(clients.get(realm, clientUuid), keycloakUrls.issuer(realm), consoleUrl,
                defaultCatalogs(realm, clientUuid));
    }

    @Override
    public LoginClient update(String realm, UpdateLoginClientRequest request) {
        String clientUuid = loginClientUuid(realm);
        // checked before anything is changed
        Map<String, String> catalogScopeIds = catalogSetup.catalogScopeIds(realm);
        if (request.defaultCatalogs() != null) {
            List<String> unknown = request.defaultCatalogs().stream()
                    .filter(name -> !catalogScopeIds.containsKey(name))
                    .toList();
            if (!unknown.isEmpty()) {
                throw new InvalidRequestException("Not catalogs of realm '%s': %s".formatted(realm, unknown));
            }
        }

        // read, modify, write back: the admin API replaces the client with what it receives
        ClientRepresentation client = clients.get(realm, clientUuid);
        loginClientMapper.update(request, client);
        clients.update(realm, clientUuid, client);
        if (request.defaultCatalogs() != null) {
            catalogSetup.setDefaultCatalogs(realm, clientUuid, catalogScopeIds, request.defaultCatalogs());
        }
        if (request.lightweightTokens() != null) {
            setMarker(realm, clientUuid, request.lightweightTokens());
        }

        log.info("Login client of realm '{}' updated: redirect URIs {}, web origins {}, default catalogs {}, lightweight tokens {}",
                realm, request.redirectUris(), request.webOrigins(), request.defaultCatalogs(), request.lightweightTokens());
        return get(realm);
    }

    /**
     * The scope of the marker follows the lightweight mode: a default scope of the client when it is on, removed when
     * it is off (a full token must not carry the marker, the APIs would introspect it for nothing).
     */
    private void setMarker(String realm, String clientUuid, boolean lightweight) {
        Optional<String> existing = scopes.findId(realm, LightweightTokens.MARKER_CLAIM);
        if (!lightweight) {
            existing.ifPresent(markerScopeId -> clients.removeScope(realm, clientUuid, markerScopeId));
            return;
        }
        String markerScopeId = existing
                // created the first time the mode is turned on, read back if a concurrent request just created it
                .or(() -> scopes.create(realm, loginClientMapper.toMarkerScope()))
                .or(() -> scopes.findId(realm, LightweightTokens.MARKER_CLAIM))
                .orElseThrow(() -> new IllegalStateException("Client scope '%s' not found in realm '%s'"
                        .formatted(LightweightTokens.MARKER_CLAIM, realm)));
        clients.makeDefaultScope(realm, clientUuid, markerScopeId);
    }

    /** The default client scopes of the client that are catalogs. */
    private List<String> defaultCatalogs(String realm, String clientUuid) {
        Set<String> defaultScopeIds = clients.defaultScopeIds(realm, clientUuid);
        return catalogSetup.catalogScopeIds(realm).entrySet().stream()
                .filter(catalog -> defaultScopeIds.contains(catalog.getValue()))
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
    }

    /** The login client is named after the realm (see RealmProvisioner.ensureDefaults). */
    private String loginClientUuid(String realm) {
        return clients.findUuid(realm, realm).orElseThrow(() -> new LoginClientNotFoundException(realm));
    }
}
