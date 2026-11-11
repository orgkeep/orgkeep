package org.orgkeep.api.model;

import jakarta.validation.constraints.Size;

/**
 * Replaces what can change of a catalog role. Its name cannot change: the APIs of the realm owner read it.
 *
 * @param displayName  optional, absent = none
 * @param description  optional, absent = none
 * @param orgGrantable see {@link CatalogRole}
 */
public record UpdateCatalogRoleRequest(
    @Size(max = 255)
    String displayName,
    @Size(max = 255)
    String description,
    boolean orgGrantable){ }
