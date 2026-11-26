package org.orgkeep.api;

import org.orgkeep.api.model.OrgMember;
import org.orgkeep.api.model.ServiceAccount;

/**
 * Suspensions set and lifted by the realm owner only, on a member (owners included) or a service account of an
 * organisation: the account is disabled and its sessions end, and an org admin cannot enable it again. Lifting
 * restores what the organisation had decided (see docs/design/members.md, "Suspension par le realm owner").
 */
public interface AccountSuspensionService {

    /**
     * Suspends the member: writes the suspension record (or only its reason if one already exists, keeping the
     * date, the suspender and the organisation's choice), then disables the account if it is enabled and ends its
     * sessions. Idempotent and resumable: a failure after the record is written leaves the account possibly still
     * enabled, and the same call finishes the job.
     *
     * @throws org.orgkeep.api.exceptions.OrgNotFoundException if no organisation has this name
     * @throws org.orgkeep.api.exceptions.MemberNotFoundException if the user is not a person member of this
     *         organisation (a service account included, or a member of another organisation)
     * @throws org.orgkeep.api.exceptions.OperationNotAllowedException if the member is privileged (a neutral
     *         message that does not name the role)
     * @throws org.orgkeep.api.exceptions.ResourceBusyException if a change is in progress, after a short wait
     * @throws IllegalStateException if the realm's user profile does not declare the suspension attributes (a
     *         realm created before this feature): the account is left untouched
     */
    OrgMember suspendMember(String realm, String orgName, String userId, String reason, String suspenderId);

    /**
     * Lifts the suspension, if there is one: idempotent, nothing changes when the member is not suspended. Enables
     * the member again only if the organisation is enabled, the member is not listed as disabled by it, and the
     * organisation's choice at the time of the suspension (or since) was "enabled"; otherwise it stays disabled.
     * Never writes the organisation.
     *
     * @throws org.orgkeep.api.exceptions.OrgNotFoundException if no organisation has this name
     * @throws org.orgkeep.api.exceptions.MemberNotFoundException if the user is not a person member of this
     *         organisation
     * @throws org.orgkeep.api.exceptions.ResourceBusyException if a change is in progress, after a short wait
     */
    OrgMember liftMember(String realm, String orgName, String userId);

    /** Same rules as {@link #suspendMember}, for a service account of the organisation. */
    ServiceAccount suspendServiceAccount(String realm, String orgName, String name, String reason, String suspenderId);

    /** Same rules as {@link #liftMember}, for a service account of the organisation. */
    ServiceAccount liftServiceAccount(String realm, String orgName, String name);
}
