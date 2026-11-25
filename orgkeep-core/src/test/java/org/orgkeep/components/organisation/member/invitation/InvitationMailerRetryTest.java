package org.orgkeep.components.organisation.member.invitation;

import org.orgkeep.api.events.UserCreated;
import org.orgkeep.api.model.OrgRole;
import org.orgkeep.config.OrgkeepConfig;
import org.orgkeep.repository.keycloak.InvalidRequiredActionsException;
import org.orgkeep.repository.keycloak.KeycloakClients;
import org.orgkeep.repository.keycloak.KeycloakRealms;
import org.orgkeep.repository.keycloak.KeycloakUsers;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.keycloak.representations.idm.UserRepresentation;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The retry and fallback branches of InvitationMailer.send depend on how Keycloak fails: easier and faster to
 * force with a mocked KeycloakUsers than to reproduce against a real Keycloak (InvitationMailerTest covers the
 * happy path there). Driven through resend (public) and invite (package-private), never send itself (private).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InvitationMailerRetryTest {
    private static final String REALM = "acme";
    private static final String ORG = "org";
    private static final String USER_ID = "user-1";
    private static final Duration LIFESPAN = Duration.ofHours(72);
    private static final List<String> WITH_VERIFY_EMAIL = List.of(InvitationMailer.UPDATE_PASSWORD, "VERIFY_EMAIL");
    private static final List<String> WITHOUT_VERIFY_EMAIL = List.of(InvitationMailer.UPDATE_PASSWORD);

    @Mock private KeycloakRealms realms;
    @Mock private KeycloakUsers users;
    @Mock private KeycloakClients clients;
    @Mock private OrgkeepConfig config;
    @Mock private OrgkeepConfig.Invitation invitation;

    private InvitationMailer mailer;

    @BeforeEach
    void setUp() {
        mailer = new InvitationMailer(realms, users, clients, config);
        // only resend() reads these: invite() never checks a password or the current roles of the member
        when(config.invitation()).thenReturn(invitation);
        when(invitation.linkLifespan()).thenReturn(LIFESPAN);
        when(users.hasPassword(REALM, USER_ID)).thenReturn(false);
        when(realms.hasSmtpServer(REALM)).thenReturn(true);
        // no org client found: resend treats the member as not an org owner, so the client is the realm's login
        // client (named after the realm), same as invite() for a non-owner
        when(clients.findUuid(eq(REALM), any())).thenReturn(Optional.empty());
    }

    @Test
    void happyPathSendsOnceWithBothActionsAndTheChosenClient() {
        mailer.resend(REALM, ORG, USER_ID);

        verify(users, times(1)).sendActionsEmail(REALM, USER_ID, REALM, WITH_VERIFY_EMAIL, LIFESPAN);
        verify(users, never()).sendActionsEmail(eq(REALM), eq(USER_ID), any(), eq(WITHOUT_VERIFY_EMAIL), eq(LIFESPAN));
    }

    @Test
    void verifyEmailRefusedRetriesOnceWithTheSameClient() {
        doThrow(new InvalidRequiredActionsException("refused", null))
                .when(users).sendActionsEmail(REALM, USER_ID, REALM, WITH_VERIFY_EMAIL, LIFESPAN);

        mailer.resend(REALM, ORG, USER_ID);

        verify(users, times(1)).sendActionsEmail(REALM, USER_ID, REALM, WITHOUT_VERIFY_EMAIL, LIFESPAN);
        verify(users, times(2)).sendActionsEmail(eq(REALM), eq(USER_ID), eq(REALM), any(), eq(LIFESPAN));
        // an actions refusal is not a client refusal: the client-less fallback must never fire here
        verify(users, never()).sendActionsEmail(any(), any(), isNull(), any(), any());
    }

    @Test
    void clientRefusalAndVerifyEmailRefusalCombineWithoutAFourthCall() {
        WebApplicationException clientRefused = new WebApplicationException(Response.status(400).build());
        doThrow(clientRefused).when(users).sendActionsEmail(REALM, USER_ID, REALM, WITH_VERIFY_EMAIL, LIFESPAN);
        doThrow(new InvalidRequiredActionsException("refused", null))
                .when(users).sendActionsEmail(REALM, USER_ID, null, WITH_VERIFY_EMAIL, LIFESPAN);

        mailer.resend(REALM, ORG, USER_ID);

        verify(users, times(1)).sendActionsEmail(REALM, USER_ID, REALM, WITH_VERIFY_EMAIL, LIFESPAN);
        verify(users, times(1)).sendActionsEmail(REALM, USER_ID, null, WITH_VERIFY_EMAIL, LIFESPAN);
        verify(users, times(1)).sendActionsEmail(REALM, USER_ID, null, WITHOUT_VERIFY_EMAIL, LIFESPAN);
        // never retried with the client again: the client was already proven refused
        verify(users, never()).sendActionsEmail(REALM, USER_ID, REALM, WITHOUT_VERIFY_EMAIL, LIFESPAN);
        verify(users, times(3)).sendActionsEmail(any(), any(), any(), any(), any());
    }

    @Test
    void verifyEmailRefusedOnTheRetryTooPropagatesFromResend() {
        doThrow(new InvalidRequiredActionsException("refused", null))
                .when(users).sendActionsEmail(REALM, USER_ID, REALM, WITH_VERIFY_EMAIL, LIFESPAN);
        doThrow(new InvalidRequiredActionsException("refused again", null))
                .when(users).sendActionsEmail(REALM, USER_ID, REALM, WITHOUT_VERIFY_EMAIL, LIFESPAN);

        assertThrows(InvalidRequiredActionsException.class, () -> mailer.resend(REALM, ORG, USER_ID));
    }

    @Test
    void verifyEmailRefusedOnTheRetryTooIsAbsorbedByTheUserCreatedConsumer() {
        doThrow(new InvalidRequiredActionsException("refused", null))
                .when(users).sendActionsEmail(REALM, USER_ID, REALM, WITH_VERIFY_EMAIL, LIFESPAN);
        doThrow(new InvalidRequiredActionsException("refused again", null))
                .when(users).sendActionsEmail(REALM, USER_ID, REALM, WITHOUT_VERIFY_EMAIL, LIFESPAN);

        // invite() itself propagates (see verifyEmailRefusedOnTheRetryTooPropagatesFromResend): only the event bus
        // consumer catches it, since creating the user must not fail because the invitation could not be sent
        assertDoesNotThrow(() -> mailer.onUserCreated(new UserCreated(REALM, ORG, USER_ID, false, false)));
    }

    @Test
    void nonBadRequestErrorIsPropagatedWithoutRetry() {
        WebApplicationException serverError = new WebApplicationException(Response.status(500).build());
        doThrow(serverError).when(users).sendActionsEmail(REALM, USER_ID, REALM, WITH_VERIFY_EMAIL, LIFESPAN);

        WebApplicationException thrown = assertThrows(WebApplicationException.class, () -> mailer.resend(REALM, ORG, USER_ID));
        assertSame(serverError, thrown);
        verify(users, times(1)).sendActionsEmail(any(), any(), any(), any(), any());
    }

    @Test
    void isPendingIsTrueForNonOwnersWithUpdatePasswordRegardlessOfEmailVerified() {
        UserRepresentation user = new UserRepresentation();
        user.setRequiredActions(List.of(InvitationMailer.UPDATE_PASSWORD));
        user.setEmailVerified(true);

        assertTrue(mailer.isPending(user, OrgRole.ORG_USER));
        assertTrue(mailer.isPending(user, OrgRole.ORG_ADMIN));
    }

    @Test
    void isPendingIsFalseForTheOwnerEvenWithUpdatePassword() {
        UserRepresentation user = new UserRepresentation();
        user.setRequiredActions(List.of(InvitationMailer.UPDATE_PASSWORD));

        assertFalse(mailer.isPending(user, OrgRole.ORG_OWNER));
    }

    @Test
    void isPendingIsFalseWithoutTheRequiredActionOrWithNullRequiredActions() {
        UserRepresentation withoutIt = new UserRepresentation();
        withoutIt.setRequiredActions(List.of());
        UserRepresentation withNullActions = new UserRepresentation();

        assertFalse(mailer.isPending(withoutIt, OrgRole.ORG_USER));
        assertFalse(mailer.isPending(withNullActions, OrgRole.ORG_USER));
    }
}
