package org.orgkeep.components.catalog.role;

import org.orgkeep.api.CatalogRoleService;
import org.orgkeep.api.exceptions.CatalogRoleNotFoundException;
import org.orgkeep.api.exceptions.OperationNotAllowedException;
import org.orgkeep.api.exceptions.RoleNameTakenException;
import org.orgkeep.api.exceptions.UserNotFoundException;
import org.orgkeep.api.model.CatalogRole;
import org.orgkeep.api.model.CreateCatalogRoleRequest;
import org.orgkeep.api.model.RoleHolder;
import org.orgkeep.api.model.UpdateCatalogRoleRequest;
import org.orgkeep.components.catalog.CatalogSetup;
import org.orgkeep.components.organisation.usergroup.team.TeamAlignment;
import org.orgkeep.repository.keycloak.KeycloakClientScopes;
import org.orgkeep.repository.keycloak.KeycloakClients;
import org.orgkeep.repository.keycloak.KeycloakOrgs;
import org.orgkeep.repository.keycloak.KeycloakUsers;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.representations.idm.RoleRepresentation;

import java.util.Comparator;
import java.util.List;

@Slf4j
@ApplicationScoped
@RequiredArgsConstructor
public class CatalogRoleServiceImpl implements CatalogRoleService {
    private final KeycloakClients clients;
    private final KeycloakClientScopes scopes;
    private final KeycloakUsers users;
    private final KeycloakOrgs orgs;
    private final CatalogSetup catalogSetup;
    private final TeamAlignment teamAlignment;
    private final CatalogRoleMapper catalogRoleMapper;

    @Override
    public CatalogRole create(String realm, String catalog, CreateCatalogRoleRequest request) {
        CatalogSetup.Ids ids = catalogSetup.get(realm, catalog);
        RoleRepresentation role = catalogRoleMapper.toRoleRepresentation(
                request.name(), request.displayName(), request.description(), request.orgGrantable());
        if (!clients.createRole(realm, ids.clientUuid(), role)) {
            throw new RoleNameTakenException(catalog, request.name());
        }
        try {
            // the requesting clients have fullScopeAllowed=false: the role only reaches a token through this mapping
            scopes.addClientRolesToScope(realm, ids.scopeId(), ids.clientUuid(), List.of(request.name()));
        } catch (RuntimeException e) {
            clients.deleteRole(realm, ids.clientUuid(), request.name());
            throw e;
        }
        log.info("Catalog role '{}' created in catalog '{}' of realm '{}'", request.name(), catalog, realm);
        return catalogRoleMapper.toCatalogRole(catalog, role);
    }

    @Override
    public List<CatalogRole> list(String realm, String catalog) {
        CatalogSetup.Ids ids = catalogSetup.get(realm, catalog);
        return clients.listRoles(realm, ids.clientUuid()).stream()
                .filter(catalogRoleMapper::isCatalogRole)
                .map(role -> catalogRoleMapper.toCatalogRole(catalog, role))
                .sorted(Comparator.comparing(CatalogRole::name))
                .toList();
    }

    @Override
    public CatalogRole get(String realm, String catalog, String role) {
        CatalogSetup.Ids ids = catalogSetup.get(realm, catalog);
        return catalogRoleMapper.toCatalogRole(catalog, getRole(realm, catalog, ids, role));
    }

    @Override
    public CatalogRole update(String realm, String catalog, String role, UpdateCatalogRoleRequest request) {
        CatalogSetup.Ids ids = catalogSetup.get(realm, catalog);
        boolean wasGrantable = catalogRoleMapper.toCatalogRole(catalog, getRole(realm, catalog, ids, role)).orgGrantable();
        RoleRepresentation updated = catalogRoleMapper.toRoleRepresentation(
                role, request.displayName(), request.description(), request.orgGrantable());
        clients.updateRole(realm, ids.clientUuid(), role, updated);
        if (wasGrantable && !request.orgGrantable()) {
            // now reserved to the realm owner: the teams no longer give it, the users holding it directly keep it
            teamAlignment.revokeCatalogRoleFromTeams(realm, catalog, role);
        }
        log.info("Catalog role '{}' of catalog '{}' updated in realm '{}'", role, catalog, realm);
        return catalogRoleMapper.toCatalogRole(catalog, updated);
    }

    @Override
    public void delete(String realm, String catalog, String role) {
        CatalogSetup.Ids ids = catalogSetup.get(realm, catalog);
        getRole(realm, catalog, ids, role);
        // Keycloak removes it from the groups and users holding it, and from the scope mappings
        clients.deleteRole(realm, ids.clientUuid(), role);
        log.info("Catalog role '{}' deleted from catalog '{}' of realm '{}'", role, catalog, realm);
    }

    @Override
    public void grant(String realm, String catalog, String role, String userId) {
        CatalogSetup.Ids ids = catalogSetup.get(realm, catalog);
        getRole(realm, catalog, ids, role);
        users.find(realm, userId).orElseThrow(() -> new UserNotFoundException(realm, userId));
        // the catalog roles are for the users of the orgs (and later their service accounts), not for anyone in the realm
        if (orgs.organisationsOf(realm, userId).isEmpty()) {
            throw new OperationNotAllowedException("User '%s' is not a member of an organisation".formatted(userId));
        }
        clients.grantToUser(realm, ids.clientUuid(), userId, role);
        log.info("Catalog role '{}' of catalog '{}' granted to user '{}' in realm '{}'", role, catalog, userId, realm);
    }

    @Override
    public void revoke(String realm, String catalog, String role, String userId) {
        CatalogSetup.Ids ids = catalogSetup.get(realm, catalog);
        getRole(realm, catalog, ids, role);
        users.find(realm, userId).orElseThrow(() -> new UserNotFoundException(realm, userId));
        clients.revokeFromUser(realm, ids.clientUuid(), userId, role);
        log.info("Catalog role '{}' of catalog '{}' revoked from user '{}' in realm '{}'", role, catalog, userId, realm);
    }

    @Override
    public List<RoleHolder> holders(String realm, String catalog, String role) {
        CatalogSetup.Ids ids = catalogSetup.get(realm, catalog);
        getRole(realm, catalog, ids, role);
        return clients.listUsersWithRole(realm, ids.clientUuid(), role).stream()
                .map(catalogRoleMapper::toRoleHolder)
                .sorted(Comparator.comparing(RoleHolder::username))
                .toList();
    }

    /** The role, checked to be a catalog role (not a product of the same client). */
    private RoleRepresentation getRole(String realm, String catalog, CatalogSetup.Ids ids, String role) {
        return clients.findRole(realm, ids.clientUuid(), role)
                .filter(catalogRoleMapper::isCatalogRole)
                .orElseThrow(() -> new CatalogRoleNotFoundException(catalog, role));
    }
}
