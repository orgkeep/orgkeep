package org.orgkeep.api.model;

/**
 * Naming of the Keycloak clients owned by organisations.
 * The prefix sets them apart from the other clients of the realm.
 */
public final class OrgClients {
    public static final String PREFIX = "org-";

    /** Attribute of the client of a service account: the org it belongs to. Never reaches a token. */
    public static final String ORG_ATTRIBUTE = "org";
    /** Keycloak names the user of a service account client with this prefix and the clientId. */
    public static final String SERVICE_ACCOUNT_USERNAME_PREFIX = "service-account-";

    private OrgClients() {
    }

    /**
     * clientId of the client of a service account, e.g. "org-acme.billing". The dot is in no slug (orgs, teams,
     * catalogs): it never collides with an org client or a catalog. Never a ":", the separator of client_secret_basic.
     */
    public static String serviceAccountClientId(String orgName, String serviceAccount) {
        return serviceAccountClientPrefix(orgName) + serviceAccount;
    }

    /** What the clientId of every service account of the org starts with, e.g. "org-acme.". */
    public static String serviceAccountClientPrefix(String orgName) {
        return clientId(orgName) + ".";
    }

    /** clientId of the client holding the roles of an organisation, e.g. "org-acme". */
    public static String clientId(String orgName) {
        return PREFIX + orgName;
    }
}
