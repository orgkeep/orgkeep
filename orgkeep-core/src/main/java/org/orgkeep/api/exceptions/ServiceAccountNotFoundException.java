package org.orgkeep.api.exceptions;

/** The message never says whether the name exists in another organisation. */
public class ServiceAccountNotFoundException extends ResourceNotFoundException {
    public ServiceAccountNotFoundException(String orgName, String serviceAccount) {
        super("No service account '%s' in the organisation '%s'".formatted(serviceAccount, orgName));
    }
}
