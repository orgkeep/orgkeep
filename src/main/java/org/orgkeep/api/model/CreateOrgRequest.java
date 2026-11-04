package org.orgkeep.api.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.Set;

/**
 * @param name           used to derive the client id (org-{name}) and the group path (/{name}),
 *                       hence restricted to a lowercase slug
 * @param ownerEmail     optional; an existing user with this email is reused, otherwise a user is created
 * @param ownerPassword  optional temporary password, only used when the owner user is created
 * @param ownerFirstName required with an ownerEmail, only used when the owner user is created (an existing user keeps
 *                       their names). Keycloak requires a first and last name at login: without them, the user must
 *                       complete the profile in the browser and a password grant fails ("Account is not fully set up")
 * @param ownerLastName  same as ownerFirstName
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
    @Size(max = 255)
    String ownerFirstName,
    @Size(max = 255)
    String ownerLastName){

    /** Without owner names: only valid without an owner (ownerEmail null), or for an owner who already exists. */
    public CreateOrgRequest(String name, Set<String> emailDomains, String ownerEmail, String ownerPassword) {
        this(name, emailDomains, ownerEmail, ownerPassword, null, null);
    }

    /**
     * Bean Validation reads this "is" method like a property (reported as ownerNamed): the names are required as soon as
     * an owner is given. Hidden from JSON and from the OpenAPI schema.
     */
    @JsonIgnore
    @Schema(hidden = true)
    @AssertTrue(message = "ownerFirstName and ownerLastName are required with an ownerEmail")
    public boolean isOwnerNamed() {
        return ownerEmail == null || (isPresent(ownerFirstName) && isPresent(ownerLastName));
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }
}
