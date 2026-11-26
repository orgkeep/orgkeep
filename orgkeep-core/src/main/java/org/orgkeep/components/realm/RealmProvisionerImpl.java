package org.orgkeep.components.realm;

import org.orgkeep.api.RealmProvisioner;
import org.orgkeep.api.events.UserCreated;
import org.orgkeep.api.exceptions.RealmAlreadyExistsException;
import org.orgkeep.api.model.CreateRealmRequest;
import org.orgkeep.api.model.CreateRealmResponse;
import org.orgkeep.api.model.DashboardClientSettings;
import org.orgkeep.api.model.OrgClients;
import org.orgkeep.api.model.OrgkeepApiClient;
import org.orgkeep.api.model.RealmRole;
import org.orgkeep.api.model.SmtpSettings;
import org.orgkeep.api.model.Suspensions;
import org.orgkeep.components.realm.smtp.SmtpMapper;
import org.orgkeep.repository.keycloak.KeycloakClients;
import org.orgkeep.repository.keycloak.KeycloakOrgs;
import org.orgkeep.repository.keycloak.KeycloakRealms;
import org.orgkeep.repository.keycloak.KeycloakUsers;
import org.orgkeep.components.catalog.CatalogSetup;
import io.vertx.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.RolesRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.keycloak.representations.userprofile.config.UPAttribute;
import org.keycloak.representations.userprofile.config.UPAttributePermissions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

@Slf4j
@ApplicationScoped
@RequiredArgsConstructor
public class RealmProvisionerImpl implements RealmProvisioner {
    /** Client created by Keycloak in every realm, holding the roles of the admin console and API. */
    private static final String REALM_MANAGEMENT = "realm-management";
    /** Composite of all the roles of realm-management: full administration of the realm, and of this realm only. */
    private static final String REALM_ADMIN = "realm-admin";

    private final KeycloakRealms realms;
    private final KeycloakUsers users;
    private final KeycloakClients clients;
    private final KeycloakOrgs orgs;
    private final CatalogSetup catalogSetup;
    private final RealmMapper realmMapper;
    private final SmtpMapper smtpMapper;
    private final PasswordGenerator passwordGenerator;
    private final EventBus eventBus;

    @Override
    public CreateRealmResponse create(CreateRealmRequest request) {
        RealmRepresentation realm = toCreateRepresentation(request);
        if (!realms.create(realm)) {
            throw new RealmAlreadyExistsException(realm.getRealm());
        }
        return completeCreation(realm.getRealm(), request, request.adminPassword() != null);
    }

    /**
     * The realm to create: settings, roles, and the owner with its roles and password (generated when the request has
     * none). Sent by {@link #create}, or by {@link RealmBootstrap} with more in it.
     */
    RealmRepresentation toCreateRepresentation(CreateRealmRequest request) {
        String password = request.adminPassword() != null ? request.adminPassword() : passwordGenerator.generate();

        RealmRepresentation realm = realmMapper.toRealmRepresentation(request);
        realm.setRoles(realmRoles());

        UserRepresentation owner = realmMapper.toUserRepresentation(request, password);
        owner.setRealmRoles(Arrays.stream(RealmRole.values())
                .map(RealmRole::getRoleName)
                .toList());
        realm.setUsers(List.of(owner));
        return realm;
    }

    /**
     * What follows the creation of the realm in Keycloak, whoever created it: the defaults, the default catalog, the
     * default roles of the owner and the UserCreated event.
     */
    CreateRealmResponse completeCreation(String realmName, CreateRealmRequest request, boolean passwordSet) {
        log.info("Realm '{}' created with owner {}", realmName, request.adminEmail());
        ensureDefaults(realmName);
        // only with a new realm, not in ensureDefaults (which runs on existing realms too): a realm created before
        // this feature existed has no declarations and is never caught up (no migration in 0.x)
        realms.declareUserAttributes(realmName, suspensionAttributes());
        // only with a new realm, not in ensureDefaults: the realm owner may delete it.
        // After the login client, which gets its scope as a default scope
        catalogSetup.ensureDefault(realmName);

        // the realm and its owner are created in a single call: read back the ids Keycloak generated
        String ownerId = users.findIdByEmail(realmName, request.adminEmail())
                .orElseThrow(() -> new IllegalStateException("Owner of realm '%s' not found after creation".formatted(realmName)));
        // a user imported with the realm only gets the roles listed in its representation, not the default roles of
        // the realm that a user created afterwards gets: without the roles of the account client they hold, the
        // Account console of Keycloak answers 401 to the owner
        users.addRealmRole(realmName, ownerId, realms.defaultRoleName(realmName));
        eventBus.publish(UserCreated.ADDRESS, new UserCreated(realmName, null, ownerId, true, passwordSet));
        return new CreateRealmResponse(realms.id(realmName), realmName, ownerId);
    }

