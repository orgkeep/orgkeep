package org.orgkeep.api.exceptions;

/**
 * An existing user cannot be added to an organisation. The subclasses say why, for the logs and the Java callers,
 * but the message is the same for all of them: the caller may be an org admin, who must learn nothing about the rest
 * of the realm (who holds a realm role, who is a client of the realm owner).
 */
public abstract class UserNotAddableException extends OperationNotAllowedException{
    protected UserNotAddableException(String email) {
        super("User '%s' cannot be added to this organisation".formatted(email));
    }
}
