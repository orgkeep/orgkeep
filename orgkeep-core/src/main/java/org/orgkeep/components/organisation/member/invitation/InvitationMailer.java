package org.orgkeep.components.organisation.member.invitation;

import org.orgkeep.api.events.UserCreated;
import org.orgkeep.api.exceptions.OperationNotAllowedException;
import org.orgkeep.config.OrgkeepConfig;
import org.orgkeep.repository.keycloak.KeycloakRealms;
import org.orgkeep.repository.keycloak.KeycloakUsers;
import io.quarkus.vertx.ConsumeEvent;
import jakarta.enterprise.context.ApplicationScoped;
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
    static final String UPDATE_PASSWORD = "UPDATE_PASSWORD";

    private final KeycloakRealms realms;
    private final KeycloakUsers users;
    private final OrgkeepConfig config;

    // blocking: the admin client makes blocking HTTP calls, which must not run on the event loop
    @ConsumeEvent(value = UserCreated.ADDRESS, blocking = true)
    void onUserCreated(UserCreated event) {
        if (event.passwordSet()) {
            return;
        }
        try {
            invite(event.realmName(), event.userId());
        } catch (Exception e) {
            log.error("Invitation email failed for user {} in realm {}", event.userId(), event.realmName(), e);
        }
    }

    /** At the creation of the user: never fails, creating the user must not fail because no email can be sent. */
    void invite(String realm, String userId) {
        if (!realms.hasSmtpServer(realm)) {
            log.warn("No SMTP server on realm '{}': user {} gets no invitation and cannot log in until a password is set",
                    realm, userId);
            return;
        }
        send(realm, userId);
    }

    /**
     * Sends the invitation again, asked by an admin (expired link, SMTP server configured after the creation of the
     * user). Unlike {@link #invite}, fails when nothing can be sent: the admin must know why.
     *
     * @throws OperationNotAllowedException if the user already has a password, or the realm has no SMTP server
     */
    public void resend(String realm, String userId) {
        // a password reset sent by an org admin to a member who did not ask for it: not what an invitation is for
        if (users.hasPassword(realm, userId)) {
            throw new OperationNotAllowedException("The user has already chosen a password: there is no invitation to send");
        }
        if (!realms.hasSmtpServer(realm)) {
            throw new OperationNotAllowedException("The realm has no SMTP server: the invitation cannot be sent");
        }
        send(realm, userId);
    }

    /**
     * Whether the user has not followed the invitation link yet: no password and the email not verified (following
     * the link verifies it). Both are needed: an owner created with a password has not verified their email either.
     */
    public boolean isPending(String realm, UserRepresentation user) {
        // the email first: it comes with the user, the password costs a Keycloak call
        return !Boolean.TRUE.equals(user.isEmailVerified()) && !users.hasPassword(realm, user.getId());
    }

    private void send(String realm, String userId) {
        // no VERIFY_EMAIL action needed: following the link proves the user received the email
        users.sendActionsEmail(realm, userId, List.of(UPDATE_PASSWORD), config.invitation().linkLifespan());
        log.info("Invitation email sent to user {} in realm '{}'", userId, realm);
    }
}
