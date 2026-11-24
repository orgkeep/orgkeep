package org.orgkeep.components.organisation.serviceaccount;

import jakarta.enterprise.context.ApplicationScoped;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.orgkeep.api.exceptions.InvalidRequestException;
import org.orgkeep.api.exceptions.ServiceAccountNotFoundException;
import org.orgkeep.api.model.OrgClients;
import org.orgkeep.api.model.OrgGroups;
import org.orgkeep.components.catalog.CatalogSetup;
import org.orgkeep.repository.keycloak.KeycloakClients;
import org.orgkeep.repository.keycloak.KeycloakGroups;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

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

    /** The client and the user of a service account, found and checked by {@link #resolve}. */
    public record Resolved(ClientRepresentation client, UserRepresentation user) { }

    /**
     * The service account of the org with this name. The name is checked first (400): it is part of a clientId and
     * must never point elsewhere. Then, in this order:
     * <ol>
     *     <li>the client "org-{org}.{name}";</li>
     *     <li>its attribute "org" is the org;</li>
     *     <li>it has a service-account user;</li>
     *     <li>this user is a member of /{org}/service-accounts.</li>
     * </ol>
     * Any failure is the same 404.
     */
    public Resolved resolve(String realm, String orgName, String name) {
        requireName(name);
        ServiceAccountNotFoundException notFound = new ServiceAccountNotFoundException(orgName, name);
        ClientRepresentation client = clients.find(realm, OrgClients.serviceAccountClientId(orgName, name))
                .filter(found -> belongsTo(found, orgName))
                .orElseThrow(() -> notFound);
        UserRepresentation user = clients.serviceAccountUser(realm, client.getId()).orElseThrow(() -> notFound);
        if (!isServiceAccountUser(realm, orgName, user.getId())) {
            throw notFound;
        }
        return new Resolved(client, user);
    }

    public static void requireName(String name) {
        if (name == null || name.length() > 50 || !OrgGroups.isServiceAccountName(name)) {
            throw new InvalidRequestException(
                    "The name of a service account is made of lowercase letters, digits and inner hyphens, 50 at most");
        }
    }

    /** Names of the teams the user is a member of, sorted: the user groups of this org under /{org}/user-groups. */
    public List<String> teamsOf(String realm, String orgName, String userId) {
        String prefix = OrgGroups.userGroups(orgName) + "/";
        return groups.groupsOf(realm, userId).stream()
                .map(GroupRepresentation::getPath)
                .filter(path -> OrgGroups.isUserGroup(path) && path.startsWith(prefix))
                .map(path -> path.substring(prefix.length()))
                .sorted()
                .toList();
    }

    /** The name of the service account of a user of the group of the org, null if its username says otherwise. */
    public static String nameOfUser(String orgName, UserRepresentation user) {
        String username = user.getUsername();
        String prefix = OrgClients.SERVICE_ACCOUNT_USERNAME_PREFIX + OrgClients.serviceAccountClientPrefix(orgName);
        return username != null && username.startsWith(prefix) ? username.substring(prefix.length()) : null;
    }

    /**
     * The client whose service-account user this is, empty for a person. Not told by the username alone (a person's is
     * their email, which may start with "service-account-"): the client named by it must exist and have this very
     * user as its service-account user. The admin API does not say it on the user (serviceAccountClientId is not set).
     */
    public Optional<ClientRepresentation> clientOfUser(String realm, UserRepresentation user) {
        String username = user.getUsername();
        if (username == null || !username.startsWith(OrgClients.SERVICE_ACCOUNT_USERNAME_PREFIX)) {
            return Optional.empty();
        }
        return clients.find(realm, username.substring(OrgClients.SERVICE_ACCOUNT_USERNAME_PREFIX.length()))
                .filter(client -> clients.serviceAccountUser(realm, client.getId())
                        .filter(serviceAccountUser -> user.getId().equals(serviceAccountUser.getId())).isPresent());
    }

    /**
     * The org of the service account this user is the user of, empty if it is not one of an org. A user of a client
     * of the console only counts when its client carries the attribute "org" and the user is in the group of that
     * org, however it was added to an organisation.
     */
    public Optional<String> orgOf(String realm, UserRepresentation user) {
        return clientOfUser(realm, user)
                .map(client -> client.getAttributes() == null ? null : client.getAttributes().get(OrgClients.ORG_ATTRIBUTE))
                .filter(org -> isServiceAccountUser(realm, org, user.getId()));
    }

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

    /** Ids of the service-account users of the org: the members of its group. Read once to filter a member list. */
    public Set<String> userIds(String realm, String orgName) {
        return users(realm, orgName).stream().map(UserRepresentation::getId).collect(Collectors.toSet());
    }

    /**
     * People of the org: Keycloak's count of its members minus its service accounts, which are members of the
     * Keycloak organisation too. Used by the organisation list and the unfiltered member list, so the two counts
     * agree (the member list leaves out the members of this very group).
     */
    public long peopleCount(String realm, String orgName, long memberCount) {
        return Math.max(0, memberCount - users(realm, orgName).size());
    }

    /**
     * Whether the user is a service account of this org: a member of its group /{org}/service-accounts, the proof
     * that a service account belongs to the org. Service accounts are members of the Keycloak organisation too, so
     * everything that walks its members must leave them out: they are not people. Not told by the username: a person's
     * username is their email, which may start with "service-account-" like any other.
     */
    public boolean isServiceAccountUser(String realm, String orgName, String userId) {
        String groupPath = OrgGroups.serviceAccounts(orgName);
        return groups.groupsOf(realm, userId).stream().anyMatch(group -> groupPath.equals(group.getPath()));
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
                clients.delete(realm, client.get().getClientId(), client.get().getId());
                deleted++;
            } else {
                log.warn("User {} of the group {} is not the user of a service account of the organisation '{}' in "
                        + "realm '{}': left alone", user.getUsername(), OrgGroups.serviceAccounts(orgName), orgName, realm);
            }
        }
        return deleted;
    }

    /**
     * The catalog becomes an optional scope of every service account of the org that has it neither by default nor
     * as optional: a catalog created after the service account, whose first product the org just got. Idempotent.
     */
    public void makeCatalogAvailable(String realm, String orgName, String catalog) {
        String scopeId = catalogSetup.catalogScopeIds(realm).get(catalog);
        if (scopeId == null) {
            return;
        }
        for (UserRepresentation user : users(realm, orgName)) {
            clientOf(realm, orgName, user)
                    .filter(client -> !clients.defaultScopeIds(realm, client.getId()).contains(scopeId)
                            && !clients.optionalScopeIds(realm, client.getId()).contains(scopeId))
                    .ifPresent(client -> clients.makeOptionalScope(realm, client.getId(), scopeId));
        }
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
