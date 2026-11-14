package org.orgkeep.api.model;

import java.util.List;

/**
 * A service account of an organisation: a confidential Keycloak client using the client credentials grant.
 *
 * @param name            name in the organisation, a slug
 * @param clientId        the client id the application authenticates with, e.g. "org-acme.billing"
 * @param userId          id of the service-account user of the client: what a reserved catalog role is granted to
 * @param description     optional
 * @param enabled         false: no new token is issued
 * @param defaultCatalogs catalogs in every token of the service account; the others are asked with the scope parameter
 */
public record ServiceAccount(
    String name,
    String clientId,
    String userId,
    String description,
    boolean enabled,
    List<String> defaultCatalogs){ }
