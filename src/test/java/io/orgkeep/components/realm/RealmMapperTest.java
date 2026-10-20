package io.orgkeep.components.realm;

import io.orgkeep.api.model.CreateRealmRequest;
import org.junit.jupiter.api.Test;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.UserRepresentation;

import static org.junit.jupiter.api.Assertions.*;

class RealmMapperTest {
    private final RealmMapper mapper = new RealmMapperImpl();

    @Test
    void toRealmRepresentation_mapsNameAndConstants() {
        CreateRealmRequest request = new CreateRealmRequest("acme","admin@acme.com","s3cret");
        RealmRepresentation realm = mapper.toRealmRepresentation(request);

        assertEquals("acme", realm.getRealm());
        assertFalse(realm.isDuplicateEmailsAllowed());
        assertTrue(realm.isLoginWithEmailAllowed());
    }

    @Test
    void toUserRepresentation_setsUsernameEmailAndCredentials() {
        CreateRealmRequest request = new CreateRealmRequest("acme","admin@acme.com","s3cret");
        UserRepresentation user = mapper.toUserRepresentation(request, "s3cret");

        assertEquals("admin@acme.com", user.getUsername());
        assertEquals("admin@acme.com", user.getEmail());

        assertNotNull(user.getCredentials());
        assertEquals(1, user.getCredentials().size());
        CredentialRepresentation cred = user.getCredentials().get(0);
        assertEquals(CredentialRepresentation.PASSWORD, cred.getType());
        assertEquals("s3cret", cred.getValue());
        assertTrue(cred.isTemporary());
    }

    @Test
    void toUserRepresentation_usesExplicitUsernameAndFinalPassword() {
        CreateRealmRequest request = new CreateRealmRequest("acme", "admin@acme.com", "s3cret", "realm-owner", false,
                "Alice", "Martin");
        UserRepresentation user = mapper.toUserRepresentation(request, "s3cret");

        assertEquals("realm-owner", user.getUsername());
        assertEquals("admin@acme.com", user.getEmail());
        assertEquals("Alice", user.getFirstName());
        assertEquals("Martin", user.getLastName());
        assertFalse(user.getCredentials().get(0).isTemporary());
    }
}
