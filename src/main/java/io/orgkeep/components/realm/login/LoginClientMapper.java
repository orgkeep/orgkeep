package io.orgkeep.components.realm.login;

import io.orgkeep.api.model.LightweightTokens;
import io.orgkeep.api.model.LoginClient;
import io.orgkeep.api.model.UpdateLoginClientRequest;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.ClientScopeRepresentation;
import org.keycloak.representations.idm.ProtocolMapperRepresentation;
import org.mapstruct.AfterMapping;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.Named;
import org.mapstruct.ReportingPolicy;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Mapper(componentModel = "cdi", unmappedTargetPolicy = ReportingPolicy.WARN)
public interface LoginClientMapper {

    /** Client attribute holding the post logout redirect URIs, separated by "##" (Keycloak has no dedicated field). */
    String POST_LOGOUT_REDIRECT_URIS = "post.logout.redirect.uris";
    String SEPARATOR = "##";

    // several sources: the client fills the properties read from Keycloak, the parameters the other ones
    // (MapStruct matches a parameter to the property of the same name)
    @Mapping(target = "clientId", source = "client.clientId")
    @Mapping(target = "redirectUris", source = "client.redirectUris")
    @Mapping(target = "webOrigins", source = "client.webOrigins")
    @Mapping(target = "postLogoutRedirectUris", source = "client.attributes", qualifiedByName = "postLogoutRedirectUris")
    @Mapping(target = "lightweightTokens", source = "client.attributes", qualifiedByName = "lightweightTokens")
    LoginClient toLoginClient(ClientRepresentation client, String issuer, String consoleUrl, List<String> defaultCatalogs);

    /**
     * Applies the request onto the client read from Keycloak: every other setting of the client is kept,
     * the representation is then sent back whole.
     */
    // only the listed properties: the target is the full client, the other ones must stay untouched
    @BeanMapping(ignoreByDefault = true)
    @Mapping(target = "redirectUris", source = "redirectUris")
    @Mapping(target = "webOrigins", source = "webOrigins")
    void update(UpdateLoginClientRequest request, @MappingTarget ClientRepresentation client);

    @AfterMapping
    default void updatePostLogoutRedirectUris(UpdateLoginClientRequest request, @MappingTarget ClientRepresentation client) {
        Map<String, String> attributes = client.getAttributes() == null ? new HashMap<>() : new HashMap<>(client.getAttributes());
        attributes.put(POST_LOGOUT_REDIRECT_URIS, String.join(SEPARATOR, request.postLogoutRedirectUris()));
        // absent: the mode does not change
        if (request.lightweightTokens() != null) {
            attributes.put(LightweightTokens.CLIENT_ATTRIBUTE, request.lightweightTokens().toString());
        }
        client.setAttributes(attributes);
    }

    @Named("lightweightTokens")
    default boolean lightweightTokens(Map<String, String> attributes) {
        return attributes != null && Boolean.parseBoolean(attributes.get(LightweightTokens.CLIENT_ATTRIBUTE));
    }

    /**
     * The client scope of the marker of the lightweight tokens: a native "Hardcoded claim" mapper, in the lightweight
     * access token only. Neither in the full token (the scope is only on clients in lightweight mode), nor in the
     * introspection, which returns the full content; nor listed in the "scope" claim (white label).
     */
    default ClientScopeRepresentation toMarkerScope() {
        ProtocolMapperRepresentation mapper = new ProtocolMapperRepresentation();
        mapper.setName(LightweightTokens.MARKER_CLAIM);
        mapper.setProtocol("openid-connect");
        mapper.setProtocolMapper("oidc-hardcoded-claim-mapper");
        mapper.setConfig(Map.of(
                "claim.name", LightweightTokens.MARKER_CLAIM,
                "claim.value", "true",
                "jsonType.label", "boolean",
                "access.token.claim", "true",
                "lightweight.claim", "true",
                "introspection.token.claim", "false",
                "id.token.claim", "false",
                "userinfo.token.claim", "false"));

        ClientScopeRepresentation scope = new ClientScopeRepresentation();
        scope.setName(LightweightTokens.MARKER_CLAIM);
        scope.setDescription("Marker of the lightweight access tokens");
        scope.setProtocol("openid-connect");
        scope.setAttributes(Map.of(
                "include.in.token.scope", "false",
                "display.on.consent.screen", "false"));
        scope.setProtocolMappers(List.of(mapper));
        return scope;
    }

    // @Named keeps MapStruct from using this method for every other Map -> List conversion
    @Named("postLogoutRedirectUris")
    default List<String> postLogoutRedirectUris(Map<String, String> attributes) {
        String value = attributes == null ? null : attributes.get(POST_LOGOUT_REDIRECT_URIS);
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.asList(value.split(SEPARATOR));
    }
}
