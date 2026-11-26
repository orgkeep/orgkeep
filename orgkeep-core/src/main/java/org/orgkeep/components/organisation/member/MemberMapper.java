package org.orgkeep.components.organisation.member;

import org.orgkeep.api.model.AddMemberRequest;
import org.orgkeep.api.model.OrgMember;
import org.orgkeep.api.model.OrgRole;
import org.orgkeep.components.organisation.member.invitation.InvitationMailer;
import org.orgkeep.components.organisation.suspension.SuspensionAttributes;
import org.keycloak.representations.idm.UserRepresentation;
import org.mapstruct.AfterMapping;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.ReportingPolicy;

import java.util.List;

@Mapper(componentModel = "cdi", unmappedTargetPolicy = ReportingPolicy.WARN, imports = SuspensionAttributes.class)
public interface MemberMapper {

    /**
     * A member added by an org admin: no credentials yet, created with emailVerified=false and the required
     * action UPDATE_PASSWORD (added below), which lets the invitation emailed by Keycloak show as pending until a
     * password is set. The email becomes verified when the invitation link is followed, since the link itself
     * carries VERIFY_EMAIL (see InvitationMailer.send): the user's own required actions never carry it.
     */
    // UserRepresentation has dozens of properties: only map the listed ones instead of warning on each
    @BeanMapping(ignoreByDefault = true)
    @Mapping(target = "username", source = "email")
    @Mapping(target = "email", source = "email")
    @Mapping(target = "firstName", source = "firstName")
    @Mapping(target = "lastName", source = "lastName")
    @Mapping(target = "emailVerified", constant = "false")
    @Mapping(target = "enabled", constant = "true")
    UserRepresentation toUserRepresentation(AddMemberRequest request);

    @AfterMapping
    default void addRequiredAction(@MappingTarget UserRepresentation user) {
        user.setRequiredActions(List.of(InvitationMailer.UPDATE_PASSWORD));
    }

    // several sources: the user properties come from user, the other parameters fill the properties of the same name
    @Mapping(target = "suspension", expression = "java(SuspensionAttributes.toSuspension(user))")
    OrgMember toOrgMember(UserRepresentation user, OrgRole role, Boolean invitationPending);

    /** Without the invitation status: for the owners of the organisation lists and details, where it is not shown. */
    @Mapping(target = "invitationPending", ignore = true)
    @Mapping(target = "suspension", expression = "java(SuspensionAttributes.toSuspension(user))")
    OrgMember toOrgMember(UserRepresentation user, OrgRole role);
}
