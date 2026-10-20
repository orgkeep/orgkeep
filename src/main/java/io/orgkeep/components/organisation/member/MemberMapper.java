package io.orgkeep.components.organisation.member;

import io.orgkeep.api.model.AddMemberRequest;
import io.orgkeep.api.model.OrgMember;
import io.orgkeep.api.model.OrgRole;
import org.keycloak.representations.idm.UserRepresentation;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "cdi", unmappedTargetPolicy = ReportingPolicy.WARN)
public interface MemberMapper {

    /** A member added by an org admin: no credentials yet, the email is not verified. */
    // UserRepresentation has dozens of properties: only map the listed ones instead of warning on each
    @BeanMapping(ignoreByDefault = true)
    @Mapping(target = "username", source = "email")
    @Mapping(target = "email", source = "email")
    @Mapping(target = "firstName", source = "firstName")
    @Mapping(target = "lastName", source = "lastName")
    @Mapping(target = "emailVerified", constant = "false")
    @Mapping(target = "enabled", constant = "true")
    UserRepresentation toUserRepresentation(AddMemberRequest request);

    // several sources: the user properties come from user, the role parameter fills the property of the same name
    OrgMember toOrgMember(UserRepresentation user, OrgRole role);
}
