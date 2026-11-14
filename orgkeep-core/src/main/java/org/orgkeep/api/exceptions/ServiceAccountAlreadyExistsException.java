package org.orgkeep.api.exceptions;

/** Thrown when the clientId is taken, whoever owns it: the message does not tell. */
public class ServiceAccountAlreadyExistsException extends AlreadyExistsException {
    public ServiceAccountAlreadyExistsException(String orgName, String serviceAccount) {
        super("A service account '%s' already exists in the organisation '%s'".formatted(serviceAccount, orgName));
    }
}
