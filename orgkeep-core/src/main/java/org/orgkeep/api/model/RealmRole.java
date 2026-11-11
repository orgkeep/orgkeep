package org.orgkeep.api.model;

import lombok.AllArgsConstructor;
import lombok.Getter;

@AllArgsConstructor
public enum RealmRole {
    REALM_OWNER("realm-owner"),
    REALM_ADMIN("realm-admin"),
    REALM_USER("realm-user");

    @Getter
    private final String roleName;

}
