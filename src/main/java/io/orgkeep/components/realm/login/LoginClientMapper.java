package io.orgkeep.components.realm.login;

import io.orgkeep.api.model.LoginClient;
import io.orgkeep.api.model.UpdateLoginClientRequest;
import org.keycloak.representations.idm.ClientRepresentation;
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

    // two sources: the client fills every property but defaultCatalogs, read from its client scopes
    @Mapping(target = "clientId", source = "client.clientId")
    @Mapping(target = "redirectUris", source = "client.redirectUris")
    @Mapping(target = "webOrigins", source = "client.webOrigins")
    @Mapping(target = "postLogoutRedirectUris", source = "client.attributes", qualifiedByName = "postLogoutRedirectUris")
    LoginClient toLoginClient(ClientRepresentation client, List<String> defaultCatalogs);

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
        client.setAttributes(attributes);
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
