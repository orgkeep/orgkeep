package io.orgkeep.components.catalog;

import io.orgkeep.api.CatalogService;
import io.orgkeep.api.exceptions.CatalogAlreadyExistsException;
import io.orgkeep.api.exceptions.CatalogNotFoundException;
import io.orgkeep.api.exceptions.OperationNotAllowedException;
import io.orgkeep.api.model.Catalog;
import io.orgkeep.api.model.CatalogCredentials;
import io.orgkeep.api.model.CatalogRoles;
import io.orgkeep.api.model.CreateCatalogRequest;
import io.orgkeep.api.model.UpdateCatalogRequest;
import io.orgkeep.config.KeycloakUrls;
import io.orgkeep.repository.keycloak.KeycloakClientScopes;
import io.orgkeep.repository.keycloak.KeycloakClients;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.ClientScopeRepresentation;

import java.util.List;
import java.util.Optional;

@Slf4j
@ApplicationScoped
@RequiredArgsConstructor
public class CatalogServiceImpl implements CatalogService {
    private final KeycloakClients clients;
    private final KeycloakClientScopes scopes;
    private final CatalogSetup catalogSetup;
    private final CatalogMapper catalogMapper;
    private final KeycloakUrls keycloakUrls;

    @Override
    public Catalog create(String realm, CreateCatalogRequest request) {
        // the name becomes a clientId and a client scope name, both unique in the realm: an existing client
        // ("account", the login client...) or scope ("basic"...) must not be turned into a catalog
        if (clients.findUuid(realm, request.name()).isPresent() || scopes.findId(realm, request.name()).isPresent()) {
            throw new CatalogAlreadyExistsException(realm, request.name());
        }
        CatalogSetup.Ids ids;
        try {
            ids = catalogSetup.ensure(realm, request);
        } catch (RuntimeException e) {
            // admin API calls are not transactional: a client without its scope must not stay behind
            remove(realm, request.name());
            throw e;
        }
        log.info("Catalog '{}' created in realm '{}'", request.name(), realm);
        return catalogMapper.toCatalog(clients.get(realm, ids.clientUuid()), 0);
    }

    @Override
    public List<Catalog> list(String realm) {
        // the scopes tell the catalogs apart (see Catalogs), then two calls per catalog for its client and its
        // products: a realm has a handful of catalogs
        return scopes.findAll(realm).stream()
                .filter(catalogMapper::isCatalog)
                .map(ClientScopeRepresentation::getName)
                .sorted()
                .map(name -> clients.find(realm, name))
                // a scope left behind by a client deleted in the console
                .flatMap(Optional::stream)
                .map(client -> catalogMapper.toCatalog(client, productCount(realm, client.getId())))
                .toList();
    }

    @Override
    public Catalog get(String realm, String name) {
        ClientRepresentation client = getClient(realm, name);
        return catalogMapper.toCatalog(client, productCount(realm, client.getId()));
    }

    @Override
    public Catalog update(String realm, String name, UpdateCatalogRequest request) {
        // read, modify, write back: the admin API replaces the client with what it receives
        ClientRepresentation client = getClient(realm, name);
        client.setName(request.displayName());
        // Keycloak ignores a null field on update: an empty description removes the current one
        client.setDescription(request.description() == null ? "" : request.description());
        clients.update(realm, client.getId(), client);
        log.info("Catalog '{}' updated in realm '{}'", name, realm);
        return catalogMapper.toCatalog(client, productCount(realm, client.getId()));
    }

    @Override
    public CatalogCredentials regenerateSecret(String realm, String name) {
        ClientRepresentation client = getClient(realm, name);
        String secret = clients.regenerateSecret(realm, client.getId());
        // never log the secret itself
        log.info("Secret of catalog '{}' regenerated in realm '{}'", name, realm);
        return new CatalogCredentials(name, secret, keycloakUrls.introspection(realm));
    }

    @Override
    public void delete(String realm, String name) {
        ClientRepresentation client = getClient(realm, name);
        // its products and roles would go with it, and the orgs and users holding them would silently lose them
        if (clients.countRoles(realm, client.getId()) > 0) {
            throw new OperationNotAllowedException(
                    "Catalog '%s' still has products or roles, delete them first".formatted(name));
        }
        remove(realm, name);
        log.info("Catalog '{}' deleted from realm '{}'", name, realm);
    }

    /** The roles of the client that are products: not their roles, nor the catalog roles. */
    private int productCount(String realm, String clientUuid) {
        return (int) clients.listRoles(realm, clientUuid).stream()
                .filter(role -> CatalogRoles.TYPE_PRODUCT.equals(CatalogRoles.typeOf(role.getAttributes())))
                .count();
    }

    /** The catalog client, not any client of this name: its scope must be marked as the scope of a catalog. */
    private ClientRepresentation getClient(String realm, String name) {
        return scopes.find(realm, name)
                .filter(catalogMapper::isCatalog)
                .flatMap(scope -> clients.find(realm, name))
                .orElseThrow(() -> new CatalogNotFoundException(realm, name));
    }

    /** Deletes what exists of the catalog: the scope first, so that no client is left with the claim of a deleted catalog. */
    private void remove(String realm, String name) {
        scopes.findId(realm, name).ifPresent(scopeId -> {
            scopes.removeRealmDefault(realm, scopeId);
            scopes.removeRealmOptional(realm, scopeId);
            scopes.delete(realm, scopeId);
        });
        clients.findUuid(realm, name).ifPresent(clientUuid -> clients.delete(realm, clientUuid));
    }
}
