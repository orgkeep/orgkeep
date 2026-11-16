package org.orgkeep.components.realm;

/** An admin of the master realm, only needed to create the managed realm at the first startup. */
public record BootstrapCredentials(String username, String password) {
}
