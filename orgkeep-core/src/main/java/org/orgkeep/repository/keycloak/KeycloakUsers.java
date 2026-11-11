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
import java.util.List;
import java.util.Optional;

@ApplicationScoped
@RequiredArgsConstructor
public class KeycloakUsers {
    private final Keycloak keycloak;

    public Optional<String> findIdByEmail(String realm, String email) {
        return keycloak.realm(realm).users().searchByEmail(email, true).stream()
                .findFirst()
                .map(UserRepresentation::getId);
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
     * of the realm. No client given: Keycloak uses its "account" client and ends on its own "account updated" page.
     * Following the link also marks the email as verified.
     */
    public void sendActionsEmail(String realm, String userId, List<String> actions, Duration lifespan) {
        keycloak.realm(realm).users().get(userId)
                .executeActionsEmail(null, null, (int) lifespan.toSeconds(), actions);
    }
}
