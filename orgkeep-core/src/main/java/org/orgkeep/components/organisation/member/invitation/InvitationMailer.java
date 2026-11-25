package org.orgkeep.components.organisation.member.invitation;

import org.orgkeep.api.events.UserCreated;
import org.orgkeep.api.exceptions.OperationNotAllowedException;
import org.orgkeep.api.model.OrgClients;
import org.orgkeep.api.model.OrgRole;
import org.orgkeep.config.OrgkeepConfig;
import org.orgkeep.repository.keycloak.InvalidRequiredActionsException;
import org.orgkeep.repository.keycloak.KeycloakClients;
import org.orgkeep.repository.keycloak.KeycloakRealms;
import org.orgkeep.repository.keycloak.KeycloakUsers;
import io.quarkus.vertx.ConsumeEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.representations.idm.UserRepresentation;

import java.util.List;

/**
 * Invites the users created without password: Keycloak emails them a link to choose one.
 * The email itself (template, language, SMTP server) belongs to Keycloak, Orgkeep only triggers it.
 */
@Slf4j
@ApplicationScoped
@RequiredArgsConstructor
public class InvitationMailer {
    /** Also read by MemberMapper (another package), which sets it on a member created by add. */
    public static final String UPDATE_PASSWORD = "UPDATE_PASSWORD";
    /** Only ever put in the invitation link, never in a user's own requiredActions (see {@link #send}). */
    private static final String VERIFY_EMAIL = "VERIFY_EMAIL";
    private static final List<String> ACTIONS_WITH_VERIFY_EMAIL = List.of(UPDATE_PASSWORD, VERIFY_EMAIL);
    private static final List<String> ACTIONS_WITHOUT_VERIFY_EMAIL = List.of(UPDATE_PASSWORD);

    private final KeycloakRealms realms;
    private final KeycloakUsers users;
    private final KeycloakClients clients;
    private final OrgkeepConfig config;

    // blocking: the admin client makes blocking HTTP calls, which must not run on the event loop
    @ConsumeEvent(value = UserCreated.ADDRESS, blocking = true)
    void onUserCreated(UserCreated event) {
        if (event.passwordSet()) {
            return;
        }
        try {
            invite(event.realmName(), event.userId(), event.owner());
        } catch (Exception e) {
            log.error("Invitation email failed for user {} in realm {}", event.userId(), event.realmName(), e);
        }
    }

    /** At the creation of the user: never fails, creating the user must not fail because no email can be sent. */
    void invite(String realm, String userId, boolean owner) {
        if (!realms.hasSmtpServer(realm)) {
            log.warn("No SMTP server on realm '{}': user {} gets no invitation and cannot log in until a password is set",
                    realm, userId);
            return;
        }
        send(realm, userId, targetClientId(realm, owner));
    }

    /**
     * Sends the invitation again, asked by an admin (expired link, SMTP server configured after the creation of the
     * user). Unlike {@link #invite}, fails when nothing can be sent: the admin must know why.
     *
     * @throws OperationNotAllowedException if the user already has a password, or the realm has no SMTP server
     */
    public void resend(String realm, String orgName, String userId) {
        // a password reset sent by an org admin to a member who did not ask for it: not what an invitation is for
        if (users.hasPassword(realm, userId)) {
            throw new OperationNotAllowedException("The user has already chosen a password: there is no invitation to send");
        }
        if (!realms.hasSmtpServer(realm)) {
            throw new OperationNotAllowedException("The realm has no SMTP server: the invitation cannot be sent");
        }
        // resend only exists for org members: the realm owner case (owner without an org) does not occur here,
        // the roles are stable at that point, so they are read instead of carried by an event
        send(realm, userId, targetClientId(realm, isOrgOwner(realm, orgName, userId)));
    }

    /**
     * The realm owner and an org owner land on the dashboard, when it is deployed (orgkeep.dashboard.url); every
     * other invited user, on the login client, named after the realm. Returned even when the target client has no
     * home URL: Keycloak then falls back to today's page, and reading the client before every email is avoided.
     */
    private String targetClientId(String realm, boolean owner) {
        return owner && config.dashboard().url().isPresent() ? config.dashboard().clientId() : realm;
    }

