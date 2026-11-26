package org.orgkeep.api.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** @param reason free text, required, 500 characters at most */
public record SuspendRequest(@NotBlank @Size(max = Suspensions.MAX_REASON_LENGTH) String reason) { }
