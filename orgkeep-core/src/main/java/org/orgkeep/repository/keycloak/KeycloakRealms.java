package org.orgkeep.repository.keycloak;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import lombok.RequiredArgsConstructor;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.admin.client.resource.UserProfileResource;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.userprofile.config.UPAttribute;
import org.keycloak.representations.userprofile.config.UPConfig;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@ApplicationScoped
@RequiredArgsConstructor
public class KeycloakRealms {
    private final Keycloak keycloak;

    /** @return false if a realm with this name already exists */
    public boolean create(RealmRepresentation realm) {
        try {
            keycloak.realms().create(realm);
            return true;
        } catch (WebApplicationException e) {
            if (e.getResponse().getStatus() == Response.Status.CONFLICT.getStatusCode()) {
                return false;
            }
            throw e;
        }
    }

    public boolean exists(String realm) {
        try {
            keycloak.realm(realm).toRepresentation();
            return true;
        } catch (WebApplicationException e) {
            // only a 404 means "absent": a 401/403/500 must not be mistaken for a missing realm
            if (!KeycloakErrors.isNotFound(e)) {
                throw e;
            }
            return false;
        }
    }

    public String id(String realm) {
        return keycloak.realm(realm).toRepresentation().getId();
    }

    /** The composite realm role every user of the realm gets ("default-roles-{realm}"). */
    public String defaultRoleName(String realm) {
        return keycloak.realm(realm).toRepresentation().getDefaultRole().getName();
    }

    /**
     * Adds the role clientRole of the client clientId to the composites of the realm role realmRole.
     * Idempotent: Keycloak ignores a composite already there.
     */
    public void addClientRoleToComposite(String realm, String realmRole, String clientId, String clientRole) {
        RealmResource realmResource = keycloak.realm(realm);
        String clientUuid = realmResource.clients().findByClientId(clientId).stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No client '%s' in realm '%s'".formatted(clientId, realm)))
                .getId();
        RoleRepresentation role = realmResource.clients().get(clientUuid).roles().get(clientRole).toRepresentation();
        realmResource.roles().get(realmRole).addComposites(List.of(role));
    }

    /**
     * The realm's "smtpServer" map, as Keycloak returns it (password masked), a mutable copy; an empty map when
     * Keycloak returns null.
     */
    public Map<String, String> smtpServer(String realm) {
        Map<String, String> smtpServer = keycloak.realm(realm).toRepresentation().getSmtpServer();
        return smtpServer == null ? new HashMap<>() : new HashMap<>(smtpServer);
    }

    /**
     * Replaces the realm's "smtpServer" map with the one given (partial realm update, the rest of the realm is
     * untouched): Keycloak replaces the whole map with the one it receives, an empty map clears it, null is ignored.
     */
    public void setSmtpServer(String realm, Map<String, String> smtpServer) {
        RealmRepresentation update = new RealmRepresentation();
        update.setSmtpServer(smtpServer);
        keycloak.realm(realm).update(update);
    }

    /** The value of a realm attribute, empty when the realm does not have it. */
    public Optional<String> attribute(String realm, String name) {
        Map<String, String> attributes = keycloak.realm(realm).toRepresentation().getAttributes();
        return attributes == null ? Optional.empty() : Optional.ofNullable(attributes.get(name));
    }

    /** Sets one realm attribute: the other ones are kept. */
    public void setAttribute(String realm, String name, String value) {
        // Keycloak replaces the whole map of attributes with the one it receives (the ones left out are removed): all
        // the current attributes are sent back with this one. The other fields of the realm are left out of the update
        Map<String, String> attributes = new HashMap<>();
        Map<String, String> current = keycloak.realm(realm).toRepresentation().getAttributes();
        if (current != null) {
            attributes.putAll(current);
        }
        attributes.put(name, value);
        RealmRepresentation update = new RealmRepresentation();
        update.setAttributes(attributes);
        keycloak.realm(realm).update(update);
    }

    /** Without an SMTP host, Keycloak cannot send any email of the realm. */
    public boolean hasSmtpServer(String realm) {
        String host = smtpServer(realm).get("host");
        return host != null && !host.isBlank();
    }

    public void delete(String realm) {
        keycloak.realm(realm).remove();
    }

    /**
     * Declares these attributes in the realm's user profile, read then written back whole: the admin API replaces
     * the whole configuration. Idempotent, an attribute already declared with that name is left as is (its
     * permissions and validators are not touched). Called only at realm creation (see RealmProvisionerImpl): a
     * realm created before this declaration existed is never caught up (no migration in 0.x).
     */
    public void declareUserAttributes(String realm, List<UPAttribute> attributes) {
        UserProfileResource userProfile = keycloak.realm(realm).users().userProfile();
        UPConfig config = userProfile.getConfiguration();
        boolean changed = false;
        for (UPAttribute attribute : attributes) {
            if (config.getAttribute(attribute.getName()) == null) {
                config.addOrReplaceAttribute(attribute);
                changed = true;
            }
        }
        if (changed) {
            userProfile.update(config);
        }
    }
}
