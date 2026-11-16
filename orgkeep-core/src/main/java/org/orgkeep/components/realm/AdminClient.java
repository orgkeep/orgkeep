package org.orgkeep.components.realm;

/** The client Orgkeep calls the Keycloak admin API with, created in the realm it manages: its id and secret. */
public record AdminClient(String clientId, String secret) {
}
