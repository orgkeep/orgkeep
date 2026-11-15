package org.orgkeep.api.model;

import jakarta.validation.constraints.NotNull;

/** @param lightweightTokens the mode wanted: required, the calls are idempotent */
public record UpdateServiceAccountSettingsRequest(
    @NotNull
    Boolean lightweightTokens){ }
