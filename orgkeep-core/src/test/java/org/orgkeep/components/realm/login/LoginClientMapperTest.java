package org.orgkeep.components.realm.login;

import org.orgkeep.api.model.LightweightTokens;
import org.orgkeep.api.model.LoginClient;
import org.orgkeep.api.model.UpdateLoginClientRequest;
import org.junit.jupiter.api.Test;
import org.keycloak.representations.idm.ClientRepresentation;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        client.setBaseUrl("https://app.example.com/home");

        assertEquals(new LoginClient("demo", ISSUER, CONSOLE, List.of("https://app.example.com/*"),
                        List.of("https://app.example.com/", "https://app.example.com/bye"), List.of("+"), List.of("products"),
                        false, "https://app.example.com/home"),
                mapper.toLoginClient(client, ISSUER, CONSOLE, List.of("products")));
    }

    // null: the baseUrl does not change
    @Test
    void updateWithoutHomeUrlKeepsTheBaseUrl() {
        ClientRepresentation client = new ClientRepresentation();
        client.setBaseUrl("https://app.example.com/home");

        mapper.update(new UpdateLoginClientRequest(List.of(), List.of(), List.of()), client);

        assertEquals("https://app.example.com/home", client.getBaseUrl());
    }

    // "": removes the baseUrl. Set to "", not null: the Keycloak admin API drops a null field from a client
    // update instead of clearing it, so toLoginClient is the one that reads an empty baseUrl back as "not set"
    @Test
    void updateWithEmptyHomeUrlRemovesTheBaseUrl() {
        ClientRepresentation client = new ClientRepresentation();
        client.setBaseUrl("https://app.example.com/home");

        mapper.update(new UpdateLoginClientRequest(List.of(), List.of(), List.of(), null, null, ""), client);

        assertEquals("", client.getBaseUrl());
        assertNull(mapper.toLoginClient(client, ISSUER, null, List.of()).homeUrl());
    }

    // a value: replaces the baseUrl
    @Test
    void updateWithHomeUrlReplacesTheBaseUrl() {
        ClientRepresentation client = new ClientRepresentation();
        client.setBaseUrl("https://old.example.com/");

        mapper.update(new UpdateLoginClientRequest(List.of(), List.of(), List.of(), null, null, "https://app.example.com/home"), client);

        assertEquals("https://app.example.com/home", client.getBaseUrl());
    }

    // a client never configured has no attribute at all
    @Test
    void toLoginClientWithoutAttributes() {
        ClientRepresentation client = new ClientRepresentation();
        client.setClientId("demo");

        LoginClient loginClient = mapper.toLoginClient(client, ISSUER, null, List.of());
        assertEquals(List.of(), loginClient.postLogoutRedirectUris());
        assertFalse(loginClient.lightweightTokens());
        assertNull(loginClient.homeUrl());
    }

    // the mode is written by LightweightClients (ordered with the marker scope), never by the mapper
    @Test
    void updateDoesNotTouchTheLightweightMode() {
        ClientRepresentation client = new ClientRepresentation();
        client.setAttributes(Map.of(LightweightTokens.CLIENT_ATTRIBUTE, "true"));

        mapper.update(new UpdateLoginClientRequest(List.of(), List.of(), List.of(), null, false), client);

        assertEquals("true", client.getAttributes().get(LightweightTokens.CLIENT_ATTRIBUTE));
        assertTrue(mapper.toLoginClient(client, ISSUER, null, List.of()).lightweightTokens());
    }
}
