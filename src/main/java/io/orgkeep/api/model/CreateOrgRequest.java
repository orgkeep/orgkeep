package io.orgkeep.api.model;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Set;

/**
 * @param name           used to derive the client id (org-{name}) and the group path (/{name}),
 *                       hence restricted to a lowercase slug
 * @param ownerEmail     optional; an existing user with this email is reused, otherwise a user is created
 * @param ownerPassword  optional temporary password, only used when the owner user is created
 * @param ownerFirstName optional, only used when the owner user is created. Keycloak requires a first and last name
 *                       at login: without them, the user must complete the profile in the browser and a password
 *                       grant fails ("Account is not fully set up")
 * @param ownerLastName  optional, same as ownerFirstName
 */
public record CreateOrgRequest (
    @NotBlank
    @Size(max = 50)
    @Pattern(regexp = "[a-z0-9]([a-z0-9-]*[a-z0-9])?", message = "lowercase letters, digits and inner hyphens only")
    String name,
    Set<@NotBlank String> emailDomains,
    @Email
    String ownerEmail,
    String ownerPassword,
    String ownerFirstName,
    String ownerLastName){

    /** Owner without first and last name. */
    public CreateOrgRequest(String name, Set<String> emailDomains, String ownerEmail, String ownerPassword) {
        this(name, emailDomains, ownerEmail, ownerPassword, null, null);
    }
}
