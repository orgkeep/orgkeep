package org.orgkeep.api.model;

/** A new service account, with its credentials: the only time the secret is shown, besides a rotation. */
public record ServiceAccountCreated(
    ServiceAccount serviceAccount,
    ServiceAccountCredentials credentials){ }
