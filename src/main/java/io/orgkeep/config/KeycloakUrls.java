package io.orgkeep.config;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * URLs of Keycloak as the browsers and the applications of the realm owner see it, to show them to the realm owner.
 * Not the URL the core calls: in Docker, that one is internal (http://keycloak:8080).
 */
@ApplicationScoped
public class KeycloakUrls {
    private static final String REALMS = "/realms/";

    @Inject
    OrgkeepConfig config;

    // the realm URL of the OIDC tenant: <keycloak>/realms/<realm>
    @ConfigProperty(name = "quarkus.oidc.auth-server-url")
    String authServerUrl;

    /** orgkeep.keycloak-public-url, or else the base of quarkus.oidc.auth-server-url (fine in dev, internal in Docker). */
    public String publicUrl() {
        String url = config.keycloakPublicUrl().orElseGet(() -> {
            int realms = authServerUrl.indexOf(REALMS);
            return realms < 0 ? authServerUrl : authServerUrl.substring(0, realms);
        });
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** The "iss" claim of the tokens of the realm, also the base of its OpenID discovery document. */
    public String issuer(String realm) {
        return publicUrl() + REALMS + realm;
    }

    /** The token introspection endpoint of the realm (RFC 7662), called by the APIs of the realm owner. */
    public String introspection(String realm) {
        return issuer(realm) + "/protocol/openid-connect/token/introspect";
    }

    /** The admin console of the realm: a realm owner logs in there with their own account. */
    public String console(String realm) {
        return publicUrl() + "/admin/" + realm + "/console/";
    }
}
