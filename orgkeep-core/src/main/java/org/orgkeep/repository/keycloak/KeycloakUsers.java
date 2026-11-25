package org.orgkeep.repository.keycloak;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import lombok.RequiredArgsConstructor;
import org.keycloak.admin.client.CreatedResponseUtil;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@ApplicationScoped
@RequiredArgsConstructor
public class KeycloakUsers {
    private final Keycloak keycloak;

    public Optional<String> findIdByEmail(String realm, String email) {
        return keycloak.realm(realm).users().searchByEmail(email, true).stream()
                .findFirst()
                .map(UserRepresentation::getId);
    }

    /** Exact match on the username (Keycloak stores usernames in lower case). */
    public Optional<UserRepresentation> findByUsername(String realm, String username) {
        return keycloak.realm(realm).users().search(username, true).stream().findFirst();
    }

    /** @return the id of the new user */
    public String create(String realm, UserRepresentation user) {
        try(Response response = keycloak.realm(realm).users().create(user)){
            return CreatedResponseUtil.getCreatedId(response);
        }
    }

    public UserRepresentation get(String realm, String userId) {
        return keycloak.realm(realm).users().get(userId).toRepresentation();
    }

    public Optional<UserRepresentation> find(String realm, String userId) {
        try {
            return Optional.of(get(realm, userId));
        } catch (WebApplicationException e) {
            if (!KeycloakErrors.isNotFound(e)) {
                throw e;
            }
            return Optional.empty();
        }
    }

    /**
     * Read then written whole: the admin API replaces what it receives, and with the user profile of Keycloak 24+
     * a partial representation could clear the first and last name.
     */
    public void setEnabled(String realm, String userId, boolean enabled) {
        UserRepresentation user = get(realm, userId);
        user.setEnabled(enabled);
        keycloak.realm(realm).users().get(userId).update(user);
    }

    /** Idempotent: Keycloak ignores a role the user already has. */
    public void addRealmRole(String realm, String userId, String roleName) {
        RoleRepresentation role = keycloak.realm(realm).roles().get(roleName).toRepresentation();
        keycloak.realm(realm).users().get(userId).roles().realmLevel().add(List.of(role));
    }

    /** Idempotent: removes the role mapping of the user, not the roles they get through a group or a composite. */
    public void removeRealmRole(String realm, String userId, String roleName) {
        RoleRepresentation role = keycloak.realm(realm).roles().get(roleName).toRepresentation();
        keycloak.realm(realm).users().get(userId).roles().realmLevel().remove(List.of(role));
    }

    /** The names of the realm roles mapped directly to the user (neither through a composite nor a group). */
    public Set<String> directRealmRoles(String realm, String userId) {
        return keycloak.realm(realm).users().get(userId).roles().realmLevel().listAll().stream()
                .map(RoleRepresentation::getName)
                .collect(Collectors.toSet());
    }

    /** The users holding the realm role directly, all the pages. Those who have it through a group or a composite are not in it. */
    public List<UserRepresentation> directHoldersOfRealmRole(String realm, String roleName) {
        int pageSize = 100;
        List<UserRepresentation> holders = new ArrayList<>();
        while (true) {
            List<UserRepresentation> page = keycloak.realm(realm).roles().get(roleName)
                    .getUserMembers(holders.size(), pageSize);
            holders.addAll(page);
            if (page.size() < pageSize) {
                return holders;
            }
        }
    }

    /** The names of the effective realm roles of the user: granted directly, through a composite role or a group. */
    public Set<String> effectiveRealmRoles(String realm, String userId) {
        return keycloak.realm(realm).users().get(userId).roles().realmLevel().listEffective().stream()
                .map(RoleRepresentation::getName)
                .collect(Collectors.toSet());
    }

    /**
     * The names of the effective roles of the user on one client (clientUuid: the internal id, not the clientId):
     * granted directly, through a composite role or a group.
     */
    public Set<String> effectiveClientRoles(String realm, String userId, String clientUuid) {
        return keycloak.realm(realm).users().get(userId).roles().clientLevel(clientUuid).listEffective().stream()
                .map(RoleRepresentation::getName)
                .collect(Collectors.toSet());
    }

    /** Whether the user has a password. A member invited by Orgkeep has none until they follow the email link. */
    public boolean hasPassword(String realm, String userId) {
        return keycloak.realm(realm).users().get(userId).credentials().stream()
                .anyMatch(credential -> CredentialRepresentation.PASSWORD.equals(credential.getType()));
    }

    /** Ends all the sessions of the user: their refresh tokens stop working. */
    public void logout(String realm, String userId) {
        keycloak.realm(realm).users().get(userId).logout();
    }

    /**
     * Keycloak emails the user a link to perform the required actions (e.g. UPDATE_PASSWORD), through the SMTP server
     * of the realm. No redirect URI is ever sent. With a client (clientId nullable), Keycloak's last page shows a
     * "Back to Application" link to that client's baseUrl, if it has one, and the pages use its login theme; without
     * a client, Keycloak uses its "account" client and ends on its own "account updated" page, with no link.
     * Since Keycloak 26.8, following the link marks the email verified only if VERIFY_EMAIL is among the actions
     * (up to 26.7, every action link did).
     *
     * @throws InvalidRequiredActionsException if Keycloak refuses one of the actions because it does not recognize
     *         it on the realm (not registered; a 400 with "Provided invalid required actions") — on 26.8.1 a
     *         disabled VERIFY_EMAIL is accepted (measured), the retry guards against a Keycloak that refuses an
     *         unknown action; any other error is rethrown unchanged
     */
    public void sendActionsEmail(String realm, String userId, String clientId, List<String> actions, Duration lifespan) {
        try {
            keycloak.realm(realm).users().get(userId)
                    .executeActionsEmail(clientId, null, (int) lifespan.toSeconds(), actions);
        } catch (WebApplicationException e) {
            if (e.getResponse().getStatus() == Response.Status.BAD_REQUEST.getStatusCode()
                    && KeycloakErrors.isInvalidRequiredActions(e)) {
                throw new InvalidRequiredActionsException(
                        "Keycloak refused required actions %s for user %s in realm '%s'".formatted(actions, userId, realm), e);
            }
            throw e;
        }
    }
}
