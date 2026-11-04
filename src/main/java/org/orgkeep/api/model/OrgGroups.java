package org.orgkeep.api.model;

import java.util.regex.Pattern;

/**
 * Group tree of an organisation:
 * <pre>
 * /{org}
 *   /user-groups
 *     /admin        org-admin
 *     /users        org-user, default group of new members
 *     /...          created by the org admins (the teams)
 *   /catalogs
 *     /{catalog}
 *       /{product}  assignment of the product to the org: holds the product role and what the org negotiated.
 *                   Nobody is a member of it
 * </pre>
 */
public final class OrgGroups {
    public static final String USER_GROUPS = "user-groups";
    public static final String CATALOGS = "catalogs";
    public static final String ADMIN = "admin";
    public static final String USERS = "users";

    /** Attributes of an assignment group of a simple product: the quota of the org and its cycle (see {@link QuotaCycle}). */
    public static final String QUOTA_ATTRIBUTE = "quota";
    public static final String QUOTA_CYCLE_ATTRIBUTE = "quota-cycle";
    private static final String FEATURE_QUOTA_PREFIX = QUOTA_ATTRIBUTE + ".";
    private static final String FEATURE_QUOTA_CYCLE_PREFIX = QUOTA_CYCLE_ATTRIBUTE + ".";
    /** Multivalued attribute of an assignment group: the features of the product the org bought, without the prefix. */
    public static final String BOUGHT_FEATURES_ATTRIBUTE = "bought-features";
    private static final String PARAM_PREFIX = "param.";
    private static final Pattern USER_GROUP_NAME = Pattern.compile("[a-z0-9]([a-z0-9-]*[a-z0-9])?");

    private OrgGroups() {
    }

    public static String root(String orgName) {
        return "/" + orgName;
    }

    public static String userGroups(String orgName) {
        return root(orgName) + "/" + USER_GROUPS;
    }

    public static String catalogs(String orgName) {
        return root(orgName) + "/" + CATALOGS;
    }

    public static String catalog(String orgName, String catalog) {
        return catalogs(orgName) + "/" + catalog;
    }

    /** The group of the assignment of a product to the org. */
    public static String assignment(String orgName, String catalog, String product) {
        return catalog(orgName, catalog) + "/" + product;
    }

    /** The org of an assignment group path, null if the path is not one ("/acme/catalogs/prod/billing" gives "acme"). */
    public static String orgOfAssignment(String path, String catalog, String product) {
        // "/acme/catalogs/prod/billing" splits into ["", "acme", "catalogs", "prod", "billing"]
        String[] segments = path.split("/");
        if (segments.length != 5 || !assignment(segments[1], catalog, product).equals(path)) {
            return null;
        }
        return segments[1];
    }

    /** Attribute of an assignment group of a metered product: the quota of the org for a metered feature, e.g. "quota.search". */
    public static String featureQuotaAttribute(String feature) {
        return FEATURE_QUOTA_PREFIX + feature;
    }

    /** Its cycle, e.g. "quota-cycle.search". */
    public static String featureQuotaCycleAttribute(String feature) {
        return FEATURE_QUOTA_CYCLE_PREFIX + feature;
    }

    /** The feature of a {@link #featureQuotaAttribute} ("quota.search" gives "search"), null for another attribute. */
    public static String featureOfQuota(String attribute) {
        return attribute.startsWith(FEATURE_QUOTA_PREFIX) ? attribute.substring(FEATURE_QUOTA_PREFIX.length()) : null;
    }

    /** Attribute of an assignment group holding the value of a parameter of the product, e.g. "param.region". */
    public static String paramAttribute(String param) {
        return PARAM_PREFIX + param;
    }

    /** The parameter of a value attribute ("param.region" gives "region"), null for another attribute. */
    public static String paramOf(String attribute) {
        return attribute.startsWith(PARAM_PREFIX) ? attribute.substring(PARAM_PREFIX.length()) : null;
    }

    public static String users(String orgName) {
        return userGroup(orgName, USERS);
    }

    public static String admin(String orgName) {
        return userGroup(orgName, ADMIN);
    }

    public static String userGroup(String orgName, String groupName) {
        return userGroups(orgName) + "/" + groupName;
    }

    /**
     * Same rule as CreateUserGroupRequest.name, for a name read from the URL: it is not validated by Bean Validation,
     * and a "/" in it would point to another group of the tree.
     */
    public static boolean isUserGroupName(String name) {
        return USER_GROUP_NAME.matcher(name).matches();
    }

    /** True for the path of a user group of any org, e.g. "/acme/user-groups/ops". */
    public static boolean isUserGroup(String path) {
        // "/acme/user-groups/ops" splits into ["", "acme", "user-groups", "ops"]
        String[] segments = path.split("/");
        return segments.length == 4 && segments[0].isEmpty() && USER_GROUPS.equals(segments[2]);
    }

    /** The user groups created with the organisation: they hold the org roles and cannot be deleted. */
    public static boolean isPredefined(String groupName) {
        return ADMIN.equals(groupName) || USERS.equals(groupName);
    }
}
