package io.orgkeep.components.catalog.role;

import io.orgkeep.api.model.CatalogRole;
import io.orgkeep.api.model.CatalogRoles;
import io.orgkeep.api.model.RoleHolder;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.orgkeep.api.model.CatalogRoles.firstValue;

@Mapper(componentModel = "cdi", unmappedTargetPolicy = ReportingPolicy.WARN)
public interface CatalogRoleMapper {

    /**
     * @param description null removes the current one: Keycloak ignores a null field on update, hence an empty string
     */
    default RoleRepresentation toRoleRepresentation(String name, String displayName, String description, boolean orgGrantable) {
        Map<String, List<String>> attributes = new HashMap<>();
        attributes.put(CatalogRoles.TYPE, List.of(CatalogRoles.TYPE_CATALOG_ROLE));
        attributes.put(CatalogRoles.ORG_GRANTABLE, List.of(String.valueOf(orgGrantable)));
        if (displayName != null) {
            attributes.put(CatalogRoles.DISPLAY_NAME, List.of(displayName));
        }
        RoleRepresentation role = new RoleRepresentation(name, description == null ? "" : description, false);
        role.setAttributes(attributes);
        return role;
    }

    default boolean isCatalogRole(RoleRepresentation role) {
        return CatalogRoles.TYPE_CATALOG_ROLE.equals(CatalogRoles.typeOf(role.getAttributes()));
    }

    default CatalogRole toCatalogRole(String catalog, RoleRepresentation role) {
        String description = role.getDescription();
        return new CatalogRole(catalog, role.getName(),
                firstValue(role.getAttributes(), CatalogRoles.DISPLAY_NAME),
                description == null || description.isBlank() ? null : description,
                Boolean.parseBoolean(firstValue(role.getAttributes(), CatalogRoles.ORG_GRANTABLE)));
    }

    @Mapping(target = "userId", source = "id")
    RoleHolder toRoleHolder(UserRepresentation user);
}
