package org.orgkeep.api.exceptions;

/**
 * A user belongs to one organisation only: the product quotas in the token are not qualified by organisation.
 */
public class MemberOfAnotherOrgException extends UserNotAddableException{
    public MemberOfAnotherOrgException(String email) {
        super(email);
    }
}
