package org.orgkeep.components.organisation;

import org.orgkeep.api.OrgService;
import org.orgkeep.api.model.CreateOrgRequest;
import org.orgkeep.fixtures.TestRealm;
import org.orgkeep.web.CurrentRealm;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mockito.Mockito;

import java.util.List;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;

/**
 * HTTP-level tests: routing, security and the respect of "max". The business logic (what matches the search) is the
 * same call as the organisation search, already covered for the list endpoint in OrganisationsResourceTest.
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrganisationNamesResourceTest {
    private static final String BASE = "/api/admin/organisation-names";
    private static final List<String> ORGS = List.of("acme-names", "other-names");

    @Inject OrgService orgService;
    @InjectMock CurrentRealm currentRealm;

    private TestRealm testRealm;

    @BeforeAll
    void createRealm() {
        testRealm = TestRealm.create();
        ORGS.forEach(org -> orgService.create(testRealm.name(), new CreateOrgRequest(org, Set.of(), null, null)));
    }

    @BeforeEach
    void stubCurrentRealm() {
        Mockito.when(currentRealm.name()).thenReturn(testRealm.name());
    }

    @AfterAll
    void deleteRealm() {
        testRealm.close();
    }

    @Test
    void anonymousGets401() {
        given().when().get(BASE)
                .then().statusCode(401);
    }

    @Test
    @TestSecurity(user = "alice", roles = "org-acme-names:org-admin")
    void orgAdminGets403() {
        given().when().get(BASE)
                .then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "owner", roles = "realm-owner")
    void listMatchesTheSearch() {
        given().when().get(BASE + "?search=other")
                .then().statusCode(200)
                .body("$", contains("other-names"));
    }

    @Test
    @TestSecurity(user = "owner", roles = "realm-owner")
    void listRespectsMax() {
        given().when().get(BASE + "?max=1")
                .then().statusCode(200)
                .body("$", hasSize(1));
    }
}
