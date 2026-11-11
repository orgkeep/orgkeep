package org.orgkeep.api.model;

/**
 * @param enabled false disables the organisation and its members: they can no longer log in
 */
public record UpdateOrgStatusRequest(boolean enabled){ }
