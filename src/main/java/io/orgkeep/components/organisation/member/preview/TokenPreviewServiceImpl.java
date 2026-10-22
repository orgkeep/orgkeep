package io.orgkeep.components.organisation.member.preview;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.orgkeep.api.TokenPreviewService;
import io.orgkeep.api.exceptions.InvalidRequestException;
import io.orgkeep.api.exceptions.LoginClientNotFoundException;
import io.orgkeep.api.exceptions.MemberNotFoundException;
import io.orgkeep.components.catalog.CatalogSetup;
import io.orgkeep.repository.keycloak.KeycloakClients;
import io.orgkeep.repository.keycloak.KeycloakOrgs;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.RequiredArgsConstructor;
import org.keycloak.representations.AccessToken;

import java.util.List;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
@RequiredArgsConstructor
public class TokenPreviewServiceImpl implements TokenPreviewService {
    private final KeycloakOrgs orgs;
    private final KeycloakClients clients;
    private final CatalogSetup catalogSetup;
    // the ObjectMapper of Quarkus (quarkus-jackson), the one the REST layer uses
    private final ObjectMapper objectMapper;

    @Override
    public Map<String, Object> preview(String realm, String orgName, String userId, List<String> catalogs) {
        String orgId = orgs.getByName(realm, orgName).getId();
        if (!orgs.isMember(realm, orgId, userId)) {
            throw new MemberNotFoundException(orgName, userId);
        }
        // the names go into the scope parameter: only real catalogs, never another client scope of the realm
        Set<String> realmCatalogs = catalogSetup.catalogScopeIds(realm).keySet();
        List<String> unknown = catalogs.stream().filter(catalog -> !realmCatalogs.contains(catalog)).distinct().toList();
        if (!unknown.isEmpty()) {
            throw new InvalidRequestException("Not catalogs of the realm: %s".formatted(unknown));
        }

        // the login client (clientId = realm name): the tokens the applications of the realm owner get
        String loginClientUuid = clients.findUuid(realm, realm)
                .orElseThrow(() -> new LoginClientNotFoundException(realm));
        StringBuilder scope = new StringBuilder("openid");
        catalogs.stream().distinct().forEach(catalog -> scope.append(' ').append(catalog));
        AccessToken token = clients.exampleAccessToken(realm, loginClientUuid, userId, scope.toString());

        // a plain JSON object for the API: the Keycloak representation stays in the core (the api package does not
        // depend on Keycloak). Its unknown claims (the claims of the catalogs) are kept by Jackson (otherClaims)
        return objectMapper.convertValue(token, new TypeReference<>() { });
    }
}
