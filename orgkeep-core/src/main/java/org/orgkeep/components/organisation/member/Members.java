package org.orgkeep.components.organisation.member;

import org.orgkeep.components.organisation.serviceaccount.ServiceAccounts;
import org.orgkeep.repository.keycloak.KeycloakOrgs;
import org.keycloak.representations.idm.OrganizationRepresentation;

/**
 * Small helpers shared by the services of this package that manage members of an organisation.
 */
final class Members {
    private Members() {
    }

    /**
     * A member of the org who is a person: the service accounts are members of the Keycloak organisation too, and
     * every member endpoint answers as if they were not (404).
     */
    static boolean isPerson(KeycloakOrgs orgs, ServiceAccounts serviceAccounts, String realm,
                             OrganizationRepresentation org, String userId) {
        return orgs.isMember(realm, org.getId(), userId)
                && !serviceAccounts.isServiceAccountUser(realm, org.getName(), userId);
    }
}
