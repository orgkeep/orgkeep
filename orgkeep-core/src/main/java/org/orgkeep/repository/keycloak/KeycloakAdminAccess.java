package org.orgkeep.repository.keycloak;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import lombok.RequiredArgsConstructor;
import org.eclipse.microprofile.config.ConfigProvider;
import org.keycloak.admin.client.Keycloak;

/**
 * What the admin client of Orgkeep is allowed to do right now: does Keycloak give it a token at all?
 * Asked at startup, before the first real call, to tell "realm missing" from "wrong secret".
 */
@ApplicationScoped
@RequiredArgsConstructor
public class KeycloakAdminAccess {
    public enum Probe {
        OK,
        /** The token endpoint answered 404: the realm of the admin client does not exist. */
        REALM_NOT_FOUND,
        /** The token endpoint answered 401: wrong secret, or the client is deleted or disabled. */
        UNAUTHORIZED
    }

    private final Keycloak keycloak;

    /** Asks Keycloak for a token of the admin client. Any other failure is rethrown with the connection details. */
    public Probe probe() {
        try {
            keycloak.tokenManager().getAccessToken();
            return Probe.OK;
        } catch (WebApplicationException e) {
            int status = e.getResponse().getStatus();
            if (status == Response.Status.NOT_FOUND.getStatusCode()) {
                return Probe.REALM_NOT_FOUND;
            }
            if (status == Response.Status.UNAUTHORIZED.getStatusCode()) {
                return Probe.UNAUTHORIZED;
            }
            throw unreachable(e);
        } catch (RuntimeException e) {
            throw unreachable(e);
        }
    }

    /** Server URL of the admin client: Keycloak for the admin API (an internal URL in Docker). */
    public String serverUrl() {
        return property("server-url", "");
    }

    /** Realm the admin client logs in to: the managed realm, or master when the runtime client is an admin of master. */
    public String realm() {
        return property("realm", "master");
    }

    public String clientId() {
        return property("client-id", "admin-cli");
    }

    private IllegalStateException unreachable(RuntimeException cause) {
        return new IllegalStateException("Cannot get a token from Keycloak at %s (realm '%s', client '%s'): %s"
                .formatted(serverUrl(), realm(), clientId(), cause.getMessage()), cause);
    }

    private static String property(String name, String defaultValue) {
        return ConfigProvider.getConfig()
                .getOptionalValue("quarkus.keycloak.admin-client." + name, String.class)
                .orElse(defaultValue);
    }
}
