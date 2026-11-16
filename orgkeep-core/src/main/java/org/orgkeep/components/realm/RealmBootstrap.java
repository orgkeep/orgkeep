package org.orgkeep.components.realm;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.keycloak.OAuth2Constants;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.orgkeep.api.exceptions.RealmAlreadyExistsException;
import org.orgkeep.api.model.CreateRealmRequest;
import org.orgkeep.api.model.CreateRealmResponse;
import org.orgkeep.repository.keycloak.KeycloakAdminAccess;

import java.util.ArrayList;
import java.util.List;

/**
 * Creates the managed realm at the first startup, with an admin of the master realm: the only moment Orgkeep needs one.
 * The realm, the admin client of Orgkeep and its service account are created in a single call, then everything else
 * goes through the injected admin client, which is the service account of the new realm.
 */
@Slf4j
@ApplicationScoped
@RequiredArgsConstructor
public class RealmBootstrap {
    private static final String MASTER_REALM = "master";
    private static final String ADMIN_CLI = "admin-cli";

    private final RealmProvisionerImpl provisioner;
    private final RealmMapper realmMapper;
    private final KeycloakAdminAccess adminAccess;

    /**
     * @throws RealmAlreadyExistsException if the realm exists
     * @throws IllegalStateException       if Keycloak refuses the bootstrap credentials
     */
    public CreateRealmResponse createWithBootstrapAdmin(CreateRealmRequest request, AdminClient adminClient,
                                                        BootstrapCredentials credentials) {
        RealmRepresentation realm = provisioner.toCreateRepresentation(request);
        realm.setClients(List.of(realmMapper.toAdminClient(adminClient.clientId(), adminClient.secret())));
        List<UserRepresentation> users = new ArrayList<>(realm.getUsers());
        users.add(realmMapper.toAdminServiceAccount(adminClient.clientId()));
        realm.setUsers(users);

        // a local client, closed right after: the master credentials are never kept, never a bean
        try (Keycloak master = KeycloakBuilder.builder()
                .serverUrl(adminAccess.serverUrl())
                .realm(MASTER_REALM)
                .clientId(ADMIN_CLI)
                .grantType(OAuth2Constants.PASSWORD)
                .username(credentials.username())
                .password(credentials.password())
                .build()) {
            // the token first: a refused password answers 400 (Keycloak) or 401, told apart from a refused creation
            try {
                master.tokenManager().getAccessToken();
            } catch (WebApplicationException e) {
                int status = e.getResponse().getStatus();
                if (status == Response.Status.UNAUTHORIZED.getStatusCode() || status == Response.Status.BAD_REQUEST.getStatusCode()) {
                    throw new IllegalStateException(("Keycloak refused KEYCLOAK_ADMIN_USER / KEYCLOAK_ADMIN_PASSWORD on the "
                            + "master realm at %s: Orgkeep needs them to create realm '%s' at the first startup.")
                            .formatted(adminAccess.serverUrl(), request.realmName()), e);
                }
                throw e;
            }
            master.realms().create(realm);
            closeSession(master);
        } catch (WebApplicationException e) {
            if (e.getResponse().getStatus() == Response.Status.CONFLICT.getStatusCode()) {
                throw new RealmAlreadyExistsException(request.realmName());
            }
            // the token was accepted (checked above): this user may not create realms
            if (e.getResponse().getStatus() == Response.Status.FORBIDDEN.getStatusCode()
                    || e.getResponse().getStatus() == Response.Status.UNAUTHORIZED.getStatusCode()) {
                throw new IllegalStateException(("KEYCLOAK_ADMIN_USER is not allowed to create realms on the master "
                        + "realm at %s (it needs the admin or create-realm role).").formatted(adminAccess.serverUrl()), e);
            }
            throw e;
        }
        log.info("Realm '{}' created with its admin client '{}'", request.realmName(), adminClient.clientId());
        return provisioner.completeCreation(request.realmName(), request, request.adminPassword() != null);
    }

    /** Ends the session of the master admin in Keycloak; a failure here must not fail the startup. */
    private static void closeSession(Keycloak master) {
        try {
            master.tokenManager().logout();
        } catch (RuntimeException e) {
            log.warn("Could not end the session of KEYCLOAK_ADMIN_USER on the master realm: {}", e.getMessage());
        }
    }
}
