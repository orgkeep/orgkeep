package org.orgkeep.components.organisation.member.invitation;

import org.orgkeep.api.events.UserCreated;
import org.orgkeep.api.exceptions.OperationNotAllowedException;
import org.orgkeep.api.model.OrgClients;
import org.orgkeep.api.model.OrgRole;
import org.orgkeep.config.OrgkeepConfig;
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
     * Whether the member has not followed the invitation link yet, read from the full representation the caller
     * already holds: no realm, no Keycloak call. True when the email is not verified and the required action
     * UPDATE_PASSWORD is still pending (a member created by add, see MemberMapper.toUserRepresentation). Keycloak
     * removes the action once a password is set, through the invitation link or "forgot password".
     * <p>
     * Both conditions are needed, for a different reason each: requiredActions alone would show an owner created
     * with a temporary password as pending too (it also carries UPDATE_PASSWORD, see
     * OrgMapper.toOwnerRepresentation, but is created with emailVerified=true, so it never matches here);
     * emailVerified alone would never clear, since it stays false whatever the member does (neither the invitation
     * link nor "forgot password" ever sets it to true): a member who chose a password would stay pending forever.
     */
    public boolean isPending(UserRepresentation user) {
        return !Boolean.TRUE.equals(user.isEmailVerified())
                && user.getRequiredActions() != null && user.getRequiredActions().contains(UPDATE_PASSWORD);
    }

    /**
     * Sends with the chosen client, falling back to no client on a 400 (the client was disabled or deleted by hand):
     * the invitation is never lost because of it, logged at INFO at most. Any other failure is left to the caller
     * (invite logs an error and never fails, resend propagates).
     */
    private void send(String realm, String userId, String clientId) {
        // no VERIFY_EMAIL action needed: following the link proves the user received the email
        try {
            users.sendActionsEmail(realm, userId, clientId, List.of(UPDATE_PASSWORD), config.invitation().linkLifespan());
        } catch (WebApplicationException e) {
            if (clientId == null || e.getResponse().getStatus() != Response.Status.BAD_REQUEST.getStatusCode()) {
                throw e;
            }
            log.info("Invitation of user {} in realm '{}' sent without client: Keycloak refused client '{}' ({})",
                    userId, realm, clientId, e.getResponse().getStatus());
            users.sendActionsEmail(realm, userId, null, List.of(UPDATE_PASSWORD), config.invitation().linkLifespan());
        }
        log.info("Invitation email sent to user {} in realm '{}'", userId, realm);
    }
}
