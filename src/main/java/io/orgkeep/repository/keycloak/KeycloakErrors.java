package io.orgkeep.repository.keycloak;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

/**
 * The Quarkus REST client throws ClientWebApplicationException for every error status,
 * never NotFoundException or another JAX-RS subclass: statuses are checked by hand.
 * Kept inside this package: the services never see Keycloak HTTP errors.
 */
final class KeycloakErrors {

    private KeycloakErrors() {
    }

    static boolean isNotFound(WebApplicationException e) {
        return e.getResponse().getStatus() == Response.Status.NOT_FOUND.getStatusCode();
    }

    static boolean isConflict(WebApplicationException e) {
        return isConflict(e.getResponse());
    }

    static boolean isConflict(Response response) {
        return response.getStatus() == Response.Status.CONFLICT.getStatusCode();
    }

    static void requireSuccess(Response response, String action) {
        if (response.getStatusInfo().getFamily() != Response.Status.Family.SUCCESSFUL) {
            throw new IllegalStateException("%s failed with status %d: %s"
                    .formatted(action, response.getStatus(), response.readEntity(String.class)));
        }
    }
}
