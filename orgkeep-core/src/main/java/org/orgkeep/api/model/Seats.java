package org.orgkeep.api.model;

/**
 * The seat limits of an organisation and how many are used, for its members and for its service accounts
 * separately: a service account never takes a member seat, a member never takes a service-account slot. See
 * org.orgkeep.api.SeatService.
 *
 * @param members         used and maximum number of members. Every state counts (enabled, disabled, pending
 *                        invitation), owners included: only removing a member frees a seat
 * @param serviceAccounts used and maximum number of service accounts
 */
public record Seats(SeatCount members, SeatCount serviceAccounts) {

    /**
     * @param used current count
     * @param max  maximum allowed, null = no limit. May be lower than used: lowering a limit removes nobody, only
     *             new additions are refused until the count drops below it
     */
    public record SeatCount(long used, Integer max) { }
}
