package org.orgkeep.api.exceptions;

public class MemberNotFoundException extends ResourceNotFoundException{
    public MemberNotFoundException(String orgName, String userId) {
        super("User '%s' is not a member of the organisation '%s'".formatted(userId,orgName));
    }
}
