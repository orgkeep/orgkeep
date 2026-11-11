package org.orgkeep.api.model;

/** A catalog role given by a team (an org grantable one). */
public record TeamCatalogRole(
    String catalog,
    String role){ }
