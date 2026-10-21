package io.orgkeep.components.organisation.usergroup.team;

import io.orgkeep.api.exceptions.OperationNotAllowedException;
import io.orgkeep.api.model.TeamGrants;
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
 * For a given product, a user is in at most one team with a quota: the API of the realm owner charges every pool of
 * the token, there must be no doubt about the team to charge, and no usage counted twice.
 */
@ApplicationScoped
@RequiredArgsConstructor
public class TeamQuotas {
    private final KeycloakGroups groups;
    private final KeycloakUsers users;
    private final TeamAlignment alignment;
    private final TeamMapper teamMapper;

    /**
     * Before the team gets a quota for the product.
     *
     * @throws OperationNotAllowedException if a member of the team is in another team with a quota for the product
     */
    public void checkQuota(String realm, String orgName, String teamId, String catalog, String product) {
        String grant = TeamGrants.product(catalog, product);
        List<GroupRepresentation> withQuota = alignment.teams(realm, orgName).stream()
                .filter(team -> !team.getId().equals(teamId) && teamMapper.productsWithQuota(attributes(team)).contains(grant))
                .toList();
        if (withQuota.isEmpty()) {
            return;
        }
        Set<String> members = groups.memberIds(realm, teamId);
        for (GroupRepresentation other : withQuota) {
            Set<String> both = new HashSet<>(groups.memberIds(realm, other.getId()));
            both.retainAll(members);
            if (!both.isEmpty()) {
                throw conflict(realm, both.iterator().next(), other.getName(), catalog, product);
            }
        }
    }

    /**
     * Before the user joins the team.
     *
     * @throws OperationNotAllowedException if the team has a quota for a product, and the user is already in another
     *                                      team with a quota for it
     */
    public void checkJoin(String realm, String orgName, String teamId, String userId) {
        List<GroupRepresentation> teams = alignment.teams(realm, orgName);
        Set<String> joined = teams.stream()
                .filter(team -> team.getId().equals(teamId))
                .findFirst()
                .map(team -> teamMapper.productsWithQuota(attributes(team)))
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
            for (String grant : teamMapper.productsWithQuota(attributes(other))) {
                if (joined.contains(grant)) {
                    String[] product = TeamGrants.productOf(grant);
                    throw conflict(realm, userId, other.getName(), product[0], product[1]);
                }
            }
        }
    }

    private OperationNotAllowedException conflict(String realm, String userId, String otherTeam, String catalog,
                                                  String product) {
        String user = users.find(realm, userId).map(UserRepresentation::getUsername).orElse(userId);
        return new OperationNotAllowedException(("User '%s' is already in the team '%s', which has a quota for the "
                + "product '%s' of the catalog '%s': a member uses at most one quota of team per product")
                .formatted(user, otherTeam, product, catalog));
    }

    private static Map<String, List<String>> attributes(GroupRepresentation group) {
        return group.getAttributes() == null ? Map.of() : group.getAttributes();
    }
}
