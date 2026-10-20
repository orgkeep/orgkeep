package io.orgkeep.api.model;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * An existing user with this email is reused, otherwise a user is created without credentials:
 * the invitation email lets them choose a password.
 */
public record AddMemberRequest(
    @NotBlank
    @Email
    String email,
    String firstName,
    String lastName){ }
