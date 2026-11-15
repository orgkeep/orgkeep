package org.orgkeep.api.model;

/**
 * Settings of the service accounts, managed by the realm owner only: of one service account, or the default of the
 * realm for the service accounts created afterwards.
 *
 * @param lightweightTokens true: the access tokens are lightweight (see {@link LightweightTokens}), every API
 *                          receiving them must introspect them first
 */
public record ServiceAccountSettings(boolean lightweightTokens){ }
