package org.orgkeep.api.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param name name of the group under /{org}/user-groups, a lowercase slug (kebab-case) like the other names
 *             of Orgkeep: it is part of the group path
 */
public record CreateUserGroupRequest(
    @NotBlank
    @Size(max = 50)
    @Slug
    String name){ }
