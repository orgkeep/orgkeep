package org.orgkeep.components.catalog;

import org.orgkeep.api.exceptions.CatalogNotFoundException;
import org.orgkeep.api.model.Catalogs;
import org.orgkeep.api.model.CreateCatalogRequest;
import org.orgkeep.repository.keycloak.KeycloakClientScopes;
import org.orgkeep.repository.keycloak.KeycloakClients;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.RequiredArgsConstructor;
import org.keycloak.representations.idm.ClientScopeRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Creates the Keycloak objects of a catalog (see {@link Catalogs}) when they are missing, and manages the place of
 * the catalog scopes on the login client. Idempotent: also used to create the default catalog with the realm.
 */
@ApplicationScoped
@RequiredArgsConstructor
public class CatalogSetup {
    private final KeycloakClients clients;
    private final KeycloakClientScopes scopes;
    private final CatalogMapper catalogMapper;

    /**
     * @param clientUuid internal id of the catalog client
     * @param scopeId    id of its client scope
     */
    public record Ids(String clientUuid, String scopeId) { }

    /** The catalog created with the realm, a default scope of the login client: a realm owner has nothing to configure. */
    public Ids ensureDefault(String realm) {
        Ids ids = ensure(realm, new CreateCatalogRequest(Catalogs.DEFAULT, "Products", null));
        clients.findUuid(realm, realm)
                .ifPresent(loginClientUuid -> clients.makeDefaultScope(realm, loginClientUuid, ids.scopeId()));
        return ids;
    }

    public Ids ensure(String realm, CreateCatalogRequest request) {
        String name = request.name();
        String clientUuid = clients.findUuid(realm, name)
                // empty if a concurrent request just created it: read it back
                .or(() -> clients.create(realm, catalogMapper.toClient(request)))
                .or(() -> clients.findUuid(realm, name))
                .orElseThrow(() -> new IllegalStateException("Catalog client '%s' not found in realm '%s'"
                        .formatted(name, realm)));

        String scopeId = scopes.findId(realm, name)
                .or(() -> scopes.create(realm, catalogMapper.toScope(request)))
                .or(() -> scopes.findId(realm, name))
                .orElseThrow(() -> new IllegalStateException("Client scope '%s' not found in realm '%s'"
                        .formatted(name, realm)));
        markAsCatalog(realm, name);

        // optional, not default: a token only carries the catalogs its client has by default or the app asks for
        // (scope=<catalog>), so that a user present in several catalogs does not get them all in every token.
        // A scope created by an older version was a realm default
        scopes.removeRealmDefault(realm, scopeId);
        scopes.addRealmOptional(realm, scopeId);
        // the login client is older than the catalogs created afterwards. A catalog already among its defaults
        // (chosen by the realm owner) stays there
        clients.findUuid(realm, realm)
                .filter(loginClientUuid -> !clients.defaultScopeIds(realm, loginClientUuid).contains(scopeId))
                .ifPresent(loginClientUuid -> clients.makeOptionalScope(realm, loginClientUuid, scopeId));

        // the roles of the catalog only reach the tokens through the scope mappings of its scope
        // (the requesting clients have fullScopeAllowed=false): catches up on roles created before them
        List<String> roleNames = clients.listRoles(realm, clientUuid).stream().map(RoleRepresentation::getName).toList();
        if (!roleNames.isEmpty()) {
            scopes.addClientRolesToScope(realm, scopeId, clientUuid, roleNames);
        }
        return new Ids(clientUuid, scopeId);
    }

    /**
     * The ids of an existing catalog: a client whose client scope of the same name is marked as the scope of a catalog.
     *
     * @throws CatalogNotFoundException otherwise
     */
    public Ids get(String realm, String name) {
        return scopes.find(realm, name)
                .filter(catalogMapper::isCatalog)
                .flatMap(scope -> clients.findUuid(realm, name).map(clientUuid -> new Ids(clientUuid, scope.getId())))
                .orElseThrow(() -> new CatalogNotFoundException(realm, name));
    }

    /** The catalogs of the realm: name → id of the client scope. */
    public Map<String, String> catalogScopeIds(String realm) {
        return scopes.findAll(realm).stream()
                .filter(catalogMapper::isCatalog)
                .collect(Collectors.toMap(ClientScopeRepresentation::getName, ClientScopeRepresentation::getId));
    }

    /**
     * Makes these catalogs the default catalog scopes of the client, every other catalog an optional one.
     *
     * @param catalogScopeIds every catalog of the realm, as read by {@link #catalogScopeIds}
     */
    public void setDefaultCatalogs(String realm, String clientUuid, Map<String, String> catalogScopeIds,
                                   Collection<String> defaultCatalogs) {
        catalogScopeIds.forEach((name, scopeId) -> {
            if (defaultCatalogs.contains(name)) {
                clients.makeDefaultScope(realm, clientUuid, scopeId);
            } else {
                clients.makeOptionalScope(realm, clientUuid, scopeId);
            }
        });
    }

    /** A scope created by an older version, before the marker attribute, is listed with the other catalogs. */
    private void markAsCatalog(String realm, String name) {
        scopes.find(realm, name)
                .filter(scope -> !catalogMapper.isCatalog(scope))
                .ifPresent(scope -> {
                    catalogMapper.markAsCatalog(scope);
                    scopes.update(realm, scope.getId(), scope);
                });
    }
}
