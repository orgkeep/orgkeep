package org.orgkeep.components.catalog;

import org.orgkeep.api.exceptions.CatalogNotFoundException;
import org.orgkeep.api.model.Catalogs;
import org.orgkeep.api.model.CreateCatalogRequest;
import org.orgkeep.repository.keycloak.KeycloakClientScopes;
import org.orgkeep.repository.keycloak.KeycloakClients;
import io.quarkus.cache.Cache;
import io.quarkus.cache.CacheName;
import io.quarkus.cache.CaffeineCache;
import io.quarkus.cache.CompositeCacheKey;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.RequiredArgsConstructor;
import org.keycloak.representations.idm.ClientScopeRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Creates the Keycloak objects of a catalog (see {@link Catalogs}) when they are missing, and manages the place of
 * the catalog scopes on the login client. Idempotent: also used to create the default catalog with the realm.
 */
@ApplicationScoped
@RequiredArgsConstructor
public class CatalogSetup {
    /** Cache of the ids of a catalog, keyed by (realm, name): they never change while the catalog exists. */
    static final String CATALOG_IDS_CACHE = "catalog-ids";

    private final KeycloakClients clients;
    private final KeycloakClientScopes scopes;
    private final CatalogMapper catalogMapper;
    // @CacheName also registers the cache name at build time (a bean with no @CacheResult / @CacheInvalidate
    // would otherwise go unregistered); lombok.config copies it onto this constructor parameter
    @CacheName(CATALOG_IDS_CACHE)
    private final Cache catalogIdsCacheBean;

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
     * Cached (realm, name -> ids): the heavy part of the lookup is {@link KeycloakClientScopes#find}, which reads
     * every client scope of the realm with its mappers. A cache hit is verified (the client still exists with this
     * name, a cheap GET by id) before being trusted, and read again otherwise: {@link #evict} keeps the cache
     * correct when Orgkeep deletes the catalog, but the catalog's client can also go while this cache does not
     * notice, e.g. a catalog with products deleted outside the service layer. Not {@code @CacheResult}: nothing
     * runs on a hit, so it cannot verify one, nor only cache a positive result. {@link CatalogNotFoundException} is
     * never served stale: it is never cached ({@code resolve} throws before the {@code put}).
     *
     * @throws CatalogNotFoundException otherwise
     */
    public Ids get(String realm, String name) {
        CaffeineCache cache = catalogIdsCacheBean.as(CaffeineCache.class);
        CompositeCacheKey key = new CompositeCacheKey(realm, name);
        CompletableFuture<Ids> cached = cache.getIfPresent(key);
        if (cached != null) {
            Ids ids = cached.join();
            if (clients.matches(realm, ids.clientUuid(), name)) {
                return ids;
            }
            cache.invalidate(key).await().indefinitely();
        }
        Ids ids = resolve(realm, name);
        cache.put(key, CompletableFuture.completedFuture(ids));
        return ids;
    }

    private Ids resolve(String realm, String name) {
        return scopes.find(realm, name)
                .filter(catalogMapper::isCatalog)
                .flatMap(scope -> clients.findUuid(realm, name).map(clientUuid -> new Ids(clientUuid, scope.getId())))
                .orElseThrow(() -> new CatalogNotFoundException(realm, name));
    }

    /** Evicts the cached ids of the catalog: called when it is deleted. */
    public void evict(String realm, String name) {
        catalogIdsCacheBean.invalidate(new CompositeCacheKey(realm, name)).await().indefinitely();
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
