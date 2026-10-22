package io.orgkeep.api.model;

/**
 * What the APIs of a catalog need to introspect the tokens (RFC 7662): the credentials of the catalog client.
 * Keycloak only lets a client introspect the tokens it is the audience of, and the catalog client is in the audience
 * of every token carrying one of its roles: the APIs of a catalog introspect the tokens of this catalog, not the others.
 *
 * @param clientId         the name of the catalog
 * @param clientSecret     shown only when generated: Orgkeep does not give it back afterwards
 * @param introspectionUrl where the APIs send the tokens, authenticated with clientId and clientSecret (HTTP Basic)
 */
public record CatalogCredentials(
    String clientId,
    String clientSecret,
    String introspectionUrl){ }
