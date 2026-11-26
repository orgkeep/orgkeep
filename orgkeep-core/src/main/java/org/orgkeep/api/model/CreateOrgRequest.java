package org.orgkeep.api.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.Set;

/**
 * @param name                used to derive the client id (org-{name}) and the group path (/{name}),
 *                            hence restricted to a lowercase slug
 * @param ownerEmail          optional; an existing user with this email is reused, otherwise a user is created
 * @param ownerPassword       optional temporary password, only used when the owner user is created
 * @param ownerFirstName      required with an ownerEmail, only used when the owner user is created (an existing user
 *                            keeps their names). Keycloak requires a first and last name at login: without them, the
 *                            user must complete the profile in the browser and a password grant fails ("Account is
 *                            not fully set up")
 * @param ownerLastName       same as ownerFirstName
 * @param maxMembers          optional maximum number of members, null = no limit (as today). 0 freezes the
 *                            organisation to its owner, if any
 * @param maxServiceAccounts  optional maximum number of service accounts, null = no limit
 */
public record CreateOrgRequest (
    @NotBlank
    @Size(max = 50)
    @Slug
    String name,
    Set<@NotBlank String> emailDomains,
    @Email
    String ownerEmail,
    String ownerPassword,
    @Size(max = 255)
    String ownerFirstName,
    @Size(max = 255)
    String ownerLastName,
    @PositiveOrZero
    Integer maxMembers,
    @PositiveOrZero
    Integer maxServiceAccounts){

    /** Without owner names: only valid without an owner (ownerEmail null), or for an owner who already exists. */
    public CreateOrgRequest(String name, Set<String> emailDomains, String ownerEmail, String ownerPassword) {
        this(name, emailDomains, ownerEmail, ownerPassword, null, null, null, null);
    }

    /** Without seat limits, as every organisation before #39. */
    public CreateOrgRequest(String name, Set<String> emailDomains, String ownerEmail, String ownerPassword,
                            String ownerFirstName, String ownerLastName) {
        this(name, emailDomains, ownerEmail, ownerPassword, ownerFirstName, ownerLastName, null, null);
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

    /**
     * The owner takes a member seat: an organisation created with an owner and a members limit of 0 would freeze
     * before anyone could ever join it. Hidden from JSON and from the OpenAPI schema, like isOwnerNamed.
     */
    @JsonIgnore
    @Schema(hidden = true)
    @AssertTrue(message = "maxMembers must be at least 1 with an ownerEmail: the owner takes a seat")
    public boolean isOwnerSeated() {
        return ownerEmail == null || maxMembers == null || maxMembers >= 1;
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }
}
