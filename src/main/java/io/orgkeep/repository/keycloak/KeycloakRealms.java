package io.orgkeep.repository.keycloak;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import lombok.RequiredArgsConstructor;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.idm.RealmRepresentation;

import java.util.Map;

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

    public void setSmtpServer(String realm, Map<String, String> smtpServer) {
        // partial update: Keycloak only changes the fields that are set in the representation
        RealmRepresentation update = new RealmRepresentation();
        update.setSmtpServer(smtpServer);
        keycloak.realm(realm).update(update);
    }

    /** Without an SMTP host, Keycloak cannot send any email of the realm. */
    public boolean hasSmtpServer(String realm) {
        Map<String, String> smtpServer = keycloak.realm(realm).toRepresentation().getSmtpServer();
        return smtpServer != null && smtpServer.get("host") != null && !smtpServer.get("host").isBlank();
    }

    public void delete(String realm) {
        keycloak.realm(realm).remove();
    }
}
