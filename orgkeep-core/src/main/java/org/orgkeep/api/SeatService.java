package org.orgkeep.api;

import org.orgkeep.api.model.Seats;
import org.orgkeep.api.model.UpdateSeatsRequest;

/**
 * Seat limits of an organisation: a maximum number of members and a maximum number of service accounts, set by the
 * realm owner, read by its own org admins too. See org.orgkeep.components.organisation.seats.SeatGuard for how a
 * member and a service account are counted, and the checks made when one is added.
 */
public interface SeatService {
    /** @throws org.orgkeep.api.exceptions.OrgNotFoundException if no organisation has this name */
    Seats get(String realm, String orgName);

    /**
     * @throws org.orgkeep.api.exceptions.OrgNotFoundException if no organisation has this name
     * @throws org.orgkeep.api.exceptions.ResourceBusyException if a change is in progress on the organisation,
     *         after a short wait
     */
    Seats update(String realm, String orgName, UpdateSeatsRequest request);
}
