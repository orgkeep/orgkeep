package org.orgkeep.api.exceptions;

/**
 * No seat (member or service account) left in an organisation: the message names the organisation and the numbers,
 * unlike most refusals of this kind, because the caller already administers this organisation.
 */
public class SeatLimitReachedException extends OperationNotAllowedException {
    private SeatLimitReachedException(String message) {
        super(message);
    }

    public static SeatLimitReachedException members(String orgName, long used, int max) {
        return new SeatLimitReachedException(
                "The organisation '%s' has no seat left (%d of %d members)".formatted(orgName, used, max));
    }

    public static SeatLimitReachedException serviceAccounts(String orgName, long used, int max) {
        return new SeatLimitReachedException(
                "The organisation '%s' has no service account left (%d of %d)".formatted(orgName, used, max));
    }
}
