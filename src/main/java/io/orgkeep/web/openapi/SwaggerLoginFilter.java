package io.orgkeep.web.openapi;

import io.quarkus.smallrye.openapi.OpenApiFilter;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.openapi.OASFactory;
import org.eclipse.microprofile.openapi.OASFilter;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.eclipse.microprofile.openapi.models.security.SecurityScheme;

/**
 * When orgkeep.swagger-ui.login is true (dev mode), replaces the "paste a bearer token" security scheme
 * of the OpenAPI document by an OAuth2 password flow: the "Authorize" button of Swagger UI then asks for
 * username / password and gets the token from Keycloak itself.
 * <p>
 * Runs at startup rather than at build time because the token URL comes from runtime config
 * (quarkus.oidc.auth-server-url, set by Dev Services). Not a CDI bean: config is read through ConfigProvider.
 */
@OpenApiFilter(stages = OpenApiFilter.RunStage.RUNTIME_STARTUP)
public class SwaggerLoginFilter implements OASFilter {

    @Override
    public void filterOpenAPI(OpenAPI openAPI) {
        Config config = ConfigProvider.getConfig();
        if (!config.getOptionalValue("orgkeep.swagger-ui.login", Boolean.class).orElse(false)
                || openAPI.getComponents() == null) {
            return;
        }
        // same name as the scheme declared by quarkus.smallrye-openapi.security-scheme: the security requirements
        // Quarkus adds to the secured endpoints point to it
        String schemeName = config.getOptionalValue("quarkus.smallrye-openapi.security-scheme-name", String.class)
                .orElse("SecurityScheme");
        String authServerUrl = config.getOptionalValue("orgkeep.swagger-ui.auth-server-url", String.class)
                .or(() -> config.getOptionalValue("orgkeep.keycloak-public-url", String.class)
                        .map(publicUrl -> publicUrl + "/realms/" + config.getValue("orgkeep.realm-name", String.class)))
                .orElseGet(() -> config.getValue("quarkus.oidc.auth-server-url", String.class));
        String tokenUrl = authServerUrl + "/protocol/openid-connect/token";

        SecurityScheme passwordLogin = OASFactory.createSecurityScheme()
                .type(SecurityScheme.Type.OAUTH2)
                .description("Keycloak login with username / password (client " + SwaggerClient.CLIENT_ID + ")")
                .flows(OASFactory.createOAuthFlows()
                        .password(OASFactory.createOAuthFlow().tokenUrl(tokenUrl)));
        openAPI.getComponents().addSecurityScheme(schemeName, passwordLogin);
    }
}
