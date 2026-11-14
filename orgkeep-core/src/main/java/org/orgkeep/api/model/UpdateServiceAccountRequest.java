package org.orgkeep.api.model;

import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Replaces both values.
 *
 * @param description     null or empty clears it
 * @param defaultCatalogs the catalogs in every token. A catalog leaving the list becomes optional again. Null: none
 */
public record UpdateServiceAccountRequest(
    @Size(max = 255)
    String description,
    List<String> defaultCatalogs){ }
