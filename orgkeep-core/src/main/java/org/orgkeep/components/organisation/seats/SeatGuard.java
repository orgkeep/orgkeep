package org.orgkeep.components.organisation.seats;

import jakarta.enterprise.context.ApplicationScoped;
import lombok.RequiredArgsConstructor;
import org.keycloak.representations.idm.OrganizationRepresentation;
import org.orgkeep.api.exceptions.SeatLimitReachedException;
import org.orgkeep.api.model.Seats;
import org.orgkeep.components.organisation.serviceaccount.ServiceAccounts;
import org.orgkeep.repository.keycloak.KeycloakOrgs;

/**
 * Checks and reports the seats of an organisation, against the limits of {@link SeatLimits}: members (every state
 * counts, owners included; the count is {@code ServiceAccounts.peopleCount}, Keycloak's member count minus the
 * service accounts, so it agrees with the organisation list and the member list) and service accounts (the users
 * of the group /{org}/service-accounts).
 * <p>
 * No lock here: the caller holds the locks of the organisation (an exclusive SEATS lock for a check that may refuse
 * the add or the creation).
 */
@ApplicationScoped
@RequiredArgsConstructor
public class SeatGuard {
    private final KeycloakOrgs orgs;
    private final ServiceAccounts serviceAccounts;

    /**
     * No limit: returns without counting, no extra Keycloak call.
     *
     * @throws SeatLimitReachedException if the organisation has no member seat left
     */
    public void checkMemberSeat(String realm, OrganizationRepresentation org) {
        Integer max = SeatLimits.maxMembers(org).orElse(null);
        if (max == null) {
            return;
        }
        long used = memberCount(realm, org);
        if (used >= max) {
            throw SeatLimitReachedException.members(org.getName(), used, max);
        }
    }

    /**
     * No limit: returns without counting, no extra Keycloak call.
     *
     * @throws SeatLimitReachedException if the organisation has no service-account slot left
     */
    public void checkServiceAccountSeat(String realm, OrganizationRepresentation org) {
        Integer max = SeatLimits.maxServiceAccounts(org).orElse(null);
        if (max == null) {
            return;
        }
        long used = serviceAccountCount(realm, org);
        if (used >= max) {
            throw SeatLimitReachedException.serviceAccounts(org.getName(), used, max);
        }
    }

    /** The current counts and limits of the organisation, whether a limit is set or not: used by SeatServiceImpl. */
    public Seats seats(String realm, OrganizationRepresentation org) {
        return new Seats(
                new Seats.SeatCount(memberCount(realm, org), SeatLimits.maxMembers(org).orElse(null)),
                new Seats.SeatCount(serviceAccountCount(realm, org), SeatLimits.maxServiceAccounts(org).orElse(null)));
    }

    private long memberCount(String realm, OrganizationRepresentation org) {
        return serviceAccounts.peopleCount(realm, org.getName(), orgs.memberCount(realm, org.getId()));
    }

    private long serviceAccountCount(String realm, OrganizationRepresentation org) {
        return serviceAccounts.users(realm, org.getName()).size();
    }
}
