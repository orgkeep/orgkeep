package io.orgkeep.dashboard.product;

/**
 * Copy of CreateCatalogRequest of orgkeep-core, without Bean Validation: the core checks the name (slug, reserved
 * claims, already taken) and its 400 or 409 is relayed to the front.
 *
 * @param name clientId of the catalog client, also the name of its claim in the tokens
 */
public record CreateCatalogRequest(
        String name,
        String displayName,
        String description) { }
