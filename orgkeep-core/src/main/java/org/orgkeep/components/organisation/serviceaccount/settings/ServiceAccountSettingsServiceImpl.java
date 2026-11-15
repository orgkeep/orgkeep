package org.orgkeep.components.organisation.serviceaccount.settings;

import jakarta.enterprise.context.ApplicationScoped;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.orgkeep.api.ServiceAccountSettingsService;
import org.orgkeep.api.model.LightweightTokens;
import org.orgkeep.api.model.ServiceAccountSettings;
import org.orgkeep.api.model.UpdateServiceAccountSettingsRequest;
import org.orgkeep.components.lock.HeldLocks;
import org.orgkeep.components.lock.LockMode;
import org.orgkeep.components.lock.LockRequest;
import org.orgkeep.components.lock.ResourceLocks;
import org.orgkeep.components.organisation.serviceaccount.ServiceAccounts;
import org.orgkeep.components.realm.lightweight.LightweightClients;
import org.orgkeep.repository.keycloak.KeycloakClients;
import org.orgkeep.repository.keycloak.KeycloakOrgs;
import org.orgkeep.repository.keycloak.KeycloakRealms;

/**
 * Settings of the service accounts, for the realm owner: the mode of the tokens of one service account, and the
 * default of the service accounts created afterwards. This service never reads the token of the caller: who may call
 * is decided by the resources.
 */
@Slf4j
@ApplicationScoped
@RequiredArgsConstructor
public class ServiceAccountSettingsServiceImpl implements ServiceAccountSettingsService {
    private final KeycloakRealms realms;
    private final KeycloakClients clients;
    private final KeycloakOrgs orgs;
    private final ServiceAccounts serviceAccounts;
    private final LightweightClients lightweightClients;
    private final ResourceLocks locks;

    @Override
    public ServiceAccountSettings realmDefaults(String realm) {
        return new ServiceAccountSettings(realms.attribute(realm, LightweightTokens.SERVICE_ACCOUNTS_REALM_ATTRIBUTE)
                .map(Boolean::parseBoolean)
                .orElse(false));
    }

    /** No lock: one realm attribute, read by the creations only (a creation takes what it read under its own lock). */
    @Override
    public ServiceAccountSettings updateRealmDefaults(String realm, UpdateServiceAccountSettingsRequest request) {
        realms.setAttribute(realm, LightweightTokens.SERVICE_ACCOUNTS_REALM_ATTRIBUTE,
                request.lightweightTokens().toString());
        log.info("Default of the service accounts of realm '{}' updated: lightweight tokens {}", realm,
                request.lightweightTokens());
        return realmDefaults(realm);
    }

    /** No lock, a read. */
    @Override
    public ServiceAccountSettings get(String realm, String orgName, String name) {
        orgs.getByName(realm, orgName);
        ServiceAccounts.Resolved resolved = serviceAccounts.resolve(realm, orgName, name);
        return new ServiceAccountSettings(LightweightClients.isLightweight(resolved.client()));
    }

    /**
     * Same locks as ServiceAccountServiceImpl.update (ORG shared, the service-account user exclusive, short wait): it
     * rewrites the whole client. Allowed while the service account or the org is disabled, it only writes the client.
     */
    @Override
    public ServiceAccountSettings update(String realm, String orgName, String name,
                                         UpdateServiceAccountSettingsRequest request) {
        orgs.getByName(realm, orgName);
        ServiceAccounts.Resolved first = serviceAccounts.resolve(realm, orgName, name);
        try (HeldLocks ignored = locks.acquire(LockRequest.org(realm, orgName, LockMode.SHARED),
                LockRequest.userById(realm, first.user().getId(), LockMode.EXCLUSIVE))) {
            // a deletion may have passed between the first resolution and the locks
            ServiceAccounts.Resolved resolved = serviceAccounts.resolve(realm, orgName, name);
            String clientUuid = resolved.client().getId();
            lightweightClients.apply(realm, clientUuid, request.lightweightTokens());
            log.info("Service account '{}' of organisation '{}' in realm '{}': lightweight tokens {}", name, orgName,
                    realm, request.lightweightTokens());
            return new ServiceAccountSettings(LightweightClients.isLightweight(clients.get(realm, clientUuid)));
        }
    }
}