    /** Whether the member currently holds org-owner of orgName, read from their effective client roles. */
    private boolean isOrgOwner(String realm, String orgName, String userId) {
        return clients.findUuid(realm, OrgClients.clientId(orgName))
                .map(clientUuid -> users.effectiveClientRoles(realm, userId, clientUuid).contains(OrgRole.ORG_OWNER.getRoleName()))
                .orElse(false);
    }

    /**
     * Whether the member has not followed the invitation link yet, read from the full representation and the role
     * the caller already computed: no extra Keycloak call. True when the required action UPDATE_PASSWORD is still
     * pending (a member created by add, see MemberMapper.toUserRepresentation) and the member does not hold
     * ORG_OWNER. Keycloak removes the required action once a password is set, through the invitation link or
     * "forgot password".
     * <p>
     * The role excludes the owner rather than emailVerified: an owner created with a temporary password also
     * carries UPDATE_PASSWORD (see OrgMapper.toOwnerRepresentation), so requiredActions alone would show it as
     * pending too; the role says directly what is meant, instead of relying on owners being created with
     * emailVerified=true. emailVerified is deliberately not read here any more: since the invitation link now
     * carries VERIFY_EMAIL (see {@link #send}), it does become true once the link is followed, but that is its own
     * signal (the email was proven), not a stand-in for "has not chosen a password yet".
     */
    public boolean isPending(UserRepresentation user, OrgRole role) {
        return role != OrgRole.ORG_OWNER
                && user.getRequiredActions() != null && user.getRequiredActions().contains(UPDATE_PASSWORD);
    }

    /**
     * Sends with [UPDATE_PASSWORD, VERIFY_EMAIL], through the existing client fallback (a 400 with the chosen
     * client retries without client, as today): following the link proves the member received the email, so
     * Keycloak (26.8+) marks the email verified when the link is followed, with no second email sent. VERIFY_EMAIL
     * is only ever in the link: it never ends up in the user's own requiredActions, which would make Keycloak send
     * its own verification email at the first login.
     * <p>
     * If Keycloak refuses the actions themselves (an action it does not recognize on the realm, not registered —
     * on 26.8.1 a disabled VERIFY_EMAIL is accepted, measured — raising InvalidRequiredActionsException), retries
     * once with [UPDATE_PASSWORD] only, with whichever client was just
     * accepted. Keycloak checks the client before the actions, so a client refusal and an actions refusal never
     * compete for the same call: at most three calls in total (client refused, then no client refused on actions,
     * then no client with fewer actions), never a fourth. Not retried a second time: any other failure is left to
     * the caller as today (invite logs an error and never fails, resend propagates).
     */
    private void send(String realm, String userId, String clientId) {
        try {
            sendActions(realm, userId, clientId, ACTIONS_WITH_VERIFY_EMAIL);
        } catch (InvalidRequiredActionsException e) {
            // the client was already accepted at this point: only the actions are retried, with the same client
            retryWithoutVerifyEmail(realm, userId, clientId);
            return;
        } catch (WebApplicationException e) {
            if (clientId == null || e.getResponse().getStatus() != Response.Status.BAD_REQUEST.getStatusCode()) {
                throw e;
            }
            log.info("Invitation of user {} in realm '{}' sent without client: Keycloak refused client '{}' ({})",
                    userId, realm, clientId, e.getResponse().getStatus());
            try {
                sendActions(realm, userId, null, ACTIONS_WITH_VERIFY_EMAIL);
            } catch (InvalidRequiredActionsException e2) {
                // no client was already resolved here: the retry stays without a client too
                retryWithoutVerifyEmail(realm, userId, null);
                return;
            }
        }
        log.info("Invitation email sent to user {} in realm '{}'", userId, realm);
    }

    private void retryWithoutVerifyEmail(String realm, String userId, String clientId) {
        log.warn("VERIFY_EMAIL refused on realm '{}': invitation of user {} sent without email verification",
                realm, userId);
        sendActions(realm, userId, clientId, ACTIONS_WITHOUT_VERIFY_EMAIL);
        log.info("Invitation email sent to user {} in realm '{}'", userId, realm);
    }

    private void sendActions(String realm, String userId, String clientId, List<String> actions) {
        users.sendActionsEmail(realm, userId, clientId, actions, config.invitation().linkLifespan());
    }
}
