package io.orgkeep.repository.keycloak;

import io.orgkeep.api.exceptions.OrgNotFoundException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import lombok.RequiredArgsConstructor;
import org.keycloak.admin.client.CreatedResponseUtil;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.OrganizationResource;
import org.keycloak.representations.idm.MemberRepresentation;
import org.keycloak.representations.idm.OrganizationRepresentation;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Keycloak organisations and their membership.
 */
@ApplicationScoped
@RequiredArgsConstructor
public class KeycloakOrgs {
    private static final int PAGE_SIZE = 100;

    private final Keycloak keycloak;

    /** @return the id of the new organisation, empty if one with this name already exists */
    public Optional<String> create(String realm, OrganizationRepresentation org) {
        try(Response response = keycloak.realm(realm).organizations().create(org)){
            if(KeycloakErrors.isConflict(response)){
                return Optional.empty();
            }
            return Optional.of(CreatedResponseUtil.getCreatedId(response));
        }
    }

    public List<OrganizationRepresentation> search(String realm, String search, int first, int max) {
        return keycloak.realm(realm).organizations().search(search, false, first, max);
    }

    /** Names of all the organisations of the realm, read page by page. */
    public List<String> names(String realm) {
        List<String> names = new ArrayList<>();
        int first = 0;
        List<OrganizationRepresentation> page;
        do {
            page = keycloak.realm(realm).organizations().search(null, false, first, PAGE_SIZE);
            page.forEach(org -> names.add(org.getName()));
            first += PAGE_SIZE;
        } while (page.size() == PAGE_SIZE);
        return names;
    }

    /** Full representation of the organisation with this exact name. */
    public Optional<OrganizationRepresentation> findByName(String realm, String name) {
        // search is a "contains" on name and domains: keep the exact name only
        return keycloak.realm(realm).organizations().search(name, true, 0, 10).stream()
                .filter(org -> name.equals(org.getName()))
                .findFirst()
                .map(org -> organisation(realm, org.getId()).toRepresentation());
    }

    /** @throws OrgNotFoundException if no organisation has this name */
    public OrganizationRepresentation getByName(String realm, String name) {
        return findByName(realm, name).orElseThrow(() -> new OrgNotFoundException(realm, name));
    }

    public void delete(String realm, String orgId) {
        try(Response response = organisation(realm, orgId).delete()){
            KeycloakErrors.requireSuccess(response, "Deleting organisation " + orgId);
        }
    }

    public List<MemberRepresentation> members(String realm, String orgId) {
        return organisation(realm, orgId).members().getAll();
    }

    /** Counted by Keycloak: the members are not loaded. */
    public long memberCount(String realm, String orgId) {
        return organisation(realm, orgId).members().count();
    }

    public boolean isMember(String realm, String orgId, String userId) {
        try {
            organisation(realm, orgId).members().member(userId).toRepresentation();
            return true;
        } catch (WebApplicationException e) {
            if (!KeycloakErrors.isNotFound(e)) {
                throw e;
            }
            return false;
        }
    }

    /** Names of the organisations the user is a member of. */
    public List<String> organisationsOf(String realm, String userId) {
        return keycloak.realm(realm).organizations().members().getOrganizations(userId).stream()
                .map(OrganizationRepresentation::getName)
                .toList();
    }

    /** @return false if the user is already a member */
    public boolean addMember(String realm, String orgId, String userId) {
        try(Response response = organisation(realm, orgId).members().addMember(userId)){
            if(KeycloakErrors.isConflict(response)){
                return false;
            }
            KeycloakErrors.requireSuccess(response, "Adding user %s to organisation %s".formatted(userId, orgId));
            return true;
        }
    }

    public void removeMember(String realm, String orgId, String userId) {
        try(Response response = organisation(realm, orgId).members().member(userId).delete()){
            KeycloakErrors.requireSuccess(response, "Removing user %s from organisation %s".formatted(userId, orgId));
        }
    }

    private OrganizationResource organisation(String realm, String orgId) {
        return keycloak.realm(realm).organizations().get(orgId);
    }
}
