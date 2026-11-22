package org.orgkeep.repository.keycloak;

import io.quarkus.keycloak.admin.client.common.runtime.KeycloakAdminClientConfig;
import io.quarkus.keycloak.admin.client.common.runtime.KeycloakAdminClientConfigUtil;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;

/**
 * Builds the admin client of Orgkeep once for the whole application, instead of the request-scoped bean that
 * {@code quarkus-keycloak-admin-rest-client} registers by default (one {@code Keycloak}, one grant of a token, per
 * HTTP request). Its {@code TokenManager} is thread-safe (grant and refresh are synchronized) and renews the token
 * itself when it is close to expiring, so an Orgkeep request makes no grant as long as the token is still valid.
 * <p>
 * This bean overrides the extension's default one (same type, no qualifier): Arc only falls back to a bean marked
 * {@code @DefaultBean} when no other bean of the same type is found.
 * <p>
 * Built the same way as the extension's own {@code KeycloakAdminRestClientRecorder.createAdminClient()}, from the
 * same {@code quarkus.keycloak.admin-client.*} configuration (injected as {@link KeycloakAdminClientConfig}, not
 * re-read property by property): the grant type and the defaults (username/password, realm, client id) stay exactly
 * the ones the extension itself would use.
 */
@Singleton
@RequiredArgsConstructor
public class KeycloakAdminClientProducer {
    private final KeycloakAdminClientConfig config;

    @Produces
    @Singleton
    public Keycloak keycloak() {
        KeycloakAdminClientConfigUtil.validate(config);
        String serverUrl = config.serverUrl().orElseThrow(() -> new IllegalStateException(
                "quarkus.keycloak.admin-client.server-url must be set to build the Keycloak admin client"));
        return KeycloakBuilder.builder()
                .serverUrl(serverUrl)
                .realm(config.realm())
                .clientId(config.clientId())
                .clientSecret(config.clientSecret().orElse(null))
                .grantType(config.grantType().asString())
                .username(config.username().orElse(null))
                .password(config.password().orElse(null))
                .scope(config.scope().orElse(null))
                .build();
    }

    /**
     * Closes the underlying HTTP client at shutdown. {@code logout()} (called first, see {@link Keycloak#close()})
     * only ends something with a master/password grant (tests, SaaS): a client_credentials grant (prod, dev) has no
     * session to end, there is nothing to log out of.
     */
    public void close(@Disposes Keycloak keycloak) {
        keycloak.close();
    }
}
