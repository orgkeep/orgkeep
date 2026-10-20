package io.orgkeep.api.model;

/** A user holding a role directly (not through a group). */
public record RoleHolder(
    String userId,
    String username,
    String email){ }
