package org.orgkeep.repository.keycloak;

/**
 * Keycloak refused an {@code execute-actions-email} call because one of the required actions it was asked to put
 * in the link is an action Keycloak does not recognize on the realm (not registered); on 26.8.1 a disabled
 * VERIFY_EMAIL is accepted (measured). Thrown by
 * {@link KeycloakUsers#sendActionsEmail}, caught by InvitationMailer to retry with fewer actions. Not a business
 * exception: it never reaches web/ExceptionMappers, the caller always handles it or lets a second occurrence
 * propagate as any other unexpected Keycloak failure.
 */
public class InvalidRequiredActionsException extends RuntimeException {

    public InvalidRequiredActionsException(String message, Throwable cause) {
        super(message, cause);
    }
}