    @Override
    public void configureSmtp(String name, SmtpSettings smtp) {
        realms.setSmtpServer(name, smtpMapper.merge(realms.smtpServer(name), smtp));
        log.info("SMTP server {}:{} set on realm '{}'", smtp.host(), smtp.port(), name);
    }

    @Override
    public boolean setLoginTheme(String name, String clientId, String theme) {
        Optional<String> clientUuid = clients.findUuid(name, clientId);
        clientUuid.ifPresent(uuid -> {
            clients.setLoginTheme(name, uuid, theme);
            log.info("Login theme '{}' set on client '{}' of realm '{}'", theme, clientId, name);
        });
        return clientUuid.isPresent();
    }

    @Override
    public void ensureDashboardClient(String name, DashboardClientSettings settings) {
        ClientRepresentation client = clients.find(name, settings.clientId()).orElse(null);
        if (client == null) {
            client = new ClientRepresentation();
            client.setClientId(settings.clientId());
            client.setName("Dashboard");
            client.setEnabled(true);
            // no secret: Keycloak generates one for a confidential client
            applyDashboardSettings(client, settings);
            // explicitly empty, not null: with null, Keycloak adds the origin of the redirect URIs as web origin
            client.setWebOrigins(new ArrayList<>());
            if (clients.create(name, client).isPresent()) {
                log.info("Client '{}' created in realm '{}' for the dashboard on {}", settings.clientId(), name, settings.url());
                return;
            }
            // created by someone else in the meantime: read it and repair it below
            client = clients.find(name, settings.clientId())
                    .orElseThrow(() -> new IllegalStateException("Client '%s' not found in realm '%s'"
                            .formatted(settings.clientId(), name)));
        }
        String uuid = client.getId();
        if (applyDashboardSettings(client, settings)) {
            clients.update(name, uuid, client);
            log.info("Client '{}' of realm '{}' repaired to match the configuration", settings.clientId(), name);
        }
        // a client made by hand, public or bearer-only: it has no secret until it is confidential. The only case
        // where Orgkeep sets the secret
        if (clients.secret(name, uuid).isEmpty()) {
            clients.regenerateSecret(name, uuid);
            log.info("Secret generated for client '{}' of realm '{}'", settings.clientId(), name);
        }
    }

    /**
     * Sets what Orgkeep owns on the dashboard client, leaving the rest (other attributes, secret) alone.
     *
     * @return true when something differed
     */
    private static boolean applyDashboardSettings(ClientRepresentation client, DashboardClientSettings settings) {
        boolean changed = false;
        changed |= set(client.isPublicClient(), false, client::setPublicClient);
        changed |= set(client.isBearerOnly(), false, client::setBearerOnly);
        changed |= set(client.getClientAuthenticatorType(), "client-secret", client::setClientAuthenticatorType);
        changed |= set(client.isStandardFlowEnabled(), true, client::setStandardFlowEnabled);
        changed |= set(client.isDirectAccessGrantsEnabled(), false, client::setDirectAccessGrantsEnabled);
        changed |= set(client.isImplicitFlowEnabled(), false, client::setImplicitFlowEnabled);
        changed |= set(client.isServiceAccountsEnabled(), false, client::setServiceAccountsEnabled);
        changed |= set(orEmpty(client.getRedirectUris()), List.of(settings.url() + "/*"), client::setRedirectUris);
        changed |= set(orEmpty(client.getWebOrigins()), List.<String>of(), client::setWebOrigins);
        // the home URL: where Keycloak's "Back to Application" link points to, on its last page after an invitation
        changed |= set(client.getBaseUrl(), settings.url() + "/", client::setBaseUrl);

        Map<String, String> attributes = client.getAttributes() == null ? new HashMap<>() : new HashMap<>(client.getAttributes());
        boolean attributesChanged = !"+".equals(attributes.put("post.logout.redirect.uris", "+"));
        attributesChanged |= !"S256".equals(attributes.put("pkce.code.challenge.method", "S256"));
        if (settings.loginTheme() != null) {
            attributesChanged |= !settings.loginTheme().equals(attributes.put("login_theme", settings.loginTheme()));
        }
        client.setAttributes(attributes);
        return changed || attributesChanged;
    }

