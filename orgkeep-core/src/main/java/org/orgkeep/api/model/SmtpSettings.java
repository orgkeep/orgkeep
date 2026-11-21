package org.orgkeep.api.model;

/**
 * SMTP server Keycloak uses to send the emails of a realm (invitations, password reset, email verification).
 *
 * @param from            sender address, required by Keycloak
 * @param fromDisplayName optional
 * @param username        optional, authentication is enabled when set
 * @param password        optional, used with username; masked by {@link #toString()}
 */
public record SmtpSettings(
    String host,
    int port,
    String from,
    String fromDisplayName,
    String username,
    String password,
    boolean starttls,
    boolean ssl){

    @Override
    public String toString() {
        return "SmtpSettings[host=" + host + ", port=" + port + ", from=" + from + ", fromDisplayName=" + fromDisplayName
                + ", username=" + username + ", password=" + (password == null ? null : "***")
                + ", starttls=" + starttls + ", ssl=" + ssl + "]";
    }
}
