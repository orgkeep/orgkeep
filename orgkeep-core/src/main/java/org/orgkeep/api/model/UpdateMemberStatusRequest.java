package org.orgkeep.api.model;

/**
 * @param enabled false disables the member: they can no longer log in, and their sessions are closed. True enables
 *                them again
 */
public record UpdateMemberStatusRequest(boolean enabled){ }
