package org.orgkeep.api.model;

import jakarta.validation.constraints.NotNull;

/**
 * @param enabled false stops the new tokens at once; the tokens already issued stay valid until they expire
 *                (an API that introspects sees them inactive). True enables it again
 */
public record UpdateServiceAccountStatusRequest(
    @NotNull
    Boolean enabled){ }
