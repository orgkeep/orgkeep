package org.orgkeep.components.catalog;

import org.orgkeep.api.model.Catalog;
import org.orgkeep.api.model.Catalogs;
import org.orgkeep.api.model.CreateCatalogRequest;
import org.orgkeep.api.model.LightweightTokens;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.ClientScopeRepresentation;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;
import org.mapstruct.ReportingPolicy;

import java.util.HashMap;
import java.util.Map;

@Mapper(componentModel = "cdi", unmappedTargetPolicy = ReportingPolicy.WARN)
public interface CatalogMapper {

    /**
     * The catalog client is a container for the product roles: no login flow is enabled on it. Confidential: its
     * secret is what the APIs of the catalog introspect the tokens with (see CatalogService.regenerateSecret). Its
     * introspection may also give the full token, signed, to a gateway asking for it (see
     * {@link LightweightTokens#INTROSPECTION_JWT_ATTRIBUTE}).
     */
    // ClientRepresentation has dozens of properties: only map the listed ones instead of warning on each
    @BeanMapping(ignoreByDefault = true)
    @Mapping(target = "clientId", source = "name")
    @Mapping(target = "name", source = "displayName")
    @Mapping(target = "description", source = "description")
    @Mapping(target = "enabled", constant = "true")
    @Mapping(target = "publicClient", constant = "false")
    @Mapping(target = "standardFlowEnabled", constant = "false")
    @Mapping(target = "directAccessGrantsEnabled", constant = "false")
    @Mapping(target = "serviceAccountsEnabled", constant = "false")
    @Mapping(target = "attributes", expression = "java(clientAttributes())")
    ClientRepresentation toClient(CreateCatalogRequest request);

    default Map<String, String> clientAttributes() {
        return Map.of(LightweightTokens.INTROSPECTION_JWT_ATTRIBUTE, "true");
    }

    // several sources: the client fills the names, productCount the property of the same name
    @Mapping(target = "name", source = "client.clientId")
    @Mapping(target = "displayName", source = "client.name")
    @Mapping(target = "description", source = "client.description", qualifiedByName = "blankToNull")
    Catalog toCatalog(ClientRepresentation client, int productCount);

    /** A removed description is stored as an empty string (see CatalogService.update). */
    // @Named keeps MapStruct from using this method for every other String -> String conversion
    @Named("blankToNull")
    default String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * Not shown on a consent screen, but listed in the "scope" claim. Marked as the scope of a catalog
     * (see {@link Catalogs}).
     */
    default ClientScopeRepresentation toScope(CreateCatalogRequest request) {
        ClientScopeRepresentation scope = new ClientScopeRepresentation();
        scope.setName(request.name());
        scope.setDescription("Products of the catalog " + request.name());
        scope.setProtocol("openid-connect");
        scope.setAttributes(Map.of(
                // must stay true: the introspection recomputes the claims from the scopes listed in the "scope" claim
                // of the token. Unlisted, the catalog is lost, even for a full token (see LightweightTokenTest)
                "include.in.token.scope", "true",
                "display.on.consent.screen", "false"));
        markAsCatalog(scope);
        return scope;
    }

    default boolean isCatalog(ClientScopeRepresentation scope) {
        return scope.getAttributes() != null
                && Catalogs.MARKER_VALUE.equals(scope.getAttributes().get(Catalogs.MARKER_ATTRIBUTE));
    }

    default void markAsCatalog(ClientScopeRepresentation scope) {
        Map<String, String> attributes = scope.getAttributes() == null
                ? new HashMap<>()
                : new HashMap<>(scope.getAttributes());
        attributes.put(Catalogs.MARKER_ATTRIBUTE, Catalogs.MARKER_VALUE);
        scope.setAttributes(attributes);
    }
}
