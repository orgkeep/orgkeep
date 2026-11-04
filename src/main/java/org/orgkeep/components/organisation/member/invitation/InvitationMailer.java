package org.orgkeep.components.organisation.member.invitation;

import org.orgkeep.api.events.UserCreated;
import org.orgkeep.config.OrgkeepConfig;
import org.orgkeep.repository.keycloak.KeycloakRealms;
import org.orgkeep.repository.keycloak.KeycloakUsers;
import io.quarkus.vertx.ConsumeEvent;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

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

    void invite(String realm, String userId) {
        if (!realms.hasSmtpServer(realm)) {
            log.warn("No SMTP server on realm '{}': user {} gets no invitation and cannot log in until a password is set",
                    realm, userId);
            return;
        }
        // no VERIFY_EMAIL action needed: following the link proves the user received the email
        users.sendActionsEmail(realm, userId, List.of(UPDATE_PASSWORD), config.invitation().linkLifespan());
        log.info("Invitation email sent to user {} in realm '{}'", userId, realm);
    }
}
