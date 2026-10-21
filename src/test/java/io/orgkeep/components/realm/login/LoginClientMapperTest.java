package io.orgkeep.components.realm.login;

import io.orgkeep.api.model.LoginClient;
import io.orgkeep.api.model.UpdateLoginClientRequest;
import org.junit.jupiter.api.Test;
import org.keycloak.representations.idm.ClientRepresentation;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class LoginClientMapperTest {
    private static final String ISSUER = "https://login.example.com/realms/demo";
    private static final String CONSOLE = "https://login.example.com/admin/demo/console/";

    LoginClientMapper mapper = new LoginClientMapperImpl();

    @Test
    void updateKeepsTheOtherSettings() {
        ClientRepresentation client = new ClientRepresentation();
        client.setClientId("demo");
        client.setPublicClient(true);
        client.setAttributes(Map.of("pkce.code.challenge.method", "S256"));

        mapper.update(new UpdateLoginClientRequest(List.of("https://app.example.com/*"),
                List.of("https://app.example.com/", "https://app.example.com/bye"), List.of("+")), client);

        assertEquals("demo", client.getClientId());
        assertTrue(client.isPublicClient());
        assertEquals(List.of("https://app.example.com/*"), client.getRedirectUris());
        assertEquals(List.of("+"), client.getWebOrigins());
        assertEquals(Map.of("pkce.code.challenge.method", "S256",
                        "post.logout.redirect.uris", "https://app.example.com/##https://app.example.com/bye"),
                client.getAttributes());
    }

    @Test
    void toLoginClientSplitsPostLogoutRedirectUris() {
        ClientRepresentation client = new ClientRepresentation();
        client.setClientId("demo");
        client.setRedirectUris(List.of("https://app.example.com/*"));
        client.setWebOrigins(List.of("+"));
        client.setAttributes(Map.of("post.logout.redirect.uris", "https://app.example.com/##https://app.example.com/bye"));

        assertEquals(new LoginClient("demo", ISSUER, CONSOLE, List.of("https://app.example.com/*"),
                        List.of("https://app.example.com/", "https://app.example.com/bye"), List.of("+"), List.of("products")),
                mapper.toLoginClient(client, ISSUER, CONSOLE, List.of("products")));
    }

    // a client never configured has no attribute at all
    @Test
    void toLoginClientWithoutAttributes() {
        ClientRepresentation client = new ClientRepresentation();
        client.setClientId("demo");

        assertEquals(List.of(), mapper.toLoginClient(client, ISSUER, null, List.of()).postLogoutRedirectUris());
    }
}
