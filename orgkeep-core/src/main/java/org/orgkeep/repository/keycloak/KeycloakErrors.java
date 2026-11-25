package org.orgkeep.repository.keycloak;

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

    /**
     * Whether a 400 is Keycloak's refusal of a required action it does not recognize on the realm (not registered,
     * e.g. an unknown alias): {"errorMessage":"Provided invalid required actions"}, matched loosely on "invalid
     * required actions", case-insensitive. On 26.8.1 a disabled VERIFY_EMAIL is accepted (measured), not refused
     * this way. False, never throws, if the body cannot be read (e.g. already consumed): the caller then sees a
     * generic 400.
     */
    static boolean isInvalidRequiredActions(WebApplicationException e) {
        try {
            String body = e.getResponse().readEntity(String.class);
            return body != null && body.toLowerCase().contains("invalid required actions");
        } catch (RuntimeException readFailure) {
            return false;
        }
    }

    static void requireSuccess(Response response, String action) {
        if (response.getStatusInfo().getFamily() != Response.Status.Family.SUCCESSFUL) {
            throw new IllegalStateException("%s failed with status %d: %s"
                    .formatted(action, response.getStatus(), response.readEntity(String.class)));
        }
    }
}
