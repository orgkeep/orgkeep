package io.orgkeep.components.realm;

import io.orgkeep.api.RealmProvisioner;
import io.orgkeep.api.events.UserCreated;
import io.orgkeep.api.exceptions.RealmAlreadyExistsException;
import io.orgkeep.api.model.CreateRealmRequest;
import io.orgkeep.api.model.CreateRealmResponse;
import io.orgkeep.api.model.OrgClients;
import io.orgkeep.api.model.RealmRole;
import io.orgkeep.api.model.SmtpSettings;
import io.orgkeep.repository.keycloak.KeycloakClients;
import io.orgkeep.repository.keycloak.KeycloakOrgs;
import io.orgkeep.repository.keycloak.KeycloakRealms;
import io.orgkeep.repository.keycloak.KeycloakUsers;
import io.orgkeep.components.catalog.CatalogSetup;
import io.vertx.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.RolesRepresentation;
import org.keycloak.representations.idm.UserRepresentation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

@Slf4j
@ApplicationScoped
@RequiredArgsConstructor
public class RealmProvisionerImpl implements RealmProvisioner {
    private final KeycloakRealms realms;
    private final KeycloakUsers users;
    private final KeycloakClients clients;
    private final KeycloakOrgs orgs;
    private final CatalogSetup catalogSetup;
    private final RealmMapper realmMapper;
    private final PasswordGenerator passwordGenerator;
    private final EventBus eventBus;

    @Override
    public CreateRealmResponse create(CreateRealmRequest request) {
        boolean passwordSet = request.adminPassword() != null;
        String password = passwordSet ? request.adminPassword() : passwordGenerator.generate();

        RealmRepresentation realm = realmMapper.toRealmRepresentation(request);
        realm.setRoles(realmRoles());

        UserRepresentation owner = realmMapper.toUserRepresentation(request, password);
        owner.setRealmRoles(Arrays.stream(RealmRole.values())
                .map(RealmRole::getRoleName)
                .toList());
        realm.setUsers(List.of(owner));

        if (!realms.create(realm)) {
            throw new RealmAlreadyExistsException(realm.getRealm());
        }
        log.info("Realm '{}' created with owner {}", realm.getRealm(), request.adminEmail());
        ensureDefaults(realm.getRealm());
        // only with a new realm, not in ensureDefaults: the realm owner may delete it.
        // After the login client, which gets its scope as a default scope
        catalogSetup.ensureDefault(realm.getRealm());

        // the realm and its owner are created in a single call: read back the ids Keycloak generated
        String ownerId = users.findIdByEmail(realm.getRealm(), request.adminEmail())
                .orElseThrow(() -> new IllegalStateException("Owner of realm '%s' not found after creation".formatted(realm.getRealm())));
        eventBus.publish(UserCreated.ADDRESS, new UserCreated(realm.getRealm(), null, ownerId, passwordSet));
        return new CreateRealmResponse(realms.id(realm.getRealm()), realm.getRealm(), ownerId);
    }

    @Override
    public void configureSmtp(String name, SmtpSettings smtp) {
        realms.setSmtpServer(name, realmMapper.toSmtpServer(smtp));
        log.info("SMTP server {}:{} set on realm '{}'", smtp.host(), smtp.port(), name);
    }

    @Override
    public void ensureDefaults(String name) {
        // the scopes of the catalogs are realm optional scopes: a new login client gets them, and a catalog created
        // afterwards adds its scope to the existing login client
        Optional<String> existing = clients.findUuid(name, name);
        String loginClientUuid = existing
                .or(() -> clients.create(name, realmMapper.toLoginClient(name)))
                .or(() -> clients.findUuid(name, name))
                .orElseThrow(() -> new IllegalStateException("Login client not found in realm '%s'".formatted(name)));
        restrictLoginClientScope(name, loginClientUuid, existing.isEmpty());
    }

    /**
     * fullScopeAllowed=false: the roles of a catalog only reach a token of the login client when the scope of the
     * catalog is in it. Every other role the core API reads must then be mapped explicitly: the realm roles
     * (realm_access) and the roles of every org (resource_access.org-{org}, mapped when an org is created).
     *
     * @param created the login client was just created: like a client switching to fullScopeAllowed=false, it has
     *                none of the org mappings yet
     */
    private void restrictLoginClientScope(String realm, String loginClientUuid, boolean created) {
        List<String> realmRoles = new ArrayList<>(Arrays.stream(RealmRole.values()).map(RealmRole::getRoleName).toList());
        // composite of offline_access, uma_authorization and the roles of the account client
        realmRoles.add(realms.defaultRoleName(realm));
        clients.addRealmRolesToScope(realm, loginClientUuid, realmRoles);

        ClientRepresentation client = clients.get(realm, loginClientUuid);
        if (created || client.isFullScopeAllowed()) {
            // once, not at every start: one call per org
            orgs.names(realm).forEach(org -> clients.findUuid(realm, OrgClients.clientId(org))
                    .ifPresent(orgClientUuid -> clients.addClientRolesToScope(realm, loginClientUuid, orgClientUuid)));
        }
        if (client.isFullScopeAllowed()) {
            client.setFullScopeAllowed(false);
            clients.update(realm, loginClientUuid, client);
            log.info("Login client of realm '{}' restricted to the roles of its scope (fullScopeAllowed=false)", realm);
        }
    }

    @Override
    public void delete(String name){
        realms.delete(name);
    }

    @Override
    public boolean exists(String name) {
        return realms.exists(name);
    }

    private static RolesRepresentation realmRoles() {
        List<RoleRepresentation> roles = Arrays.stream(RealmRole.values())
                .map(r -> new RoleRepresentation(r.getRoleName(),r.name(),false))
                .toList();
        RolesRepresentation representation = new RolesRepresentation();
        representation.setRealm(roles);
        return representation;
    }
}
