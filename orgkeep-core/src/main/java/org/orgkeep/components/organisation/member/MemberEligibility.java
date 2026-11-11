package org.orgkeep.components.organisation.member;

import org.orgkeep.api.exceptions.MemberOfAnotherOrgException;
import org.orgkeep.api.exceptions.PrivilegedUserException;
import org.orgkeep.api.model.RealmRole;
import org.orgkeep.repository.keycloak.KeycloakClients;
import org.orgkeep.repository.keycloak.KeycloakOrgs;
import org.orgkeep.repository.keycloak.KeycloakUsers;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Which existing users may become members of an organisation: the rules shared by the members added by an org admin
 * (OrgMemberServiceImpl.add) and the owner of a new org (OrgServiceImpl.create), and the privileged users that
 * disabling an org leaves enabled.
 * <p>
 * A privileged user works for the realm owner: it holds, effectively (directly, through a composite role or a group),
 * a realm role of Orgkeep ({@link RealmRole}: realm-owner, realm-admin, realm-user) or any role of the realm-management
 * client (an administrator of the realm in the Keycloak console). Any other realm role does not count.
 */
@Slf4j
@ApplicationScoped
@RequiredArgsConstructor
public class MemberEligibility {
    /** The Keycloak client whose roles give rights on the realm in the console (realm-admin, manage-users...). */
    static final String REALM_MANAGEMENT = "realm-management";

    private static final Set<String> PRIVILEGED_REALM_ROLES = Arrays.stream(RealmRole.values())
            .map(RealmRole::getRoleName)
            .collect(Collectors.toUnmodifiableSet());

    private final KeycloakOrgs orgs;
    private final KeycloakClients clients;
    private final KeycloakUsers users;

    /**
     * Checks that an existing user may join the organisation orgName. Refusals, in this order:
     * <ol>
     *     <li>already a member of orgName: not refused here, the caller reports it in its own terms (the member
     *     already exists, or the organisation already exists);</li>
     *     <li>a privileged user: {@link PrivilegedUserException};</li>
     *     <li>a member of another organisation: {@link MemberOfAnotherOrgException}.</li>
     * </ol>
     * Both exceptions have the same message, the reason is logged.
     */
    public void checkReusable(String realm, String orgName, String userId, String email) {
        List<String> userOrgs = orgs.organisationsOf(realm, userId);
        if (userOrgs.contains(orgName)) {
            return;
        }
        // || stops at the first true: the realm-management client is only looked up without a realm role
        if (hasPrivilegedRealmRole(realm, userId)
                || hasRealmManagementRole(realm, userId, realmManagementUuid(realm))) {
            log.info("User {} cannot join organisation '{}' in realm '{}': privileged user", email, orgName, realm);
            throw new PrivilegedUserException(email);
        }
        if (!userOrgs.isEmpty()) {
            log.info("User {} cannot join organisation '{}' in realm '{}': member of '{}'",
                    email, orgName, realm, userOrgs.getFirst());
            throw new MemberOfAnotherOrgException(email);
        }
    }

    /**
     * The privileged users among userIds. The realm-management client is looked up at most once for all of them,
     * and only if a user has none of the realm roles.
     */
    public Set<String> privilegedAmong(String realm, Collection<String> userIds) {
        if (userIds.isEmpty()) {
            return Set.of();
        }
        Set<String> privileged = new HashSet<>();
        // looked up at the first user without a realm role, then reused
        Optional<String> realmManagementUuid = null;
        for (String userId : userIds) {
            if (hasPrivilegedRealmRole(realm, userId)) {
                privileged.add(userId);
                continue;
            }
            if (realmManagementUuid == null) {
                realmManagementUuid = realmManagementUuid(realm);
            }
            if (hasRealmManagementRole(realm, userId, realmManagementUuid)) {
                privileged.add(userId);
            }
        }
        return privileged;
    }

    private boolean hasPrivilegedRealmRole(String realm, String userId) {
        return users.effectiveRealmRoles(realm, userId).stream().anyMatch(PRIVILEGED_REALM_ROLES::contains);
    }

    private boolean hasRealmManagementRole(String realm, String userId, Optional<String> realmManagementUuid) {
        // every realm has this client: absent only if it was deleted
        return realmManagementUuid
                .map(clientUuid -> !users.effectiveClientRoles(realm, userId, clientUuid).isEmpty())
                .orElse(false);
    }

    private Optional<String> realmManagementUuid(String realm) {
        return clients.findUuid(realm, REALM_MANAGEMENT);
    }
}
