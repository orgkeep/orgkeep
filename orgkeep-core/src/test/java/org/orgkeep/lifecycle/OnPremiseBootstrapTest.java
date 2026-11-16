package org.orgkeep.lifecycle;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.orgkeep.api.RealmProvisioner;
import org.orgkeep.api.model.CreateRealmRequest;
import org.orgkeep.components.realm.AdminClient;
import org.orgkeep.components.realm.BootstrapCredentials;
import org.orgkeep.components.realm.RealmBootstrap;
import org.orgkeep.config.OrgkeepConfig;
import org.orgkeep.repository.keycloak.KeycloakAdminAccess;
import org.orgkeep.repository.keycloak.KeycloakAdminAccess.Probe;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The startup sequence with every collaborator mocked: each test checks what is called and what is not. No Quarkus,
 * no Keycloak: the sequence is plain Java, and the real Keycloak is covered by RealmBootstrapTest and
 * ServiceAccountPathTest.
 */
class OnPremiseBootstrapTest {
    private static final BootstrapCredentials MASTER = new BootstrapCredentials("admin", "admin");

    private final OrgkeepConfig config = mock(OrgkeepConfig.class);
    private final OrgkeepConfig.Owner owner = mock(OrgkeepConfig.Owner.class);
    private final OrgkeepConfig.Keycloak keycloak = mock(OrgkeepConfig.Keycloak.class);
    private final OrgkeepConfig.Dashboard dashboard = mock(OrgkeepConfig.Dashboard.class);
    private final RealmProvisioner realmService = mock(RealmProvisioner.class);
    private final RealmBootstrap realmBootstrap = mock(RealmBootstrap.class);
    private final KeycloakAdminAccess adminAccess = mock(KeycloakAdminAccess.class);
    private final BootstrapCredentialsSource credentialsSource = mock(BootstrapCredentialsSource.class);

    private final OnPremiseBootstrap bootstrap = new OnPremiseBootstrap();

    @BeforeEach
    void setUp() {
        when(config.mode()).thenReturn(OrgkeepConfig.Mode.ON_PREM);
        when(config.realmName()).thenReturn(Optional.of("acme"));
        when(config.owner()).thenReturn(owner);
        when(owner.email()).thenReturn(Optional.of("owner@acme.test"));
        when(owner.password()).thenReturn(Optional.of("changeme"));
        when(owner.firstName()).thenReturn(Optional.of("Alice"));
        when(owner.lastName()).thenReturn(Optional.of("Admin"));
        when(owner.username()).thenReturn(Optional.empty());
        when(owner.temporaryPassword()).thenReturn(true);
        when(config.keycloak()).thenReturn(keycloak);
        when(keycloak.clientId()).thenReturn("orgkeep-admin");
        when(keycloak.clientSecret()).thenReturn(Optional.of("the-secret"));
        when(config.smtp()).thenReturn(Optional.empty());
        when(config.dashboard()).thenReturn(dashboard);
        when(dashboard.loginTheme()).thenReturn(Optional.empty());
        when(adminAccess.serverUrl()).thenReturn("http://keycloak:8080");
        when(credentialsSource.read()).thenReturn(Optional.empty());

        bootstrap.config = config;
        bootstrap.realmService = realmService;
        bootstrap.realmBootstrap = realmBootstrap;
        bootstrap.adminAccess = adminAccess;
        bootstrap.bootstrapCredentials = credentialsSource;
    }

    @Test
    void existingRealmWithoutBootstrapCredentialsOnlyChecksDefaults() {
        when(adminAccess.probe()).thenReturn(Probe.OK);
        when(realmService.exists("acme")).thenReturn(true);

        bootstrap.onStart(null);

        verify(realmService).ensureDefaults("acme");
        verify(realmService).grantConsoleAccess("acme");
        verifyNoInteractions(realmBootstrap);
    }

    @Test
    void existingRealmWithBootstrapCredentialsNeverUsesThem() {
        when(credentialsSource.read()).thenReturn(Optional.of(MASTER));
        when(adminAccess.probe()).thenReturn(Probe.OK);
        when(realmService.exists("acme")).thenReturn(true);

        bootstrap.onStart(null);

        // the WARN itself is only a log line: what matters is that master is not used
        verifyNoInteractions(realmBootstrap);
        verify(realmService).ensureDefaults("acme");
    }

