package org.orgkeep.components.organisation.serviceaccount;

import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;
import org.orgkeep.api.model.CreateServiceAccountRequest;
import org.orgkeep.api.model.OrgClients;
import org.orgkeep.api.model.ServiceAccount;

import java.util.List;
import java.util.Map;

// imports: the classes the expressions below may use without their package
@Mapper(componentModel = "cdi", unmappedTargetPolicy = ReportingPolicy.WARN, imports = OrgClients.class)
public interface ServiceAccountMapper {
    /**
     * The client of a service account: confidential, client credentials only, no browser flow, never the whole scope
     * of the user (fullScopeAllowed=false). The client scopes are reduced afterwards. The attribute "org" is what
     * ties the client to its org, with the group membership of its user.
     */
    // ClientRepresentation has dozens of properties: only map the listed ones instead of warning on each
    @BeanMapping(ignoreByDefault = true)
    @Mapping(target = "clientId", expression = "java(OrgClients.serviceAccountClientId(orgName, request.name()))")
    @Mapping(target = "name", source = "request.name")
    @Mapping(target = "description", source = "request.description")
    @Mapping(target = "protocol", constant = "openid-connect")
    @Mapping(target = "enabled", constant = "true")
    @Mapping(target = "publicClient", constant = "false")
    @Mapping(target = "clientAuthenticatorType", constant = "client-secret")
    @Mapping(target = "serviceAccountsEnabled", constant = "true")
    @Mapping(target = "standardFlowEnabled", constant = "false")
    @Mapping(target = "implicitFlowEnabled", constant = "false")
    @Mapping(target = "directAccessGrantsEnabled", constant = "false")
    @Mapping(target = "fullScopeAllowed", constant = "false")
    @Mapping(target = "attributes", expression = "java(clientAttributes(orgName))")
    ClientRepresentation toClient(String orgName, CreateServiceAccountRequest request);

    default Map<String, String> clientAttributes(String orgName) {
        return Map.of(
                OrgClients.ORG_ATTRIBUTE, orgName,
                "oauth2.device.authorization.grant.enabled", "false",
                "oidc.ciba.grant.enabled", "false");
    }

    // several sources, every shared property is named: the client and its user both have an "enabled" and a "name"
    @BeanMapping(ignoreByDefault = true)
    @Mapping(target = "name", source = "name")
    @Mapping(target = "clientId", source = "client.clientId")
    @Mapping(target = "userId", source = "user.id")
    @Mapping(target = "description", source = "client.description")
    @Mapping(target = "enabled", source = "user.enabled")
    @Mapping(target = "defaultCatalogs", source = "defaultCatalogs")
    ServiceAccount toServiceAccount(String name, ClientRepresentation client, UserRepresentation user,
                                    List<String> defaultCatalogs);
}
