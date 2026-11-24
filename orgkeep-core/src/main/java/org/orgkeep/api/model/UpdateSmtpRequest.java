package org.orgkeep.api.model;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Replaces the SMTP server of the realm (see {@link SmtpServer}): only the keys Orgkeep owns (host, port, from,
 * fromDisplayName, starttls, ssl, auth, user, password); every other key already set in the Keycloak console
 * (reply-to, envelope from...) is kept.
 * <p>
 * password follows Keycloak's own convention: absent (null) keeps the stored password; a value replaces it. A
 * changed username without a password also keeps the stored password. Ignored (no password is stored) when
 * username is absent or blank. Side effect, accepted: a password literally equal to Keycloak's mask
 * ({@code **********}) is read by Keycloak as "unchanged".
 */
public record UpdateSmtpRequest(
    @NotBlank @Size(max = 255) String host,
    @NotNull @Min(1) @Max(65535) Integer port,
    @NotBlank @Email String from,
    String fromDisplayName,
    String username,
    String password,
    boolean starttls,
    boolean ssl){

    @Override
    public String toString() {
        return "UpdateSmtpRequest[host=" + host + ", port=" + port + ", from=" + from + ", fromDisplayName=" + fromDisplayName
                + ", username=" + username + ", password=" + (password == null ? null : "***")
                + ", starttls=" + starttls + ", ssl=" + ssl + "]";
    }
}
