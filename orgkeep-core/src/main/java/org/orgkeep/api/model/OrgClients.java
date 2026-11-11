package org.orgkeep.api.model;

/**
 * Naming of the Keycloak clients owned by organisations.
 * The prefix sets them apart from the other clients of the realm.
 */
public final class OrgClients {
    public static final String PREFIX = "org-";

    private OrgClients() {
    }

    /** clientId of the client holding the roles of an organisation, e.g. "org-acme". */
    public static String clientId(String orgName) {
        return PREFIX + orgName;
    }
}
