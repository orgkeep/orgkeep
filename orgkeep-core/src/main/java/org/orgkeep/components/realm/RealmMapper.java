package org.orgkeep.components.realm;

import org.orgkeep.api.model.CreateRealmRequest;
import org.orgkeep.api.model.OrgkeepApiClient;
import org.orgkeep.api.model.SmtpSettings;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.mapstruct.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;


@Mapper(componentModel = "cdi", unmappedTargetPolicy = ReportingPolicy.WARN)
public interface RealmMapper {
    // Keycloak representations have dozens of properties: only map the listed ones instead of warning on each
    @BeanMapping(ignoreByDefault = true)
    @Mapping(source="realmName", target="realm")
    @Mapping(target = "duplicateEmailsAllowed", constant = "false")
    @Mapping(target = "loginWithEmailAllowed", constant = "true")
    @Mapping(target = "organizationsEnabled", constant = "true")
    @Mapping(target = "enabled", constant = "true")
    RealmRepresentation toRealmRepresentation(CreateRealmRequest request);

    // credentials are set by addCredentials below
    @BeanMapping(ignoreByDefault = true)
    @Mapping(target = "username", source = "request", qualifiedByName = "adminUsername")
    @Mapping(source = "request.adminEmail", target = "email")
    @Mapping(source = "request.adminFirstName", target = "firstName")
    @Mapping(source = "request.adminLastName", target = "lastName")
    @Mapping(target = "emailVerified", constant = "true")
    @Mapping(target = "enabled",constant = "true")
    UserRepresentation toUserRepresentation(CreateRealmRequest request, @Context String password);

    /**
     * Client used by the users of every organisation to log in to the applications of the realm owner. Its clientId is
     * the realm name, so that nothing in the login URL or the token reveals Orgkeep (white label).
     * Public client, authorization code flow with PKCE: the redirect URIs are left to the realm owner.
     */
    default ClientRepresentation toLoginClient(String realmName) {
        ClientRepresentation client = new ClientRepresentation();
        client.setClientId(realmName);
        client.setName(realmName);
        client.setEnabled(true);
        client.setPublicClient(true);
        client.setStandardFlowEnabled(true);
        client.setImplicitFlowEnabled(false);
        client.setDirectAccessGrantsEnabled(false);
        client.setServiceAccountsEnabled(false);
        client.setAttributes(Map.of("pkce.code.challenge.method", "S256"));
        return client;
    }

    /**
     * Client to call the API of Orgkeep with (see {@link OrgkeepApiClient}). Public client, authorization code flow
     * with PKCE, like the login client: the redirect URIs are left to the integrator. Only the roles of its scope
     * (realm roles and org roles), no catalog.
     */
    default ClientRepresentation toOrgkeepApiClient() {
        ClientRepresentation client = new ClientRepresentation();
        client.setClientId(OrgkeepApiClient.CLIENT_ID);
        client.setName("Orgkeep API");
        client.setEnabled(true);
        client.setPublicClient(true);
        client.setStandardFlowEnabled(true);
        client.setImplicitFlowEnabled(false);
        client.setDirectAccessGrantsEnabled(false);
        client.setServiceAccountsEnabled(false);
        client.setFullScopeAllowed(false);
        client.setAttributes(Map.of("pkce.code.challenge.method", "S256"));
        return client;
    }

    /**
     * The client Orgkeep calls the Keycloak admin API with, in the realm it manages: confidential, client credentials
     * only. fullScopeAllowed is set explicitly: with false, its token carries no realm-management role and the admin
     * API answers 403.
     */
    default ClientRepresentation toAdminClient(String clientId, String secret) {
        ClientRepresentation client = new ClientRepresentation();
        client.setClientId(clientId);
        client.setName("Orgkeep admin");
        client.setEnabled(true);
        client.setPublicClient(false);
        client.setServiceAccountsEnabled(true);
        client.setStandardFlowEnabled(false);
        client.setImplicitFlowEnabled(false);
        client.setDirectAccessGrantsEnabled(false);
        client.setFullScopeAllowed(true);
        client.setSecret(secret);
        return client;
    }

    /** The service account user of the admin client, with the role realm-management/realm-admin of its own realm. */
    default UserRepresentation toAdminServiceAccount(String clientId) {
        UserRepresentation user = new UserRepresentation();
        user.setUsername("service-account-" + clientId.toLowerCase());
        user.setServiceAccountClientId(clientId);
        user.setEnabled(true);
        user.setClientRoles(Map.of("realm-management", List.of("realm-admin")));
        return user;
    }

    /** The "smtpServer" map of the realm representation, with the keys of the Email tab of the Keycloak console. */
    default Map<String, String> toSmtpServer(SmtpSettings smtp) {
        Map<String, String> server = new HashMap<>();
        server.put("host", smtp.host());
        server.put("port", String.valueOf(smtp.port()));
        server.put("from", smtp.from());
        if (smtp.fromDisplayName() != null) {
            server.put("fromDisplayName", smtp.fromDisplayName());
        }
        server.put("starttls", String.valueOf(smtp.starttls()));
        server.put("ssl", String.valueOf(smtp.ssl()));
        server.put("auth", String.valueOf(smtp.username() != null));
        if (smtp.username() != null) {
            server.put("user", smtp.username());
            server.put("password", smtp.password());
        }
        return server;
    }

    @Named("adminUsername")
    default String adminUsername(CreateRealmRequest request) {
        return request.adminUsername() != null ? request.adminUsername() : request.adminEmail();
    }

    @AfterMapping
    default void addCredentials(CreateRealmRequest request, @MappingTarget UserRepresentation userRepresentation,
                                @Context String password) {
        CredentialRepresentation credentials = new CredentialRepresentation();
        credentials.setType(CredentialRepresentation.PASSWORD);
        credentials.setValue(password);
        credentials.setTemporary(!Boolean.FALSE.equals(request.temporaryPassword()));

        userRepresentation.setCredentials(List.of(credentials));
    }
}
