package org.orgkeep.api.model;

/**
 * SMTP server of the realm, through which Keycloak sends every email of the realm (invitations, password resets,
 * email verifications). Read-only when {@code managedByConfiguration}: the configuration of Orgkeep
 * ({@code orgkeep.smtp.*}) sets it at every startup.
 *
 * @param host                   SMTP host, null when the realm has no server
 * @param port                   SMTP port, null when absent or not a number
 * @param from                   sender address, null when absent
 * @param fromDisplayName        optional, null when absent or blank
 * @param username               set when authentication is enabled, otherwise null
 * @param passwordSet            true when authentication is enabled and a password is stored. The password itself
 *                               is never returned: Keycloak's admin API always masks it
 * @param starttls               whether STARTTLS is enabled
 * @param ssl                    whether SSL is enabled
 * @param managedByConfiguration true when {@code orgkeep.smtp.host} (ON_PREM) sets the server: PUT and DELETE
 *                               answer 409
 */
public record SmtpServer(
    String host,
    Integer port,
    String from,
    String fromDisplayName,
    String username,
    boolean passwordSet,
    boolean starttls,
    boolean ssl,
    boolean managedByConfiguration){ }