    private static <T> boolean set(T current, T wanted, Consumer<T> setter) {
        if (wanted.equals(current)) {
            return false;
        }
        setter.accept(wanted);
        return true;
    }

    private static List<String> orEmpty(List<String> list) {
        return list == null ? List.of() : list;
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
        restrictScope(name, loginClientUuid, existing.isEmpty());

        // the client of the API of Orgkeep, for the screens of an integrator: the roles Orgkeep reads, no catalog
        Optional<String> existingApiClient = clients.findUuid(name, OrgkeepApiClient.CLIENT_ID);
        String apiClientUuid = existingApiClient
                .or(() -> clients.create(name, realmMapper.toOrgkeepApiClient()))
                .or(() -> clients.findUuid(name, OrgkeepApiClient.CLIENT_ID))
                .orElseThrow(() -> new IllegalStateException("Client '%s' not found in realm '%s'"
                        .formatted(OrgkeepApiClient.CLIENT_ID, name)));
        if (existingApiClient.isEmpty()) {
            // Keycloak gives a new client the optional scopes of the realm, the catalogs among them
            catalogSetup.catalogScopeIds(name).values()
                    .forEach(scopeId -> clients.removeScope(name, apiClientUuid, scopeId));
        }
        restrictScope(name, apiClientUuid, existingApiClient.isEmpty());
    }

    /**
     * fullScopeAllowed=false: the roles of a catalog only reach a token of the client when the scope of the
     * catalog is in it. Every other role the core API reads must then be mapped explicitly: the realm roles
     * (realm_access) and the roles of every org (resource_access.org-{org}, mapped when an org is created).
     * For the login client and the client of the API of Orgkeep.
     *
     * @param created the client was just created: like a client switching to fullScopeAllowed=false, it has
     *                none of the org mappings yet
     */
    private void restrictScope(String realm, String clientUuid, boolean created) {
        List<String> realmRoles = new ArrayList<>(Arrays.stream(RealmRole.values()).map(RealmRole::getRoleName).toList());
        // composite of offline_access, uma_authorization and the roles of the account client
        realmRoles.add(realms.defaultRoleName(realm));
        clients.addRealmRolesToScope(realm, clientUuid, realmRoles);

        ClientRepresentation client = clients.get(realm, clientUuid);
        if (created || client.isFullScopeAllowed()) {
            // once, not at every start: one call per org
            orgs.names(realm).forEach(org -> clients.findUuid(realm, OrgClients.clientId(org))
                    .ifPresent(orgClientUuid -> clients.addClientRolesToScope(realm, clientUuid, orgClientUuid)));
        }
        if (client.isFullScopeAllowed()) {
            client.setFullScopeAllowed(false);
            clients.update(realm, clientUuid, client);
            log.info("Client '{}' of realm '{}' restricted to the roles of its scope (fullScopeAllowed=false)",
                    client.getClientId(), realm);
        }
    }

    @Override
    public void grantConsoleAccess(String name) {
        // a realm role named realm-admin would not do: the console checks the roles of the realm-management client
        realms.addClientRoleToComposite(name, RealmRole.REALM_OWNER.getRoleName(), REALM_MANAGEMENT, REALM_ADMIN);
        log.info("Role '{}' of realm '{}' includes {}/{}: Keycloak console access",
                RealmRole.REALM_OWNER.getRoleName(), name, REALM_MANAGEMENT, REALM_ADMIN);
    }

