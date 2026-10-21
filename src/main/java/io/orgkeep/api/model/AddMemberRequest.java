package io.orgkeep.api.model;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * An existing user with this email is reused (first and last name unchanged), otherwise a user is created without
 * credentials: the invitation email lets them choose a password.
 *
 * @param firstName required: Keycloak asks for it at login otherwise, and a password grant fails
 *                  ("Account is not fully set up")
 * @param lastName  required, same as firstName
 */
public record AddMemberRequest(
    @NotBlank
    @Email
    String email,
    @NotBlank
    @Size(max = 255)
    String firstName,
    @NotBlank
    @Size(max = 255)
    String lastName){ }