    @Test
    void missingRealmIsCreatedWithTheBootstrapCredentials() {
        when(credentialsSource.read()).thenReturn(Optional.of(MASTER));
        when(adminAccess.probe()).thenReturn(Probe.REALM_NOT_FOUND, Probe.OK);

        bootstrap.onStart(null);

        verify(realmBootstrap).createWithBootstrapAdmin(any(CreateRealmRequest.class),
                eq(new AdminClient("orgkeep-admin", "the-secret")), eq(MASTER));
        verify(adminAccess, org.mockito.Mockito.times(2)).probe();
        // the rest of the startup goes on
        verify(realmService).grantConsoleAccess("acme");
        verify(realmService, never()).ensureDefaults("acme");
    }

    @Test
    void missingRealmWithoutBootstrapCredentialsFailsWithAMessageThatTellsWhatToDo() {
        when(adminAccess.probe()).thenReturn(Probe.REALM_NOT_FOUND);

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> bootstrap.onStart(null));

        assertTrue(e.getMessage().contains("Realm 'acme' not found at http://keycloak:8080"), e.getMessage());
        assertTrue(e.getMessage().contains("KEYCLOAK_ADMIN_USER and KEYCLOAK_ADMIN_PASSWORD"), e.getMessage());
        verifyNoInteractions(realmBootstrap);
        verify(realmService, never()).grantConsoleAccess("acme");
    }

    @Test
    void refusedClientFailsAndCallsNothingElse() {
        when(credentialsSource.read()).thenReturn(Optional.of(MASTER));
        when(adminAccess.probe()).thenReturn(Probe.UNAUTHORIZED);

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> bootstrap.onStart(null));

        assertTrue(e.getMessage().contains("Keycloak refused the client 'orgkeep-admin' of realm 'acme'"), e.getMessage());
        assertTrue(!e.getMessage().contains("the-secret"), "no secret in the message");
        verifyNoInteractions(realmBootstrap, realmService);
    }

    // the runtime client is an admin of master (tests): the token works, only the realm is missing
    @Test
    void realmAbsentWhileTheTokenWorksIsCreatedToo() {
        when(credentialsSource.read()).thenReturn(Optional.of(MASTER));
        when(adminAccess.probe()).thenReturn(Probe.OK);
        when(realmService.exists("acme")).thenReturn(false);

        bootstrap.onStart(null);

        verify(realmBootstrap).createWithBootstrapAdmin(any(CreateRealmRequest.class),
                eq(new AdminClient("orgkeep-admin", "the-secret")), eq(MASTER));
        verify(adminAccess, org.mockito.Mockito.times(1)).probe();
    }

    @Test
    void forbiddenOnTheFirstAdminCallTellsWhichRightsAreMissing() {
        when(adminAccess.probe()).thenReturn(Probe.OK);
        when(realmService.exists("acme")).thenReturn(true);
        Response forbidden = mock(Response.class);
        when(forbidden.getStatus()).thenReturn(403);
        WebApplicationException refusal = mock(WebApplicationException.class);
        when(refusal.getResponse()).thenReturn(forbidden);
        org.mockito.Mockito.doThrow(refusal).when(realmService).ensureDefaults("acme");

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> bootstrap.onStart(null));

        assertTrue(e.getMessage().contains("lacks rights on the admin API"), e.getMessage());
        assertTrue(e.getMessage().contains("realm-management/realm-admin"), e.getMessage());
        assertTrue(e.getMessage().contains("Full scope allowed"), e.getMessage());
    }

    // a 401 in the middle of the sequence (the secret changed since the probe, say): the same message as a refused probe
    @Test
    void unauthorizedMidSequenceIsReportedAsARefusedClient() {
        when(adminAccess.probe()).thenReturn(Probe.OK);
        when(realmService.exists("acme")).thenReturn(true);
        Response unauthorized = mock(Response.class);
        when(unauthorized.getStatus()).thenReturn(401);
        WebApplicationException refusal = mock(WebApplicationException.class);
        when(refusal.getResponse()).thenReturn(unauthorized);
        org.mockito.Mockito.doThrow(refusal).when(realmService).grantConsoleAccess("acme");

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> bootstrap.onStart(null));

        assertTrue(e.getMessage().contains("Keycloak refused the client 'orgkeep-admin' of realm 'acme'"), e.getMessage());
    }

    @Test
    void missingClientSecretFailsBeforeAnyCall() {
        when(keycloak.clientSecret()).thenReturn(Optional.empty());

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> bootstrap.onStart(null));

        assertEquals("orgkeep.keycloak.client-secret (ORGKEEP_KEYCLOAK_CLIENT_SECRET) is required in ON_PREM mode",
                e.getMessage());
        verifyNoInteractions(adminAccess, realmService, realmBootstrap);
    }

    @Test
    void saasModeDoesNothing() {
        when(config.mode()).thenReturn(OrgkeepConfig.Mode.SAAS);

        bootstrap.onStart(null);

        verifyNoInteractions(adminAccess, realmService, realmBootstrap);
    }
}
