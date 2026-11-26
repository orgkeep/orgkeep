package org.orgkeep.components.organisation.seats;

import jakarta.enterprise.context.ApplicationScoped;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.representations.idm.OrganizationRepresentation;
import org.orgkeep.api.SeatService;
import org.orgkeep.api.model.Seats;
import org.orgkeep.api.model.UpdateSeatsRequest;
import org.orgkeep.components.lock.HeldLocks;
import org.orgkeep.components.lock.LockMode;
import org.orgkeep.components.lock.LockRequest;
import org.orgkeep.components.lock.ResourceLocks;
import org.orgkeep.repository.keycloak.KeycloakOrgs;

@Slf4j
@ApplicationScoped
@RequiredArgsConstructor
public class SeatServiceImpl implements SeatService {
    private final KeycloakOrgs orgs;
    private final SeatGuard seatGuard;
    private final ResourceLocks locks;

    /** A read: no lock. */
    @Override
    public Seats get(String realm, String orgName) {
        OrganizationRepresentation org = orgs.getByName(realm, orgName);
        return seatGuard.seats(realm, org);
    }

    /**
     * Writes the whole organisation, like every other writer of its attributes (DisabledMembers, AssignedProducts):
     * ORG locked exclusively, short wait. Allowed whatever the state of the organisation, disabled included.
     */
    @Override
    public Seats update(String realm, String orgName, UpdateSeatsRequest request) {
        try (HeldLocks ignored = locks.acquire(LockRequest.org(realm, orgName, LockMode.EXCLUSIVE))) {
            OrganizationRepresentation org = orgs.getByName(realm, orgName);
            SeatLimits.set(org, request.maxMembers(), request.maxServiceAccounts());
            orgs.update(realm, org);
            log.info("Seats of organisation '{}' in realm '{}' set to maxMembers={}, maxServiceAccounts={}",
                    orgName, realm, request.maxMembers(), request.maxServiceAccounts());
            return seatGuard.seats(realm, org);
        }
    }
}
