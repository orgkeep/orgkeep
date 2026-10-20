package io.orgkeep.api.exceptions;

/**
 * A user belongs to one organisation only: the product quotas in the token are not qualified by organisation.
 */
public class MemberOfAnotherOrgException extends OperationNotAllowedException{
    public MemberOfAnotherOrgException(String email, String otherOrgName) {
        super("User '%s' is already a member of the organisation '%s': a user belongs to one organisation only"
                .formatted(email,otherOrgName));
    }
}
