package org.orgkeep.api.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * @param name            name of the service account in its organisation, a lowercase slug (kebab-case) like the team
 *                        names: it is part of the clientId
 * @param description     optional
 * @param defaultCatalogs catalogs in every token of the service account, among those the organisation has a product
 *                        in. Absent: none, the application asks for a catalog with the scope parameter
 */
public record CreateServiceAccountRequest(
    @NotBlank
    @Size(max = 50)
    @Slug
    String name,
    @Size(max = 255)
    String description,
    List<String> defaultCatalogs){ }
