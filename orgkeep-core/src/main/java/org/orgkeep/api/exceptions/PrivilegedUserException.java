package org.orgkeep.api.exceptions;

/**
 * A privileged user (a realm role of Orgkeep, or a role of the realm-management client) works for the realm owner,
 * not for one of its clients: it cannot be added to an organisation. Otherwise an org admin could take such a user
 * into their org, and disabling the org would disable it.
 */
public class PrivilegedUserException extends UserNotAddableException{
    public PrivilegedUserException(String email) {
        super(email);
    }
}
