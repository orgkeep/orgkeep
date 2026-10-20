package io.orgkeep.api.model;

import jakarta.validation.constraints.NotBlank;

/**
 * Replaces what the realm owner may change of a catalog. Its name cannot change: it is the clientId, the client scope
 * and the claim name the applications read.
 *
 * @param displayName name shown to the realm owner and the org admins
 * @param description optional, absent = no description
 */
public record UpdateCatalogRequest(
    @NotBlank
    String displayName,
    String description){ }