    @Override
    public void enforceSingleOwner(String name, Optional<String> username, String email) {
        // every check first: a startup that fails changes nothing
        UserRepresentation owner = username
                .map(u -> users.findByUsername(name, u))
                .orElseGet(() -> users.findIdByEmail(name, email).flatMap(id -> users.find(name, id)))
                .orElseThrow(() -> new IllegalStateException(("Realm '%s': the realm owner of the configuration was not "
                        + "found (orgkeep.owner.username / ORGKEEP_OWNER_USERNAME = '%s', or orgkeep.owner.email / "
                        + "ORGKEEP_OWNER_EMAIL = '%s' when no username is set). Create this user in the realm, or set "
                        + "these properties to an existing user.").formatted(name, username.orElse(""), email)));
        String ownerName = owner.getUsername();
        if (!Boolean.TRUE.equals(owner.isEnabled())) {
            throw new IllegalStateException(("Realm '%s': the realm owner of the configuration ('%s') is disabled. "
                    + "Enable this user in the Keycloak console, or configure another user (orgkeep.owner.username / "
                    + "ORGKEEP_OWNER_USERNAME, orgkeep.owner.email / ORGKEEP_OWNER_EMAIL).").formatted(name, ownerName));
        }
        if (username.isPresent() && (owner.getEmail() == null || !owner.getEmail().equalsIgnoreCase(email))) {
            throw new IllegalStateException(("Realm '%s': user '%s' (orgkeep.owner.username / ORGKEEP_OWNER_USERNAME) "
                    + "has the email '%s', not '%s' (orgkeep.owner.email / ORGKEEP_OWNER_EMAIL). Set both properties "
                    + "to the same user.").formatted(name, ownerName, owner.getEmail(), email));
        }
        List<String> memberOf = orgs.organisationsOf(name, owner.getId());
        if (!memberOf.isEmpty()) {
            throw new IllegalStateException(("Realm '%s': the realm owner of the configuration ('%s') is a member of "
                    + "the organisation(s) %s. A realm owner cannot be a member of an organisation: remove this user "
                    + "from the organisation in the Keycloak console, or configure another user "
                    + "(orgkeep.owner.username / ORGKEEP_OWNER_USERNAME, orgkeep.owner.email / ORGKEEP_OWNER_EMAIL).")
                    .formatted(name, ownerName, memberOf));
        }

        // grant first: there is always at least one realm owner
        Set<String> direct = users.directRealmRoles(name, owner.getId());
        for (RealmRole role : RealmRole.values()) {
            if (!direct.contains(role.getRoleName())) {
                users.addRealmRole(name, owner.getId(), role.getRoleName());
                log.info("Realm '{}': {} granted to {} (from the configuration)", name, role.getRoleName(), ownerName);
            }
        }

        // then remove from every other direct holder: read all the holders first, then, user by user, remove their
        // roles and close their sessions once, so a failure part-way never leaves a user stripped but still logged in
        Map<String, List<RealmRole>> rolesToRemove = new LinkedHashMap<>();
        Map<String, String> usernames = new HashMap<>();
        for (RealmRole role : RealmRole.values()) {
            for (UserRepresentation holder : users.directHoldersOfRealmRole(name, role.getRoleName())) {
                if (!holder.getId().equals(owner.getId())) {
                    rolesToRemove.computeIfAbsent(holder.getId(), id -> new ArrayList<>()).add(role);
                    usernames.put(holder.getId(), holder.getUsername());
                }
            }
        }
        rolesToRemove.forEach((id, roles) -> {
            String holderName = usernames.get(id);
            for (RealmRole role : roles) {
                users.removeRealmRole(name, id, role.getRoleName());
                log.warn("Realm '{}': {} removed from {}: the configuration names {} as the only realm owner",
                        name, role.getRoleName(), holderName, ownerName);
            }
            users.logout(name, id);
            log.warn("Realm '{}': sessions of {} closed", name, holderName);
        });
    }

    @Override
    public void delete(String name){
        realms.delete(name);
    }

    @Override
    public boolean exists(String name) {
        return realms.exists(name);
    }

    /**
     * The four suspension attributes (see Suspensions), admin-only (view and edit), never required, no group: a
     * suspension is set by the realm owner's calls, not by anyone editing a profile form. suspension-reason has
     * the "length" validator, 500 characters at most.
     */
    private static List<UPAttribute> suspensionAttributes() {
        UPAttribute reason = new UPAttribute(Suspensions.REASON, adminOnly());
        Map<String, Object> maxLength = new HashMap<>();
        maxLength.put("max", Suspensions.MAX_REASON_LENGTH);
        reason.addValidation("length", maxLength);
        return List.of(reason,
                new UPAttribute(Suspensions.SUSPENDED_AT, adminOnly()),
                new UPAttribute(Suspensions.SUSPENDED_BY, adminOnly()),
                new UPAttribute(Suspensions.ORG_CHOICE, adminOnly()));
    }

    private static UPAttributePermissions adminOnly() {
        return new UPAttributePermissions(Set.of("admin"), Set.of("admin"));
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
