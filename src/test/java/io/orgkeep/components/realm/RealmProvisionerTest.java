package io.orgkeep.components.realm;

import io.orgkeep.api.CatalogService;
import io.orgkeep.api.RealmProvisioner;
import io.orgkeep.api.model.Catalog;
import io.orgkeep.api.model.Catalogs;
import io.orgkeep.api.exceptions.RealmAlreadyExistsException;
import io.orgkeep.api.model.CreateRealmRequest;
import io.orgkeep.api.model.CreateRealmResponse;
import io.orgkeep.api.OrgService;
import io.orgkeep.api.model.CreateOrgRequest;
import io.orgkeep.api.model.RealmRole;
import io.orgkeep.api.model.SmtpSettings;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.ClientScopeRepresentation;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
}
