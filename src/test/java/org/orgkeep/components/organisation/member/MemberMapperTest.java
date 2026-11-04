package org.orgkeep.components.organisation.member;

import org.orgkeep.api.model.AddMemberRequest;
import org.orgkeep.api.model.OrgMember;
import org.orgkeep.api.model.OrgRole;
import org.junit.jupiter.api.Test;
import org.keycloak.representations.idm.UserRepresentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemberMapperTest {
    MemberMapper mapper = new MemberMapperImpl();

    @Test
    void toUserRepresentation() {
        UserRepresentation user = mapper.toUserRepresentation(new AddMemberRequest("bob@acme.com", "Bob", "Smith"));

        assertEquals("bob@acme.com", user.getUsername());
        assertEquals("bob@acme.com", user.getEmail());
        assertEquals("Bob", user.getFirstName());
        assertEquals("Smith", user.getLastName());
        assertTrue(user.isEnabled());
        assertFalse(user.isEmailVerified());
        // invited members get no credentials for now
        assertNull(user.getCredentials());
    }

    @Test
    void toOrgMember() {
        UserRepresentation user = new UserRepresentation();
        user.setId("42");
        user.setUsername("bob@acme.com");
        user.setEmail("bob@acme.com");
        user.setEnabled(true);

        OrgMember member = mapper.toOrgMember(user, OrgRole.ORG_ADMIN);

        assertEquals("42", member.id());
        assertEquals("bob@acme.com", member.email());
        assertTrue(member.enabled());
        assertEquals(OrgRole.ORG_ADMIN, member.role());
    }
}
