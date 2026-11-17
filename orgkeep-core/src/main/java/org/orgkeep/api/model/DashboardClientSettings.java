package org.orgkeep.api.model;

/**
 * What Orgkeep owns on the Keycloak client of the dashboard.
 *
 * @param clientId   the client id
 * @param url        public origin of the dashboard, without trailing slash: the redirect URI is url + "/*"
 * @param loginTheme login theme of the client, null: left as it is
 */
public record DashboardClientSettings(String clientId, String url, String loginTheme) { }
