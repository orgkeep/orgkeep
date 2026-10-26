package io.orgkeep.components.organisation.usergroup.team;

import io.orgkeep.api.exceptions.OperationNotAllowedException;
import io.orgkeep.components.organisation.usergroup.team.TeamMapper.QuotaScope;
import io.orgkeep.repository.keycloak.KeycloakGroups;
import io.orgkeep.repository.keycloak.KeycloakUsers;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.RequiredArgsConstructor;
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.UserRepresentation;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * For a given simple product, or a given metered feature of a product, a user is in at most one team with a quota: the
 * API of the realm owner charges every pool of the token, there must be no doubt about the team to charge, and no usage
 * counted twice.
 */
@ApplicationScoped
@RequiredArgsConstructor
public class TeamQuotas {
    private final KeycloakGroups groups;
    private final KeycloakUsers users;
    private final TeamAlignment alignment;
    private final TeamMapper teamMapper;

    /**
     * Before the team gets quotas: for the product (simple product), or for some of its metered features.
     *
     * @param scopes the quotas the team gets
     * @throws OperationNotAllowedException if a member of the team is in another team with a quota for one of them
     */
    public void checkQuotas(String realm, String orgName, String teamId, Set<QuotaScope> scopes) {
        if (scopes.isEmpty()) {
            return;
        }
        List<GroupRepresentation> others = alignment.teams(realm, orgName).stream()
                .filter(team -> !team.getId().equals(teamId))
                .toList();
        Set<String> members = null;
        for (GroupRepresentation other : others) {
            Set<QuotaScope> shared = new HashSet<>(teamMapper.quotaScopes(attributes(other)));
            shared.retainAll(scopes);
            if (shared.isEmpty()) {
                continue;
            }
            // read only once a team with a quota in common is found
            if (members == null) {
                members = groups.memberIds(realm, teamId);
            }
            Set<String> both = new HashSet<>(groups.memberIds(realm, other.getId()));
            both.retainAll(members);
            if (!both.isEmpty()) {
                throw conflict(realm, both.iterator().next(), other.getName(), shared.iterator().next());
            }
        }
    }

    /**
     * Before the user joins the team.
     *
     * @throws OperationNotAllowedException if the team has a quota for a product or a metered feature, and the user is
     *                                      already in another team with a quota for it
     */
    public void checkJoin(String realm, String orgName, String teamId, String userId) {
        List<GroupRepresentation> teams = alignment.teams(realm, orgName);
        Set<QuotaScope> joined = teams.stream()
                .filter(team -> team.getId().equals(teamId))
                .findFirst()
                .map(team -> teamMapper.quotaScopes(attributes(team)))
                .orElse(Set.of());
        if (joined.isEmpty()) {
            return;
        }
        Set<String> userGroupIds = new HashSet<>();
        groups.groupsOf(realm, userId).forEach(group -> userGroupIds.add(group.getId()));
        for (GroupRepresentation other : teams) {
            if (other.getId().equals(teamId) || !userGroupIds.contains(other.getId())) {
                continue;
            }
            for (QuotaScope scope : teamMapper.quotaScopes(attributes(other))) {
                if (joined.contains(scope)) {
                    throw conflict(realm, userId, other.getName(), scope);
                }
            }
        }
    }

    private OperationNotAllowedException conflict(String realm, String userId, String otherTeam, QuotaScope scope) {
        String user = users.find(realm, userId).map(UserRepresentation::getUsername).orElse(userId);
        if (scope.feature() == null) {
            return new OperationNotAllowedException(("User '%s' is already in the team '%s', which has a quota for the "
                    + "product '%s' of the catalog '%s': a member uses at most one quota of team per product")
                    .formatted(user, otherTeam, scope.product(), scope.catalog()));
        }
        return new OperationNotAllowedException(("User '%s' is already in the team '%s', which has a quota for the "
                + "feature '%s' of the product '%s' of the catalog '%s': a member uses at most one quota of team per "
                + "feature").formatted(user, otherTeam, scope.feature(), scope.product(), scope.catalog()));
    }

    private static Map<String, List<String>> attributes(GroupRepresentation group) {
        return group.getAttributes() == null ? Map.of() : group.getAttributes();
    }
}
