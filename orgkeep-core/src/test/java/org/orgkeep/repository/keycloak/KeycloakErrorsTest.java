package org.orgkeep.repository.keycloak;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plain unit test: isInvalidRequiredActions only reads a WebApplicationException, no Keycloak call to mock.
 */
class KeycloakErrorsTest {

    @Test
    void recognisesKeycloaksInvalidRequiredActionsBody() {
        WebApplicationException e = errorWithBody("{\"errorMessage\":\"Provided invalid required actions\"}");

        assertTrue(KeycloakErrors.isInvalidRequiredActions(e));
    }

    @Test
    void matchingIsCaseInsensitive() {
        WebApplicationException e = errorWithBody("{\"errorMessage\":\"PROVIDED INVALID REQUIRED ACTIONS\"}");

        assertTrue(KeycloakErrors.isInvalidRequiredActions(e));
    }

    @Test
    void anyOtherBodyIsNotRecognised() {
        WebApplicationException e = errorWithBody("{\"errorMessage\":\"Something else went wrong\"}");

        assertFalse(KeycloakErrors.isInvalidRequiredActions(e));
    }

    @Test
    void anUnreadableBodyIsNotRecognised() {
        // the entity is already consumed: readEntity throws instead of returning the body
        Response response = Response.status(Response.Status.BAD_REQUEST).entity("ignored").build();
        response.readEntity(String.class);
        WebApplicationException e = new WebApplicationException(response);

        assertFalse(KeycloakErrors.isInvalidRequiredActions(e));
    }

    private WebApplicationException errorWithBody(String body) {
        Response response = Response.status(Response.Status.BAD_REQUEST).entity(body).build();
        return new WebApplicationException(response);
    }
}
