package org.orgkeep.repository.keycloak;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;
import org.orgkeep.fixtures.TestRealm;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What Keycloak does when a sub group is created under a name already taken, against the Keycloak of Dev Services.
 * The idempotent creation of the groups of an assignment relies on it.
 */
@QuarkusTest
class KeycloakGroupsTest {
    @Inject Keycloak keycloak;
    @Inject KeycloakGroups groups;

    private TestRealm testRealm;
    private String parentId;

    @BeforeEach
    void beforeEach() {
        testRealm = TestRealm.create(keycloak);
        parentId = groups.createTopLevel(testRealm.name(), "parent");
    }

    @AfterEach
    void afterEach() {
        testRealm.close();
    }

    // Pins the current behaviour: createChild goes through CreatedResponseUtil, which throws on Keycloak's 409
    @Test
    void createChildOnAnExistingNameThrowsWithTheConflictStatus() {
        groups.createChild(testRealm.name(), parentId, "child");

        WebApplicationException e = assertThrows(WebApplicationException.class,
                () -> groups.createChild(testRealm.name(), parentId, "child"));

        assertEquals(409, e.getResponse().getStatus());
    }

    @Test
    void createChildIfAbsentCreatesThenAnswersEmptyOnAnExistingName() {
        var created = groups.createChildIfAbsent(testRealm.name(), parentId, "child", Map.of());
        var again = groups.createChildIfAbsent(testRealm.name(), parentId, "child", Map.of());

        assertTrue(created.isPresent());
        assertTrue(again.isEmpty());
        assertEquals(created, groups.findId(testRealm.name(), "/parent/child"));
    }
}
