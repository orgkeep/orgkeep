package org.orgkeep.api.model;

import jakarta.validation.constraints.PositiveOrZero;

/**
 * Replaces both seat limits of an organisation. A null value removes the corresponding limit (no limit).
 *
 * @param maxMembers         null = no limit, otherwise a non-negative integer; 0 freezes the organisation to its
 *                           current members
 * @param maxServiceAccounts null = no limit, otherwise a non-negative integer; 0 freezes the organisation to its
 *                           current service accounts
 */
public record UpdateSeatsRequest(@PositiveOrZero Integer maxMembers, @PositiveOrZero Integer maxServiceAccounts) { }
