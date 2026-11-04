package org.orgkeep.components.organisation;

import org.orgkeep.api.model.CreateOrgRequest;
import org.orgkeep.api.model.OrgClients;
import org.orgkeep.api.model.OrgMember;
import org.orgkeep.api.model.OrgSummary;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.OrganizationDomainRepresentation;
import org.keycloak.representations.idm.OrganizationRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.mapstruct.*;

import java.util.List;
import java.util.Set;

@Mapper(componentModel = "cdi", unmappedTargetPolicy = ReportingPolicy.WARN)
public interface OrgMapper {

    // domains are not mapped by MapStruct, which would silently skip them (getter + addAll on a null collection):
    // they are set explicitly in mapDomains below
    @BeanMapping(ignoreByDefault = true)
    @Mapping(target = "name", source = "name")
    @Mapping(target = "enabled", constant = "true")
    OrganizationRepresentation toOrgRepresentation(CreateOrgRequest request);

    // several sources: id, name and enabled come from org, the other parameters fill the properties of the same name
    @Mapping(target = "emailDomains", source = "org.domains")
    OrgSummary toOrgSummary(OrganizationRepresentation org, List<OrgMember> owners, long memberCount,
                            List<String> products);

    // an org without domain has null domains in Keycloak: the API always answers a set, empty here
    @IterableMapping(nullValueMappingStrategy = NullValueMappingStrategy.RETURN_DEFAULT)
    Set<String> toDomainNames(Set<OrganizationDomainRepresentation> domains);

    // used by MapStruct for each element of toDomainNames
    default String toDomainName(OrganizationDomainRepresentation domain) {
        return domain.getName();
    }

    /**
     * The org client is only a container for the org roles: no login flow is enabled on it.
     */
    // ClientRepresentation has dozens of properties: only map the listed ones instead of warning on each
    @BeanMapping(ignoreByDefault = true)
    @Mapping(target = "clientId", source = "name", qualifiedByName = "orgClientId")
    @Mapping(target = "name", source = "name")
    @Mapping(target = "enabled", constant = "true")
    @Mapping(target = "publicClient", constant = "false")
    @Mapping(target = "standardFlowEnabled", constant = "false")
    @Mapping(target = "directAccessGrantsEnabled", constant = "false")
    @Mapping(target = "serviceAccountsEnabled", constant = "false")
    ClientRepresentation toClientRepresentation(CreateOrgRequest request);

    @BeanMapping(ignoreByDefault = true)
    @Mapping(target = "username", source = "ownerEmail")
    @Mapping(target = "email", source = "ownerEmail")
    @Mapping(target = "firstName", source = "ownerFirstName")
    @Mapping(target = "lastName", source = "ownerLastName")
    @Mapping(target = "emailVerified", constant = "true")
    @Mapping(target = "enabled", constant = "true")
    UserRepresentation toOwnerRepresentation(CreateOrgRequest request);

    @AfterMapping
    default void mapDomains(CreateOrgRequest request, @MappingTarget OrganizationRepresentation org) {
        if (request.emailDomains() == null) {
            return;
        }

        request.emailDomains().forEach(email -> org.addDomain(toDomain(email)));
    }

    @AfterMapping
    default void addOwnerCredentials(CreateOrgRequest request, @MappingTarget UserRepresentation owner) {
        if (request.ownerPassword() == null) {
            return;
        }

        CredentialRepresentation credentials = new CredentialRepresentation();
        credentials.setType(CredentialRepresentation.PASSWORD);
        credentials.setValue(request.ownerPassword());
        credentials.setTemporary(true);

        owner.setCredentials(List.of(credentials));
    }

    // @Named keeps MapStruct from applying this String -> String method to every other String property
    @Named("orgClientId")
    default String orgClientId(String orgName) {
        return OrgClients.clientId(orgName);
    }

    default OrganizationDomainRepresentation toDomain(String name) {
        OrganizationDomainRepresentation domain = new OrganizationDomainRepresentation();
        domain.setName(name);
        return domain;
    }
}
