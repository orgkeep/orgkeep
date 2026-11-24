package org.orgkeep.components.realm.smtp;

import org.orgkeep.api.model.SmtpServer;
import org.orgkeep.api.model.SmtpSettings;
import org.orgkeep.api.model.UpdateSmtpRequest;
import org.mapstruct.Mapper;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Merges the "smtpServer" map of a realm representation with what Orgkeep owns, keeping every other key (reply-to,
 * envelope from... set in the Keycloak console, or Keycloak 26's OAuth2 SMTP authentication keys) untouched. Used
 * by the API ({@link SmtpServiceImpl}) and by the bootstrap path ({@link org.orgkeep.components.realm.RealmProvisionerImpl}).
 */
@Mapper(componentModel = "cdi")
public interface SmtpMapper {
    /** Keys of the "smtpServer" map that Orgkeep owns; every other key already there is kept by {@link #merge}. */
    Set<String> OWNED_KEYS = Set.of("host", "port", "from", "fromDisplayName", "starttls", "ssl", "auth", "user", "password");

    /**
     * A copy of current with Orgkeep's keys ({@link #OWNED_KEYS}) replaced by smtp; every other key is kept as is.
     * Never puts a null value in the map (Keycloak rejects it): password is only set when non-null, user and
     * password are both left out without a username.
     */
    default Map<String, String> merge(Map<String, String> current, SmtpSettings smtp) {
        Map<String, String> merged = current == null ? new HashMap<>() : new HashMap<>(current);
        OWNED_KEYS.forEach(merged::remove);
        merged.put("host", smtp.host());
        merged.put("port", String.valueOf(smtp.port()));
        merged.put("from", smtp.from());
        if (smtp.fromDisplayName() != null && !smtp.fromDisplayName().isBlank()) {
            merged.put("fromDisplayName", smtp.fromDisplayName());
        }
        merged.put("starttls", String.valueOf(smtp.starttls()));
        merged.put("ssl", String.valueOf(smtp.ssl()));
        merged.put("auth", String.valueOf(smtp.username() != null));
        if (smtp.username() != null) {
            merged.put("user", smtp.username());
            if (smtp.password() != null) {
                merged.put("password", smtp.password());
            }
        }
        return merged;
    }

    /** The SmtpServer the realm owner sees, from the map Keycloak returns (see KeycloakRealms.smtpServer). */
    default SmtpServer toSmtpServer(Map<String, String> smtpServer, boolean managedByConfiguration) {
        Map<String, String> server = smtpServer == null ? Map.of() : smtpServer;
        boolean auth = "true".equals(server.get("auth"));
        String password = server.get("password");
        return new SmtpServer(
                blankToNull(server.get("host")),
                parsePort(server.get("port")),
                server.get("from"),
                blankToNull(server.get("fromDisplayName")),
                auth ? server.get("user") : null,
                auth && password != null && !password.isEmpty(),
                "true".equals(server.get("starttls")),
                "true".equals(server.get("ssl")),
                managedByConfiguration);
    }

    /** The settings sent to {@link #merge}, from the update request: blank optional fields normalized to null. */
    default SmtpSettings toSettings(UpdateSmtpRequest request, String keptPassword) {
        String username = blankToNull(request.username());
        String password = username == null ? null : (request.password() != null ? request.password() : keptPassword);
        return new SmtpSettings(request.host(), request.port(), request.from(), blankToNull(request.fromDisplayName()),
                username, password, request.starttls(), request.ssl());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** Null when absent or not a number (defensive: Keycloak always stores it as a numeric string). */
    private static Integer parsePort(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
