package io.orgkeep.components.organisation.usergroup;

import io.orgkeep.api.model.OrgGroups;
import io.orgkeep.api.model.TeamCatalogRole;
import io.orgkeep.api.model.TeamProduct;
import io.orgkeep.api.model.UserGroup;
import org.keycloak.representations.idm.GroupRepresentation;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

import java.util.List;
import java.util.Set;

// imports: the classes the expressions below may use without their package
@Mapper(componentModel = "cdi", unmappedTargetPolicy = ReportingPolicy.WARN, imports = OrgGroups.class)
public interface UserGroupMapper {

    // several sources: id and name come from group, the other parameters fill the properties of the same name
    @Mapping(target = "predefined", expression = "java(OrgGroups.isPredefined(group.getName()))")
    UserGroup toUserGroup(GroupRepresentation group, Set<String> memberIds, List<TeamProduct> products,
                          List<TeamCatalogRole> catalogRoles);
}
