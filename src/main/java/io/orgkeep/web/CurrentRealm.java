package io.orgkeep.web;

import io.orgkeep.config.OrgkeepConfig;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.RequiredArgsConstructor;

/**
 * Realm the REST endpoints work on.
 * <p>
 * The core serves a single realm (orgkeep.realm-name), and OIDC only accepts tokens issued by that realm,
 * so the configured realm and the realm of the caller's token are the same. This bean is the single place
 * to change if the realm must one day be read from the token instead.
 */
@ApplicationScoped
@RequiredArgsConstructor
public class CurrentRealm {
    private final OrgkeepConfig config;

    public String name() {
        return config.realmName()
                .orElseThrow(() -> new IllegalStateException("orgkeep.realm-name is required to serve the REST API"));
    }
}
