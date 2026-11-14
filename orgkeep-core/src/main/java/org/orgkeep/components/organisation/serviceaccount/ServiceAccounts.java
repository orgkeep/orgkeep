package org.orgkeep.components.organisation.serviceaccount;

import jakarta.enterprise.context.ApplicationScoped;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.orgkeep.api.model.OrgClients;
import org.orgkeep.api.model.OrgGroups;
import org.orgkeep.components.catalog.CatalogSetup;
import org.orgkeep.repository.keycloak.KeycloakClients;
import org.orgkeep.repository.keycloak.KeycloakGroups;
import org.orgkeep.repository.keycloak.KeycloakUsers;

import java.util.List;
import java.util.Optional;

/**
 * The service accounts of an org, found through the group /{org}/service-accounts: what the other parts of the core
 * (org lifecycle, product unassignment) need to reach all of them. A service account is the client "org-{org}.{name}"
 * with the attribute "org", and its user, a member of the group: both must agree.
 * <p>
 * No lock here: the callers hold the lock of the org they need.
 */
@Slf4j
@ApplicationScoped
@RequiredArgsConstructor
public class ServiceAccounts {
    private final KeycloakClients clients;
    private final KeycloakGroups groups;
    private final CatalogSetup catalogSetup;
    private final KeycloakUsers keycloakUsers;

    /** The members of the group of the org: the users of its service accounts. Empty for an org without the group. */
    public List<UserRepresentation> users(String realm, String orgName) {
        return groups.findId(realm, OrgGroups.serviceAccounts(orgName))
                .map(groupId -> groups.members(realm, groupId))
                .orElse(List.of());
    }

    /**
     * The client of a user of the group, if it really is the service account of this org: the username
     * "service-account-org-{org}.{name}" names a client that carries the attribute "org" of this org. A client created
     * by hand in the console, with a matching clientId, is not one.
     */
    public Optional<ClientRepresentation> clientOf(String realm, String orgName, UserRepresentation user) {
        String username = user.getUsername();
        if (username == null || !username.startsWith(OrgClients.SERVICE_ACCOUNT_USERNAME_PREFIX)) {
            return Optional.empty();
        }
        String clientId = username.substring(OrgClients.SERVICE_ACCOUNT_USERNAME_PREFIX.length());
        if (!clientId.startsWith(OrgClients.serviceAccountClientPrefix(orgName))) {
            return Optional.empty();
        }
        return clients.find(realm, clientId).filter(client -> belongsTo(client, orgName));
    }

    /** The name of the service account of this clientId, e.g. "billing" for "org-acme.billing". */
    public static String nameOf(String orgName, String clientId) {
        return clientId.substring(OrgClients.serviceAccountClientPrefix(orgName).length());
    }

    /** The attribute "org" of the client names this org. */
    public static boolean belongsTo(ClientRepresentation client, String orgName) {
        return client.getAttributes() != null && orgName.equals(client.getAttributes().get(OrgClients.ORG_ATTRIBUTE));
    }

    /**
     * Whether the user is the user of a service account (Keycloak names it "service-account-{clientId}"). Service
     * accounts are members of the Keycloak organisation, so everything that walks its members must leave them out:
     * they are not people.
     */
    public static boolean isServiceAccountUser(UserRepresentation user) {
        return user.getUsername() != null && user.getUsername().startsWith(OrgClients.SERVICE_ACCOUNT_USERNAME_PREFIX);
    }

    /** Same, for a user known by its id. An unknown user is not one. */
    public boolean isServiceAccountUser(String realm, String userId) {
        return keycloakUsers.find(realm, userId).map(ServiceAccounts::isServiceAccountUser).orElse(false);
    }

    /**
     * Deletes the client of every service account of the org: Keycloak deletes its user and its group memberships with
     * it. Called before the group tree of the org goes, which is the only way to find them. A failure half way leaves
     * the rest where it is: the same call, repeated, finishes the job.
     *
     * @return the number of service accounts deleted
     */
    public int deleteAll(String realm, String orgName) {
        int deleted = 0;
        for (UserRepresentation user : users(realm, orgName)) {
            Optional<ClientRepresentation> client = clientOf(realm, orgName, user);
            if (client.isPresent()) {
                clients.delete(realm, client.get().getId());
                deleted++;
            } else {
                log.warn("User {} of the group {} is not the user of a service account of the organisation '{}' in "
                        + "realm '{}': left alone", user.getUsername(), OrgGroups.serviceAccounts(orgName), orgName, realm);
            }
        }
        return deleted;
    }

    /**
     * The catalog goes back to optional on every service account of the org that had it by default: the org has no
     * product in it any more. The direct reserved roles of the service accounts stay, as they do for the users.
     */
    public void makeCatalogOptional(String realm, String orgName, String catalog) {
        String scopeId = catalogSetup.catalogScopeIds(realm).get(catalog);
        if (scopeId == null) {
            return;
        }
        for (UserRepresentation user : users(realm, orgName)) {
            clientOf(realm, orgName, user)
                    .filter(client -> clients.defaultScopeIds(realm, client.getId()).contains(scopeId))
                    .ifPresent(client -> clients.makeOptionalScope(realm, client.getId(), scopeId));
        }
    }
}
