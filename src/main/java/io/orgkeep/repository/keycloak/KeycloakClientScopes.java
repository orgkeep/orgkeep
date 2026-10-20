package io.orgkeep.repository.keycloak;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.Response;
import lombok.RequiredArgsConstructor;
import org.keycloak.admin.client.CreatedResponseUtil;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.idm.ClientScopeRepresentation;
import org.keycloak.representations.idm.ProtocolMapperRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Keycloak client scopes and their protocol mappers. Client scopes are addressed by id, or by name in {@link #findId}.
 */
@ApplicationScoped
@RequiredArgsConstructor
public class KeycloakClientScopes {
    private final Keycloak keycloak;

    public Optional<String> findId(String realm, String name) {
        return find(realm, name).map(ClientScopeRepresentation::getId);
    }

    /** Full representation of the scope, attributes included. */
    public Optional<ClientScopeRepresentation> find(String realm, String name) {
        // no search by name in the admin API: client scopes are few, filter them all
        return findAll(realm).stream()
                .filter(scope -> name.equals(scope.getName()))
                .findFirst();
    }

    /** All the scopes of the realm, full representations. */
    public List<ClientScopeRepresentation> findAll(String realm) {
        return keycloak.realm(realm).clientScopes().findAll();
    }

    /** Replaces the scope with this representation, read with {@link #find} and modified. */
    public void update(String realm, String scopeId, ClientScopeRepresentation scope) {
        keycloak.realm(realm).clientScopes().get(scopeId).update(scope);
    }

    /** @return the id of the new client scope, empty if one with this name already exists */
    public Optional<String> create(String realm, ClientScopeRepresentation scope) {
        try(Response response = keycloak.realm(realm).clientScopes().create(scope)){
            if(KeycloakErrors.isConflict(response)){
                return Optional.empty();
            }
            return Optional.of(CreatedResponseUtil.getCreatedId(response));
        }
    }

    /** Makes the scope a default scope of the clients created afterwards. Idempotent. */
    public void addRealmDefault(String realm, String scopeId) {
        var realmResource = keycloak.realm(realm);
        boolean present = realmResource.getDefaultDefaultClientScopes().stream()
                .anyMatch(scope -> scopeId.equals(scope.getId()));
        if (!present) {
            realmResource.addDefaultDefaultClientScope(scopeId);
        }
    }

    /** Removes the scope from the default scopes of the clients created afterwards. Idempotent. */
    public void removeRealmDefault(String realm, String scopeId) {
        var realmResource = keycloak.realm(realm);
        boolean present = realmResource.getDefaultDefaultClientScopes().stream()
                .anyMatch(scope -> scopeId.equals(scope.getId()));
        if (present) {
            realmResource.removeDefaultDefaultClientScope(scopeId);
        }
    }

    /** Makes the scope an optional scope of the clients created afterwards. Idempotent. */
    public void addRealmOptional(String realm, String scopeId) {
        var realmResource = keycloak.realm(realm);
        boolean present = realmResource.getDefaultOptionalClientScopes().stream()
                .anyMatch(scope -> scopeId.equals(scope.getId()));
        if (!present) {
            realmResource.addDefaultOptionalClientScope(scopeId);
        }
    }

    /** Removes the scope from the optional scopes of the clients created afterwards. Idempotent. */
    public void removeRealmOptional(String realm, String scopeId) {
        var realmResource = keycloak.realm(realm);
        boolean present = realmResource.getDefaultOptionalClientScopes().stream()
                .anyMatch(scope -> scopeId.equals(scope.getId()));
        if (present) {
            realmResource.removeDefaultOptionalClientScope(scopeId);
        }
    }

    /**
     * Scope mappings of the scope for these roles of a client: a client with fullScopeAllowed=false only gets these
     * roles in its tokens when the scope is in the token. Keycloak removes the mapping when the role is deleted.
     */
    public void addClientRolesToScope(String realm, String scopeId, String clientUuid, Collection<String> roleNames) {
        var roles = keycloak.realm(realm).clients().get(clientUuid).roles();
        List<RoleRepresentation> representations = roleNames.stream()
                .map(name -> roles.get(name).toRepresentation())
                .toList();
        keycloak.realm(realm).clientScopes().get(scopeId).getScopeMappings().clientLevel(clientUuid).add(representations);
    }

    /** Names of the roles of the client mapped by the scope. */
    public Set<String> clientRolesInScope(String realm, String scopeId, String clientUuid) {
        return keycloak.realm(realm).clientScopes().get(scopeId).getScopeMappings().clientLevel(clientUuid).listAll().stream()
                .map(RoleRepresentation::getName)
                .collect(Collectors.toSet());
    }

    /** Deletes the scope, Keycloak also removes it from the clients having it. */
    public void delete(String realm, String scopeId) {
        keycloak.realm(realm).clientScopes().get(scopeId).remove();
    }

    /** @return false if the scope already has a mapper with this name */
    public boolean addMapper(String realm, String scopeId, ProtocolMapperRepresentation mapper) {
        try(Response response = keycloak.realm(realm).clientScopes().get(scopeId).getProtocolMappers().createMapper(mapper)){
            if(KeycloakErrors.isConflict(response)){
                return false;
            }
            KeycloakErrors.requireSuccess(response, "Creating mapper " + mapper.getName());
            return true;
        }
    }

    /** Removes the mappers of the scope having one of these names, ignores the others. */
    public void removeMappers(String realm, String scopeId, Collection<String> names) {
        var mappers = keycloak.realm(realm).clientScopes().get(scopeId).getProtocolMappers();
        mappers.getMappers().stream()
                .filter(mapper -> names.contains(mapper.getName()))
                .forEach(mapper -> mappers.delete(mapper.getId()));
    }
}
