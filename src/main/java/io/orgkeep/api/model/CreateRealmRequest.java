package io.orgkeep.api.model;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * @param adminPassword     optional, a password is generated when missing
 * @param adminUsername     optional, defaults to adminEmail
 * @param temporaryPassword optional, defaults to true: the admin must change the password at first login
 * @param adminFirstName    optional. Keycloak requires a first and last name at login: without them, the admin must
 *                          complete the profile in the browser and a password grant fails ("Account is not fully set up")
 * @param adminLastName     optional, same as adminFirstName
 */
public record CreateRealmRequest(
    @NotBlank
    @Size(max = 50)
    @Pattern(regexp = "[a-z0-9]([a-z0-9-]*[a-z0-9])?", message = "lowercase letters, digits and inner hyphens only")
    String realmName,
    @NotBlank
    @Email
    String adminEmail,
    String adminPassword,
    String adminUsername,
    Boolean temporaryPassword,
    String adminFirstName,
    String adminLastName){

    /** Admin logging in with their email, temporary password, no name. */
    public CreateRealmRequest(String realmName, String adminEmail, String adminPassword) {
        this(realmName, adminEmail, adminPassword, null, null, null, null);
    }
}
