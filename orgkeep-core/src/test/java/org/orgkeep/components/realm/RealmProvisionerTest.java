package org.orgkeep.components.realm;

import org.orgkeep.api.CatalogService;
import org.orgkeep.api.RealmProvisioner;
import org.orgkeep.api.events.UserCreated;
import org.orgkeep.api.model.Catalog;
import org.orgkeep.api.model.Catalogs;
import org.orgkeep.api.exceptions.RealmAlreadyExistsException;
import org.orgkeep.api.model.CreateRealmRequest;
import org.orgkeep.api.model.CreateRealmResponse;
import org.orgkeep.api.model.DashboardClientSettings;
import org.orgkeep.api.OrgService;
import org.orgkeep.api.model.CreateOrgRequest;
import org.orgkeep.api.model.CreateCatalogRequest;
import org.orgkeep.api.model.OrgkeepApiClient;
import org.orgkeep.api.model.RealmRole;
import org.orgkeep.api.model.SmtpSettings;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.eventbus.MessageConsumer;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.CreatedResponseUtil;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.ClientScopeRepresentation;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class RealmProvisionerTest {

    @Inject
    RealmProvisioner service;

    @Inject
    CatalogService catalogService;

    @Inject
    OrgService orgService;

    @Inject
    Keycloak keycloak;

    @Inject
    EventBus eventBus;

    @ConfigProperty(name = "quarkus.keycloak.admin-client.server-url")
    String keycloakUrl;

    private String realmName;

    @BeforeEach
    void setUp() {
        realmName = "it-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @AfterEach
    void tearDown() {
        try {
            keycloak.realm(realmName).remove();
        } catch (WebApplicationException ignored) {
            //ignore
        }
    }

    @Test
    void createsRealmWithOwnerAndRoles() {
        CreateRealmResponse response = service.create(new CreateRealmRequest(realmName, "owner@acme.test", "s3cret"));

        assertTrue(keycloak.realm(realmName).toRepresentation().isEnabled());
        assertEquals(keycloak.realm(realmName).toRepresentation().getId(), response.id());
        assertEquals(realmName, response.realmName());

        List<UserRepresentation> found =
                keycloak.realm(realmName).users().searchByEmail("owner@acme.test", true);
        assertEquals(1, found.size());
        UserRepresentation user = found.get(0);
        assertEquals(user.getId(), response.ownerId());
        assertTrue(user.isEnabled());
        assertTrue(user.isEmailVerified());

        List<CredentialRepresentation> credentials =
                keycloak.realm(realmName).users().get(user.getId()).credentials();
        assertTrue(credentials.stream()
                .anyMatch(c -> CredentialRepresentation.PASSWORD.equals(c.getType())));

        Set<String> assigned = keycloak.realm(realmName).users().get(user.getId())
                .roles().realmLevel().listAll().stream()
                .map(RoleRepresentation::getName)
                .collect(Collectors.toSet());
        Set<String> expected = Arrays.stream(RealmRole.values())
                .map(RealmRole::getRoleName)
                .collect(Collectors.toSet());
        assertTrue(assigned.containsAll(expected));
    }

    // the owner flag drives the choice of client for the invitation (InvitationMailer): true for the realm owner,
    // with a null orgName
    @Test
    void createPublishesUserCreatedWithOwnerTrue() throws InterruptedException {
        BlockingQueue<UserCreated> events = new ArrayBlockingQueue<>(1);
        MessageConsumer<UserCreated> consumer = eventBus.consumer(UserCreated.ADDRESS, message -> events.offer(message.body()));
        try {
            service.create(new CreateRealmRequest(realmName, "owner@acme.test", null));

            UserCreated event = events.poll(5, TimeUnit.SECONDS);
            assertNotNull(event, "UserCreated not published");
            assertTrue(event.owner());
            assertNull(event.orgName());
            assertFalse(event.passwordSet());
        } finally {
            consumer.unregister();
        }
    }

    // like any user of the realm: the Account console of Keycloak needs the roles of the account client they hold
    @Test
    void ownerHasTheDefaultRolesOfTheRealm() {
        CreateRealmResponse response = service.create(new CreateRealmRequest(realmName, "owner@acme.test", "s3cret"));

        String defaultRole = keycloak.realm(realmName).toRepresentation().getDefaultRole().getName();
        Set<String> assigned = keycloak.realm(realmName).users().get(response.ownerId())
                .roles().realmLevel().listAll().stream()
                .map(RoleRepresentation::getName)
                .collect(Collectors.toSet());
        assertTrue(assigned.contains(defaultRole), assigned.toString());

        String accountClient = keycloak.realm(realmName).clients().findByClientId("account").get(0).getId();
        Set<String> accountRoles = keycloak.realm(realmName).users().get(response.ownerId())
                .roles().clientLevel(accountClient).listEffective().stream()
                .map(RoleRepresentation::getName)
                .collect(Collectors.toSet());
        assertTrue(accountRoles.contains("manage-account"), accountRoles.toString());
    }

    // ON_PREM: the realm owner manages their realm in the Keycloak console, which checks the realm-management roles
    @Test
    void grantConsoleAccessMakesRealmOwnerIncludeRealmAdmin() {
        service.create(new CreateRealmRequest(realmName, "owner@acme.test", "s3cret"));

        service.grantConsoleAccess(realmName);
        // idempotent: run at each startup
        service.grantConsoleAccess(realmName);

        String realmManagement = keycloak.realm(realmName).clients().findByClientId("realm-management").get(0).getId();
        Set<String> composites = keycloak.realm(realmName).roles().get(RealmRole.REALM_OWNER.getRoleName())
                .getClientRoleComposites(realmManagement).stream()
                .map(RoleRepresentation::getName)
                .collect(Collectors.toSet());
        assertEquals(Set.of("realm-admin"), composites);
    }

    // white label: the theme of Orgkeep goes on one client (the dashboard), the realm keeps the login pages of Keycloak
    @Test
    void setsTheLoginThemeOfOneClientOnly() {
        service.create(new CreateRealmRequest(realmName, "owner@acme.test", "s3cret"));
        String clientUuid = keycloak.realm(realmName).clients().findByClientId(OrgkeepApiClient.CLIENT_ID).get(0).getId();

        assertTrue(service.setLoginTheme(realmName, OrgkeepApiClient.CLIENT_ID, "orgkeep"));
        // idempotent: run at each startup
        assertTrue(service.setLoginTheme(realmName, OrgkeepApiClient.CLIENT_ID, "orgkeep"));

        ClientRepresentation client = keycloak.realm(realmName).clients().get(clientUuid).toRepresentation();
        assertEquals("orgkeep", client.getAttributes().get("login_theme"));
        // the other attributes are kept
        assertEquals("S256", client.getAttributes().get("pkce.code.challenge.method"));
        assertNull(keycloak.realm(realmName).toRepresentation().getLoginTheme());
        ClientRepresentation loginClient = keycloak.realm(realmName).clients().findByClientId(realmName).get(0);
        assertNull(loginClient.getAttributes().get("login_theme"));
    }

    // the dashboard client is created by the operator, maybe after the first startup
    @Test
    void setLoginThemeOfAMissingClientDoesNothing() {
        service.create(new CreateRealmRequest(realmName, "owner@acme.test", "s3cret"));

        assertFalse(service.setLoginTheme(realmName, "orgkeep-dashboard", "orgkeep"));
    }

    // white label: the users of the orgs log in through a client named after the realm, which carries the quota claims
    @Test
    void createsLoginClientWithProductsScope() {
        service.create(new CreateRealmRequest(realmName, "owner@acme.test", "s3cret"));
        // idempotent: run at each startup on an existing realm
        service.ensureDefaults(realmName);

        List<ClientRepresentation> clients = keycloak.realm(realmName).clients().findByClientId(realmName);
        assertEquals(1, clients.size());
        assertTrue(clients.get(0).isPublicClient());
        assertEquals("S256", clients.get(0).getAttributes().get("pkce.code.challenge.method"));

        Set<String> defaultScopes = keycloak.realm(realmName).clients().get(clients.get(0).getId())
                .getDefaultClientScopes().stream()
                .map(ClientScopeRepresentation::getName)
                .collect(Collectors.toSet());
        assertTrue(defaultScopes.contains(Catalogs.DEFAULT));
    }

    // fullScopeAllowed=false hides the roles of the catalogs that are not in the token, but the core API still needs
    // the realm roles and the org roles: they are mapped explicitly
    @Test
    void loginClientOnlyMapsTheRolesOfItsScope() {
        service.create(new CreateRealmRequest(realmName, "owner@acme.test", "s3cret"));
        orgService.create(realmName, new CreateOrgRequest("acme", Set.of(), null, null));

        ClientRepresentation loginClient = keycloak.realm(realmName).clients().findByClientId(realmName).get(0);
        assertFalse(loginClient.isFullScopeAllowed());
        assertTrue(realmRolesInScope(loginClient.getId()).containsAll(Set.of("realm-owner", "default-roles-" + realmName)));
        assertEquals(Set.of("org-owner", "org-admin", "org-user"), orgRolesInScope(loginClient.getId(), "acme"));
    }

    // the client of the API of Orgkeep: public with PKCE, the roles Orgkeep reads (realm and orgs), and no catalog,
    // neither the default one nor one created afterwards
    @Test
    void createsTheOrgkeepApiClientWithoutCatalog() {
        service.create(new CreateRealmRequest(realmName, "owner@acme.test", "s3cret"));
        service.ensureDefaults(realmName);
        orgService.create(realmName, new CreateOrgRequest("acme", Set.of(), null, null));
        catalogService.create(realmName, new CreateCatalogRequest("prod", "Production", null));

        List<ClientRepresentation> clients = keycloak.realm(realmName).clients().findByClientId(OrgkeepApiClient.CLIENT_ID);
        assertEquals(1, clients.size());
        ClientRepresentation apiClient = clients.get(0);
        assertTrue(apiClient.isPublicClient());
        assertEquals("S256", apiClient.getAttributes().get("pkce.code.challenge.method"));
        assertFalse(apiClient.isDirectAccessGrantsEnabled());
        assertFalse(apiClient.isFullScopeAllowed());
        assertTrue(realmRolesInScope(apiClient.getId()).containsAll(Set.of("realm-owner", "default-roles-" + realmName)));
        assertEquals(Set.of("org-owner", "org-admin", "org-user"), orgRolesInScope(apiClient.getId(), "acme"));

        var apiClientResource = keycloak.realm(realmName).clients().get(apiClient.getId());
        Set<String> scopes = new HashSet<>();
        apiClientResource.getDefaultClientScopes().forEach(scope -> scopes.add(scope.getName()));
        apiClientResource.getOptionalClientScopes().forEach(scope -> scopes.add(scope.getName()));
        assertFalse(scopes.contains(Catalogs.DEFAULT), scopes.toString());
        assertFalse(scopes.contains("prod"), scopes.toString());
    }

    // a login client older than this rule: the orgs created before it get their mappings when it switches
    @Test
    void switchingToFullScopeAllowedFalseMapsTheExistingOrgs() {
        service.create(new CreateRealmRequest(realmName, "owner@acme.test", "s3cret"));
        orgService.create(realmName, new CreateOrgRequest("acme", Set.of(), null, null));
        ClientRepresentation loginClient = keycloak.realm(realmName).clients().findByClientId(realmName).get(0);
        String orgClientUuid = keycloak.realm(realmName).clients().findByClientId("org-acme").get(0).getId();
        var orgMappings = keycloak.realm(realmName).clients().get(loginClient.getId()).getScopeMappings().clientLevel(orgClientUuid);
        orgMappings.remove(orgMappings.listAll());
        loginClient.setFullScopeAllowed(true);
        keycloak.realm(realmName).clients().get(loginClient.getId()).update(loginClient);

        service.ensureDefaults(realmName);

        assertFalse(keycloak.realm(realmName).clients().get(loginClient.getId()).toRepresentation().isFullScopeAllowed());
        assertEquals(Set.of("org-owner", "org-admin", "org-user"), orgRolesInScope(loginClient.getId(), "acme"));
    }

    private Set<String> realmRolesInScope(String clientUuid) {
        return keycloak.realm(realmName).clients().get(clientUuid).getScopeMappings().realmLevel().listAll().stream()
                .map(RoleRepresentation::getName)
                .collect(Collectors.toSet());
    }

    private Set<String> orgRolesInScope(String clientUuid, String org) {
        String orgClientUuid = keycloak.realm(realmName).clients().findByClientId("org-" + org).get(0).getId();
        return keycloak.realm(realmName).clients().get(clientUuid).getScopeMappings().clientLevel(orgClientUuid).listAll().stream()
                .map(RoleRepresentation::getName)
                .collect(Collectors.toSet());
    }

    // the default catalog comes with the realm, but is not recreated at startup: the realm owner may delete it
    @Test
    void createsTheDefaultCatalogOnlyWithTheRealm() {
        service.create(new CreateRealmRequest(realmName, "owner@acme.test", "s3cret"));
        assertEquals(List.of(Catalogs.DEFAULT), catalogService.list(realmName).stream().map(Catalog::name).toList());

        catalogService.delete(realmName, Catalogs.DEFAULT);
        service.ensureDefaults(realmName);

        assertTrue(catalogService.list(realmName).isEmpty());
    }

    @Test
    void generatesPasswordWhenNoneProvided() {
        service.create(new CreateRealmRequest(realmName, "owner@acme.test", null));

        UserRepresentation user = keycloak.realm(realmName).users()
                .searchByEmail("owner@acme.test", true).get(0);
        assertFalse(keycloak.realm(realmName).users().get(user.getId()).credentials().isEmpty());
    }

    @Test
    void configureSmtpSetsTheEmailServerOfTheRealm() {
        service.create(new CreateRealmRequest(realmName, "owner@acme.test", "s3cret"));

        service.configureSmtp(realmName, new SmtpSettings("smtp.acme.test", 587, "no-reply@acme.test", "Acme",
                "mailer", "s3cret", true, false));

        Map<String, String> smtp = keycloak.realm(realmName).toRepresentation().getSmtpServer();
        assertEquals("smtp.acme.test", smtp.get("host"));
        assertEquals("587", smtp.get("port"));
        assertEquals("no-reply@acme.test", smtp.get("from"));
        assertEquals("Acme", smtp.get("fromDisplayName"));
        assertEquals("true", smtp.get("auth"));
        assertEquals("mailer", smtp.get("user"));
        assertEquals("true", smtp.get("starttls"));
        // the admin API never sends the password back
        assertNotEquals("s3cret", smtp.get("password"));
        // the partial update left the rest of the realm alone
        assertTrue(keycloak.realm(realmName).toRepresentation().isOrganizationsEnabled());
    }

    // console-only keys (not owned by Orgkeep) must survive a restart: configureSmtp merges instead of overwriting
    @Test
    void configureSmtpKeepsAReplyToSetBeforehand() {
        service.create(new CreateRealmRequest(realmName, "owner@acme.test", "s3cret"));
        service.configureSmtp(realmName, new SmtpSettings("smtp.acme.test", 587, "no-reply@acme.test", "Acme",
                "mailer", "s3cret", true, false));
        // set in the Keycloak console, outside of Orgkeep
        Map<String, String> withReplyTo = new HashMap<>(keycloak.realm(realmName).toRepresentation().getSmtpServer());
        withReplyTo.put("replyTo", "support@acme.test");
        RealmRepresentation update = new RealmRepresentation();
        update.setSmtpServer(withReplyTo);
        keycloak.realm(realmName).update(update);

        service.configureSmtp(realmName, new SmtpSettings("smtp.acme.test", 465, "no-reply@acme.test", "Acme",
                "mailer", "s3cret", true, true));

        Map<String, String> smtp = keycloak.realm(realmName).toRepresentation().getSmtpServer();
        assertEquals("support@acme.test", smtp.get("replyTo"));
        assertEquals("465", smtp.get("port"));
    }

    @Test
    void failsWhenRealmAlreadyExists() {
        CreateRealmRequest request = new CreateRealmRequest(realmName, "owner@acme.test", "s3cret");
        service.create(request);
        assertThrows(RealmAlreadyExistsException.class, () -> service.create(request));
    }

    @Test
    void existsOnlyForAnExistingRealm() {
        assertFalse(service.exists(realmName));
        service.create(new CreateRealmRequest(realmName, "owner@acme.test", "s3cret"));
        assertTrue(service.exists(realmName));
    }

    // ---- Dashboard client ----

    private static final String DASHBOARD = "orgkeep-dashboard";
    private static final DashboardClientSettings SETTINGS =
            new DashboardClientSettings(DASHBOARD, "https://dashboard.acme.test", "orgkeep");

    private ClientRepresentation dashboardClient() {
        return keycloak.realm(realmName).clients().findByClientId(DASHBOARD).get(0);
    }

    private String dashboardSecret() {
        return keycloak.realm(realmName).clients().get(dashboardClient().getId()).getSecret().getValue();
    }

    @Test
    void createsTheDashboardClientConfidentialWithAGeneratedSecret() {
        service.create(new CreateRealmRequest(realmName, "owner@acme.test", "s3cret"));

        service.ensureDashboardClient(realmName, SETTINGS);

        ClientRepresentation client = dashboardClient();
        assertFalse(client.isPublicClient());
        assertFalse(client.isBearerOnly());
        assertEquals("client-secret", client.getClientAuthenticatorType());
        assertTrue(client.isStandardFlowEnabled());
        assertFalse(client.isDirectAccessGrantsEnabled());
        assertFalse(client.isImplicitFlowEnabled());
        assertFalse(client.isServiceAccountsEnabled());
        assertEquals(List.of("https://dashboard.acme.test/*"), client.getRedirectUris());
        assertTrue(client.getWebOrigins() == null || client.getWebOrigins().isEmpty(), String.valueOf(client.getWebOrigins()));
        // home URL: where Keycloak's "Back to Application" link points to after an invitation
        assertEquals("https://dashboard.acme.test/", client.getBaseUrl());
        assertEquals("+", client.getAttributes().get("post.logout.redirect.uris"));
        assertEquals("S256", client.getAttributes().get("pkce.code.challenge.method"));
        assertEquals("orgkeep", client.getAttributes().get("login_theme"));
        assertTrue(dashboardSecret().length() >= 32, "secret generated by Keycloak");
    }

    @Test
    void aSecondCallChangesNothingAndKeepsTheSecret() {
        service.create(new CreateRealmRequest(realmName, "owner@acme.test", "s3cret"));
        service.ensureDashboardClient(realmName, SETTINGS);
        String secret = dashboardSecret();
        String id = dashboardClient().getId();

        service.ensureDashboardClient(realmName, SETTINGS);

        assertEquals(1, keycloak.realm(realmName).clients().findByClientId(DASHBOARD).size());
        assertEquals(id, dashboardClient().getId());
        assertEquals(secret, dashboardSecret());
    }

    @Test
    void repairsWhatOrgkeepOwnsAndKeepsTheRest() {
        service.create(new CreateRealmRequest(realmName, "owner@acme.test", "s3cret"));
        service.ensureDashboardClient(realmName, SETTINGS);
        String secret = dashboardSecret();
        ClientRepresentation client = dashboardClient();
        client.setRedirectUris(List.of("https://evil.test/*"));
        client.setBaseUrl("https://evil.test/");
        client.setDirectAccessGrantsEnabled(true);
        java.util.Map<String, String> attributes = new java.util.HashMap<>(client.getAttributes());
        attributes.remove("pkce.code.challenge.method");
        attributes.put("login_theme", "other");
        attributes.put("unrelated", "kept");
        client.setAttributes(attributes);
        keycloak.realm(realmName).clients().get(client.getId()).update(client);

        service.ensureDashboardClient(realmName, SETTINGS);

        ClientRepresentation repaired = dashboardClient();
        assertEquals(List.of("https://dashboard.acme.test/*"), repaired.getRedirectUris());
        assertEquals("https://dashboard.acme.test/", repaired.getBaseUrl());
        assertFalse(repaired.isDirectAccessGrantsEnabled());
        assertEquals("S256", repaired.getAttributes().get("pkce.code.challenge.method"));
        assertEquals("orgkeep", repaired.getAttributes().get("login_theme"));
        assertEquals("kept", repaired.getAttributes().get("unrelated"));
        assertEquals(secret, dashboardSecret());
    }

    @Test
    void aPublicClientWithTheSameIdBecomesConfidentialWithASecret() {
        service.create(new CreateRealmRequest(realmName, "owner@acme.test", "s3cret"));
        ClientRepresentation byHand = new ClientRepresentation();
        byHand.setClientId(DASHBOARD);
        byHand.setPublicClient(true);
        keycloak.realm(realmName).clients().create(byHand).close();

        service.ensureDashboardClient(realmName, SETTINGS);

        assertFalse(dashboardClient().isPublicClient());
        assertTrue(dashboardSecret().length() >= 32);
    }

    @Test
    void aNullThemeLeavesTheExistingOneAlone() {
        service.create(new CreateRealmRequest(realmName, "owner@acme.test", "s3cret"));
        service.ensureDashboardClient(realmName, SETTINGS);

        service.ensureDashboardClient(realmName, new DashboardClientSettings(DASHBOARD, "https://dashboard.acme.test", null));

        assertEquals("orgkeep", dashboardClient().getAttributes().get("login_theme"));
    }

    // ---- Realm owner from the configuration (enforceSingleOwner) ----

    private static final String OWNER = "owner@acme.test";

    private Set<String> directRoles(String userId) {
        return keycloak.realm(realmName).users().get(userId).roles().realmLevel().listAll().stream()
                .map(RoleRepresentation::getName)
                .collect(Collectors.toSet());
    }

    private static Set<String> ownerRoles() {
        return Arrays.stream(RealmRole.values()).map(RealmRole::getRoleName).collect(Collectors.toSet());
    }

    /** A user with a password, without any role, outside any organisation. */
    private String plainUser(String username) {
        UserRepresentation user = new UserRepresentation();
        user.setUsername(username);
        user.setEmail(username + "@acme.test");
        user.setEmailVerified(true);
        user.setFirstName(username);
        user.setLastName("Test");
        user.setEnabled(true);
        CredentialRepresentation credential = new CredentialRepresentation();
        credential.setType(CredentialRepresentation.PASSWORD);
        credential.setValue("s3cret");
        credential.setTemporary(false);
        user.setCredentials(List.of(credential));
        try (Response response = keycloak.realm(realmName).users().create(user)) {
            return CreatedResponseUtil.getCreatedId(response);
        }
    }

    private void giveOwnerRoles(String userId) {
        List<RoleRepresentation> roles = ownerRoles().stream()
                .map(name -> keycloak.realm(realmName).roles().get(name).toRepresentation())
                .toList();
        keycloak.realm(realmName).users().get(userId).roles().realmLevel().add(roles);
    }

    @Test
    void anotherRealmOwnerLosesTheRolesTheConsoleAccessAndTheSessions() {
        CreateRealmResponse response = service.create(new CreateRealmRequest(realmName, OWNER, "s3cret"));
        service.grantConsoleAccess(realmName);
        String other = plainUser("other");
        giveOwnerRoles(other);
        String refreshToken = logIn("other").path("refresh_token").asText();
        assertTrue(effectiveClientRoles(other, "realm-management").contains("realm-admin"));

        service.enforceSingleOwner(realmName, Optional.empty(), OWNER);

        assertTrue(directRoles(other).stream().noneMatch(ownerRoles()::contains), directRoles(other).toString());
        assertFalse(effectiveClientRoles(other, "realm-management").contains("realm-admin"));
        assertEquals(400, refresh(refreshToken), "the sessions were closed");
        assertTrue(directRoles(response.ownerId()).containsAll(ownerRoles()));
    }

    @Test
    void theConfiguredUserWithoutTheRolesGetsThemByUsernameAndTheOriginalOwnerLosesThem() {
        CreateRealmResponse response = service.create(new CreateRealmRequest(realmName, OWNER, "s3cret"));
        String successor = plainUser("successor");

        service.enforceSingleOwner(realmName, Optional.of("successor"), "successor@acme.test");

        assertTrue(directRoles(successor).containsAll(ownerRoles()));
        assertTrue(directRoles(response.ownerId()).stream().noneMatch(ownerRoles()::contains));
    }

    @Test
    void theConfiguredUserIsFoundByEmailWhenThereIsNoUsername() {
        CreateRealmResponse response = service.create(new CreateRealmRequest(realmName, OWNER, "s3cret"));
        String successor = plainUser("successor");

        service.enforceSingleOwner(realmName, Optional.empty(), "Successor@acme.test");

        assertTrue(directRoles(successor).containsAll(ownerRoles()));
        assertTrue(directRoles(response.ownerId()).stream().noneMatch(ownerRoles()::contains));
    }

    @Test
    void anUnknownUserFailsAndChangesNothing() {
        CreateRealmResponse response = service.create(new CreateRealmRequest(realmName, OWNER, "s3cret"));
        Set<String> before = directRoles(response.ownerId());

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.enforceSingleOwner(realmName, Optional.of("nobody"), "nobody@acme.test"));

        assertTrue(e.getMessage().contains("orgkeep.owner.username / ORGKEEP_OWNER_USERNAME = 'nobody'"), e.getMessage());
        assertTrue(e.getMessage().contains("ORGKEEP_OWNER_EMAIL"), e.getMessage());
        assertEquals(before, directRoles(response.ownerId()));
    }

    @Test
    void aDisabledUserFailsAndStaysDisabled() {
        CreateRealmResponse response = service.create(new CreateRealmRequest(realmName, OWNER, "s3cret"));
        String successor = plainUser("successor");
        UserRepresentation user = keycloak.realm(realmName).users().get(successor).toRepresentation();
        user.setEnabled(false);
        keycloak.realm(realmName).users().get(successor).update(user);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.enforceSingleOwner(realmName, Optional.of("successor"), "successor@acme.test"));

        assertTrue(e.getMessage().contains("is disabled"), e.getMessage());
        assertFalse(keycloak.realm(realmName).users().get(successor).toRepresentation().isEnabled());
        assertTrue(directRoles(successor).stream().noneMatch(ownerRoles()::contains));
        assertTrue(directRoles(response.ownerId()).containsAll(ownerRoles()));
    }

    @Test
    void aUsernameWithAnotherEmailFailsAndChangesNothing() {
        CreateRealmResponse response = service.create(new CreateRealmRequest(realmName, OWNER, "s3cret"));
        String successor = plainUser("successor");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.enforceSingleOwner(realmName, Optional.of("successor"), "someone-else@acme.test"));

        assertTrue(e.getMessage().contains("has the email 'successor@acme.test', not 'someone-else@acme.test'"),
                e.getMessage());
        assertTrue(directRoles(successor).stream().noneMatch(ownerRoles()::contains));
        assertTrue(directRoles(response.ownerId()).containsAll(ownerRoles()));
    }

    @Test
    void aMemberOfAnOrganisationFailsAndNoRoleChanges() {
        CreateRealmResponse response = service.create(new CreateRealmRequest(realmName, OWNER, "s3cret"));
        orgService.create(realmName, new CreateOrgRequest("acme", Set.of(), "member@acme.test", "s3cret", "Mia", "Member"));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.enforceSingleOwner(realmName, Optional.empty(), "member@acme.test"));

        assertTrue(e.getMessage().contains("[acme]"), e.getMessage());
        String member = keycloak.realm(realmName).users().searchByEmail("member@acme.test", true).get(0).getId();
        assertTrue(directRoles(member).stream().noneMatch(ownerRoles()::contains));
        assertTrue(directRoles(response.ownerId()).containsAll(ownerRoles()));
    }

    // ---- Suspension attributes (#38) ----

    @Test
    void createDeclaresTheSuspensionAttributes() {
        service.create(new CreateRealmRequest(realmName, OWNER, "s3cret"));

        var config = keycloak.realm(realmName).users().userProfile().getConfiguration();
        for (String name : List.of("suspension-reason", "suspended-at", "suspended-by", "suspension-org-choice")) {
            var attribute = config.getAttribute(name);
            assertNotNull(attribute, "no declaration for " + name);
            assertEquals(Set.of("admin"), attribute.getPermissions().getView());
            assertEquals(Set.of("admin"), attribute.getPermissions().getEdit());
            assertNull(attribute.getRequired());
        }
        var reason = config.getAttribute("suspension-reason");
        assertNotNull(reason.getValidations());
        assertEquals(500, reason.getValidations().get("length").get("max"));
    }

    @Test
    void ensureDefaultsDoesNotDeclareTheSuspensionAttributesOnAnExistingRealm() {
        RealmRepresentation realm = new RealmRepresentation();
        realm.setRealm(realmName);
        realm.setEnabled(true);
        realm.setOrganizationsEnabled(true);
        keycloak.realms().create(realm);

        service.ensureDefaults(realmName);

        var config = keycloak.realm(realmName).users().userProfile().getConfiguration();
        for (String name : List.of("suspension-reason", "suspended-at", "suspended-by", "suspension-org-choice")) {
            assertNull(config.getAttribute(name), "declared on a realm created before #38");
        }
    }

    @Test
    void aSecondCallChangesNothingAndClosesNoSession() {
        CreateRealmResponse response = service.create(new CreateRealmRequest(realmName, OWNER, "s3cret"));
        String ownerRefresh = logIn(OWNER).path("refresh_token").asText();

        service.enforceSingleOwner(realmName, Optional.empty(), OWNER);
        service.enforceSingleOwner(realmName, Optional.empty(), OWNER);

        assertEquals(ownerRoles(), directRoles(response.ownerId()).stream().filter(ownerRoles()::contains).collect(Collectors.toSet()));
        assertEquals(200, refresh(ownerRefresh), "no session was closed");
    }

    // the limit of the rule: GET /roles/{role}/users only lists the direct holders
    @Test
    void aHolderThroughAGroupIsNotListedAndKeepsTheRole() {
        service.create(new CreateRealmRequest(realmName, OWNER, "s3cret"));
        String viaGroup = plainUser("via-group");
        GroupRepresentation group = new GroupRepresentation();
        group.setName("owners");
        String groupId;
        try (Response response = keycloak.realm(realmName).groups().add(group)) {
            groupId = CreatedResponseUtil.getCreatedId(response);
        }
        keycloak.realm(realmName).groups().group(groupId).roles().realmLevel().add(List.of(
                keycloak.realm(realmName).roles().get(RealmRole.REALM_OWNER.getRoleName()).toRepresentation()));
        keycloak.realm(realmName).users().get(viaGroup).joinGroup(groupId);
        Set<String> listed = keycloak.realm(realmName).roles().get(RealmRole.REALM_OWNER.getRoleName())
                .getUserMembers(0, 100).stream().map(UserRepresentation::getId).collect(Collectors.toSet());
        assertFalse(listed.contains(viaGroup), "only the direct holders are listed");

        service.enforceSingleOwner(realmName, Optional.empty(), OWNER);

        assertTrue(effectiveRealmRoles(viaGroup).contains(RealmRole.REALM_OWNER.getRoleName()));
    }

    // the other limit: console access given directly, without realm-owner, is outside the rule
    @Test
    void directConsoleAccessWithoutRealmOwnerIsKept() {
        service.create(new CreateRealmRequest(realmName, OWNER, "s3cret"));
        String admin = plainUser("console-admin");
        String realmManagement = keycloak.realm(realmName).clients().findByClientId("realm-management").get(0).getId();
        RoleRepresentation realmAdmin = keycloak.realm(realmName).clients().get(realmManagement)
                .roles().get("realm-admin").toRepresentation();
        keycloak.realm(realmName).users().get(admin).roles().clientLevel(realmManagement).add(List.of(realmAdmin));

        service.enforceSingleOwner(realmName, Optional.empty(), OWNER);

        assertTrue(effectiveClientRoles(admin, "realm-management").contains("realm-admin"));
    }

    private Set<String> effectiveRealmRoles(String userId) {
        return keycloak.realm(realmName).users().get(userId).roles().realmLevel().listEffective().stream()
                .map(RoleRepresentation::getName)
                .collect(Collectors.toSet());
    }

    private Set<String> effectiveClientRoles(String userId, String clientId) {
        String uuid = keycloak.realm(realmName).clients().findByClientId(clientId).get(0).getId();
        return keycloak.realm(realmName).users().get(userId).roles().clientLevel(uuid).listEffective().stream()
                .map(RoleRepresentation::getName)
                .collect(Collectors.toSet());
    }

    /** Password grant on the login client of the realm (clientId = realm name), direct access enabled for the test. */
    private JsonNode logIn(String username) {
        ClientRepresentation login = keycloak.realm(realmName).clients().findByClientId(realmName).get(0);
        if (!Boolean.TRUE.equals(login.isDirectAccessGrantsEnabled())) {
            login.setDirectAccessGrantsEnabled(true);
            keycloak.realm(realmName).clients().get(login.getId()).update(login);
        }
        String body = RestAssured.given()
                .formParam("grant_type", "password").formParam("client_id", realmName)
                .formParam("username", username).formParam("password", "s3cret")
                .post(keycloakUrl + "/realms/" + realmName + "/protocol/openid-connect/token")
                .then().statusCode(200).extract().asString();
        try {
            return new ObjectMapper().readTree(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private int refresh(String refreshToken) {
        return RestAssured.given()
                .formParam("grant_type", "refresh_token").formParam("client_id", realmName)
                .formParam("refresh_token", refreshToken)
                .post(keycloakUrl + "/realms/" + realmName + "/protocol/openid-connect/token")
                .statusCode();
    }
}
