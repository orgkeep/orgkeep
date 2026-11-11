package org.orgkeep.components.organisation;

import org.orgkeep.api.model.CreateOrgRequest;
import org.junit.jupiter.api.Test;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.OrganizationDomainRepresentation;
import org.keycloak.representations.idm.OrganizationRepresentation;
import org.keycloak.representations.idm.UserRepresentation;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class OrgMapperTest {
    OrgMapper mapper = new OrgMapperImpl();

    @Test
    void toOrgRepresentation() {
        CreateOrgRequest request = new CreateOrgRequest("acme", Set.of("acme.com", "acme.io"), null, null);

        OrganizationRepresentation org = mapper.toOrgRepresentation(request);

        assertEquals("acme", org.getName());
        assertTrue(org.isEnabled());
        assertEquals(Set.of("acme.com", "acme.io"),
                org.getDomains().stream()
                        .map(OrganizationDomainRepresentation::getName)
                        .collect(Collectors.toSet()));
    }

    @Test
    void toClientRepresentation() {
        CreateOrgRequest request = new CreateOrgRequest("acme", Set.of(), null, null);

        ClientRepresentation client = mapper.toClientRepresentation(request);

        assertEquals("org-acme", client.getClientId());
        assertEquals("acme", client.getName());
        assertTrue(client.isEnabled());
        assertFalse(client.isPublicClient());
        assertFalse(client.isStandardFlowEnabled());
        assertFalse(client.isDirectAccessGrantsEnabled());
        assertFalse(client.isServiceAccountsEnabled());
    }

    @Test
    void toOwnerRepresentationWithPassword() {
        CreateOrgRequest request = new CreateOrgRequest("acme", Set.of(), "owner@acme.com", "changeme");

        UserRepresentation owner = mapper.toOwnerRepresentation(request);

        assertEquals("owner@acme.com", owner.getUsername());
        assertEquals("owner@acme.com", owner.getEmail());
        assertEquals(1, owner.getCredentials().size());
        assertEquals("changeme", owner.getCredentials().get(0).getValue());
        assertTrue(owner.getCredentials().get(0).isTemporary());
        assertNull(owner.getFirstName());
    }

    @Test
    void toOwnerRepresentationWithName() {
        CreateOrgRequest request = new CreateOrgRequest("acme", Set.of(), "owner@acme.com", null, "Bob", "Durand");

        UserRepresentation owner = mapper.toOwnerRepresentation(request);

        assertEquals("Bob", owner.getFirstName());
        assertEquals("Durand", owner.getLastName());
    }

    @Test
    void toOwnerRepresentationWithoutPassword() {
        CreateOrgRequest request = new CreateOrgRequest("acme", Set.of(), "owner@acme.com", null);

        assertNull(mapper.toOwnerRepresentation(request).getCredentials());
    }
}
