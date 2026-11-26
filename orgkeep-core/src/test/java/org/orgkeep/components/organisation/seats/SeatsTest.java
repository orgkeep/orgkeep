package org.orgkeep.components.organisation.seats;

import org.orgkeep.api.OrgService;
import org.orgkeep.api.exceptions.OrgNotFoundException;
import org.orgkeep.api.model.CreateOrgRequest;
import org.orgkeep.api.model.UpdateSeatsRequest;
import org.orgkeep.fixtures.TestRealm;
import org.orgkeep.web.CurrentRealm;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mockito.Mockito;

import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

/**
 * HTTP-level tests of the seats endpoints: PUT /admin/organisations/{org}/seats (realm owner) and
 * GET /organisations/{org}/seats (org admins and the realm owner). The business logic of SeatGuard (counting,
 * the 409 of an add or a service account creation once full) belongs to the slices that wire it into
 * OrgMemberServiceImpl and ServiceAccountServiceImpl.
 * <p>
 * Same fixtures as OrganisationsResourceTest: one realm for the whole class, the org deleted after each test.
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SeatsTest {
    private static final String ORG = "it-seats-org";
    private static final String OTHER_ORG = "it-seats-other-org";
    // quarkus.rest.path=/api: RestAssured only knows the root path of the HTTP server, the prefix is in the URL
    private static final String ADMIN_BASE = "/api/admin/organisations";
    private static final String ORG_BASE = "/api/organisations";

    @Inject OrgService orgService;
    @InjectMock CurrentRealm currentRealm;

    private TestRealm testRealm;

    @BeforeAll
    void createRealm() {
        testRealm = TestRealm.create();
    }

    @BeforeEach
    void stubCurrentRealm() {
        Mockito.when(currentRealm.name()).thenReturn(testRealm.name());
    }

    @AfterEach
    void cleanUp() {
        for (String org : Set.of(ORG, OTHER_ORG)) {
            try {
                orgService.delete(testRealm.name(), org);
            } catch (OrgNotFoundException ignored) {
                // not created by this test
            }
        }
    }

    @AfterAll
    void deleteRealm() {
        testRealm.close();
    }

    @Test
    @TestSecurity(user = "owner", roles = "realm-owner")
    void putByRealmOwnerReturns200WithNewLimits() {
        create();

        given().contentType(ContentType.JSON).body(new UpdateSeatsRequest(20, 5))
                .when().put(ADMIN_BASE + "/{org}/seats", ORG)
                .then().statusCode(200)
                .body("members.max", equalTo(20))
                .body("serviceAccounts.max", equalTo(5));

        given().when().get(ADMIN_BASE + "/{org}", ORG)
                .then().statusCode(200)
                .body("maxMembers", equalTo(20))
                .body("maxServiceAccounts", equalTo(5));
        given().when().get(ADMIN_BASE)
                .then().statusCode(200)
                .body("items.find { it.name == '" + ORG + "' }.maxMembers", equalTo(20));
    }

    @Test
    @TestSecurity(user = "owner", roles = "realm-owner")
    void nullRemovesALimit() {
        create();
        given().contentType(ContentType.JSON).body(new UpdateSeatsRequest(20, 5))
                .when().put(ADMIN_BASE + "/{org}/seats", ORG)
                .then().statusCode(200);

        given().contentType(ContentType.JSON).body(new UpdateSeatsRequest(null, 5))
                .when().put(ADMIN_BASE + "/{org}/seats", ORG)
                .then().statusCode(200)
                .body("members.max", nullValue())
                .body("serviceAccounts.max", equalTo(5));
        given().when().get(ADMIN_BASE + "/{org}", ORG)
                .then().statusCode(200)
                .body("maxMembers", nullValue());
    }

    @Test
    @TestSecurity(user = "owner", roles = "realm-owner")
    void putWithNegativeValueReturns400() {
        create();

        given().contentType(ContentType.JSON).body(new UpdateSeatsRequest(-1, null))
                .when().put(ADMIN_BASE + "/{org}/seats", ORG)
                .then().statusCode(400);
    }

    @Test
    @TestSecurity(user = "owner", roles = "realm-owner")
    void putOnUnknownOrgReturns404() {
        given().contentType(ContentType.JSON).body(new UpdateSeatsRequest(10, null))
                .when().put(ADMIN_BASE + "/{org}/seats", "unknown-org")
                .then().statusCode(404);
    }

    @Test
    @TestSecurity(user = "alice", roles = "org-" + ORG + ":org-admin")
    void putByOrgAdminReturns403() {
        given().contentType(ContentType.JSON).body(new UpdateSeatsRequest(10, null))
                .when().put(ADMIN_BASE + "/{org}/seats", ORG)
                .then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "bob", roles = "org-" + ORG + ":org-owner")
    void putByOrgOwnerReturns403() {
        given().contentType(ContentType.JSON).body(new UpdateSeatsRequest(10, null))
                .when().put(ADMIN_BASE + "/{org}/seats", ORG)
                .then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "carol", roles = "org-" + ORG + ":org-user")
    void getByOrgUserReturns403() {
        given().when().get(ORG_BASE + "/{org}/seats", ORG)
                .then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "mallory", roles = "org-" + OTHER_ORG + ":org-admin")
    void getByAdminOfAnotherOrgReturns403() {
        given().when().get(ORG_BASE + "/{org}/seats", ORG)
                .then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "owner", roles = "realm-owner")
    void getByRealmOwnerReturns200() {
        create();

        given().when().get(ORG_BASE + "/{org}/seats", ORG)
                .then().statusCode(200)
                .body("members.max", nullValue());
    }

    @Test
    @TestSecurity(user = "owner", roles = "realm-owner")
    void createWithSeatLimitsSetsTheAttributes() {
        given().contentType(ContentType.JSON)
                .body(new CreateOrgRequest(ORG, Set.of(), null, null, null, null, 3, 1))
                .when().post(ADMIN_BASE)
                .then().statusCode(201);

        given().when().get(ADMIN_BASE + "/{org}", ORG)
                .then().statusCode(200)
                .body("maxMembers", equalTo(3))
                .body("maxServiceAccounts", equalTo(1));
    }

    @Test
    @TestSecurity(user = "owner", roles = "realm-owner")
    void createWithAnOwnerAndNoSeatReturns400WithNothingCreated() {
        given().contentType(ContentType.JSON)
                .body(new CreateOrgRequest(ORG, Set.of(), "owner@it-seats-org.test", null, "Olivia", "Owner", 0, null))
                .when().post(ADMIN_BASE)
                .then().statusCode(400);

        given().when().get(ADMIN_BASE + "/{org}", ORG)
                .then().statusCode(404);
    }

    @Test
    @TestSecurity(user = "owner", roles = "realm-owner")
    void createWithAnOwnerAndOneSeatReturns201() {
        given().contentType(ContentType.JSON)
                .body(new CreateOrgRequest(ORG, Set.of(), "owner@it-seats-org.test", null, "Olivia", "Owner", 1, null))
                .when().post(ADMIN_BASE)
                .then().statusCode(201);

        given().when().get(ORG_BASE + "/{org}/seats", ORG)
                .then().statusCode(200)
                .body("members.used", equalTo(1))
                .body("members.max", equalTo(1));
    }

    @Test
    @TestSecurity(user = "owner", roles = "realm-owner")
    void createWithoutAnOwnerAndNoSeatReturns201() {
        given().contentType(ContentType.JSON)
                .body(new CreateOrgRequest(ORG, Set.of(), null, null, null, null, 0, null))
                .when().post(ADMIN_BASE)
                .then().statusCode(201);
    }

    @Test
    @TestSecurity(user = "owner", roles = "realm-owner")
    void createWithNegativeMaxMembersReturns400() {
        given().contentType(ContentType.JSON)
                .body(new CreateOrgRequest(ORG, Set.of(), null, null, null, null, -1, null))
                .when().post(ADMIN_BASE)
                .then().statusCode(400);
    }

    private void create() {
        given().contentType(ContentType.JSON).body(new CreateOrgRequest(ORG, Set.of(), null, null))
                .when().post(ADMIN_BASE)
                .then().statusCode(201);
    }
}
