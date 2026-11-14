package org.orgkeep.api.model;

/**
 * What an application needs to get tokens with the client credentials grant.
 *
 * @param clientId     the client id of the service account
 * @param clientSecret shown only when generated (creation, rotation): Orgkeep does not give it back afterwards
 * @param tokenUrl     the token endpoint, where clientId and clientSecret are sent (HTTP Basic or form post)
 */
public record ServiceAccountCredentials(
    String clientId,
    String clientSecret,
    String tokenUrl){ }
