package org.orgkeep.api.model;

import java.time.Instant;

/**
 * A suspension set by the realm owner on a member or a service account of an organisation: the account is disabled
 * and its sessions end, and an org admin cannot enable it again.
 *
 * @param reason      free text, 1 to 500 characters
 * @param suspendedAt when it was first suspended; kept across a later suspension that only replaces the reason.
 *                    Null when the underlying attribute was set by anything other than this code (a console edit)
 *                    and could not be parsed (see SuspensionAttributes)
 * @param suspendedBy user id (sub) of the realm owner who suspended; null for an org admin, shown to the realm
 *                    owner only (see SuspensionViews)
 */
public record Suspension(String reason, Instant suspendedAt, String suspendedBy) { }
