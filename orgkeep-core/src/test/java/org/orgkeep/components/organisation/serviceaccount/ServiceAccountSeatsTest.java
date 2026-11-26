package org.orgkeep.components.organisation.serviceaccount;

import org.orgkeep.api.OrgMemberService;
import org.orgkeep.api.OrgService;
import org.orgkeep.api.ServiceAccountService;
import org.orgkeep.api.exceptions.OrgNotFoundException;
import org.orgkeep.api.model.AddMemberRequest;
import org.orgkeep.api.model.CreateOrgRequest;
import org.orgkeep.api.model.CreateServiceAccountRequest;
import org.orgkeep.api.model.OrgClients;
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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Seat limits enforced by ServiceAccountServiceImpl.create: the checks added by #39 on top of
 * ServiceAccountServiceTest's existing coverage. Members and service accounts share nothing: one count never
 * takes the other's slot (see SeatGuard, docs/design/service-accounts.md).
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ServiceAccountSeatsTest {
    private static final String ORG = "it-sa-seats-org";
    private static final String BASE = "/api/organisations/{org}/service-accounts";
    private static final String SEATS_BASE = "/api/organisations/{org}/seats";

    @Inject OrgService orgService;
    @Inject ServiceAccountService serviceAccountService;
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
        try {
            orgService.delete(testRealm.name(), ORG);
        } catch (OrgNotFoundException ignored) {
            // not created by this test
        }
        testRealm.resource().users().list(0, 1000)
                .forEach(user -> testRealm.resource().users().get(user.getId()).remove());
    }

    @AfterAll
    void deleteRealm() {
        testRealm.close();
    }

    /** An org with the given service-account limit, no owner (it would take a member seat, irrelevant here). */
    private void createOrg(Integer maxServiceAccounts) {
        orgService.create(testRealm.name(),
                new CreateOrgRequest(ORG, Set.of(), null, null, null, null, null, maxServiceAccounts));
    }

    private boolean clientExists(String name) {
        return !testRealm.resource().clients()
                .findByClientId(OrgClients.serviceAccountClientId(ORG, name))
                .isEmpty();
    }

    @Test
    @TestSecurity(user = "alice", roles = "org-" + ORG + ":org-admin")
    void secondServiceAccountOnALimitOfOneReturns409AndCreatesNothing() {
        createOrg(1);
        serviceAccountService.create(testRealm.name(), ORG, new CreateServiceAccountRequest("bot-one", null, null));

        given().contentType(ContentType.JSON)
                .body(new CreateServiceAccountRequest("bot-two", null, null))
                .when().post(BASE, ORG)
                .then().statusCode(409)
                .body("message", containsString("no service account left"))
                .body("message", containsString(ORG));

        assertFalse(clientExists("bot-two"));
    }

    @Test
    @TestSecurity(user = "alice", roles = "org-" + ORG + ":org-admin")
    void deletingTheFirstServiceAccountFreesTheSlot() {
        createOrg(1);
        serviceAccountService.create(testRealm.name(), ORG, new CreateServiceAccountRequest("bot-one", null, null));

        given().contentType(ContentType.JSON)
                .body(new CreateServiceAccountRequest("bot-two", null, null))
                .when().post(BASE, ORG)
                .then().statusCode(409);

        serviceAccountService.delete(testRealm.name(), ORG, "bot-one");

        given().contentType(ContentType.JSON)
                .body(new CreateServiceAccountRequest("bot-two", null, null))
                .when().post(BASE, ORG)
                .then().statusCode(201);
    }

    @Test
    @TestSecurity(user = "alice", roles = "org-" + ORG + ":org-admin")
    void duplicateNameOnAFullOrgReturnsAlreadyExists() {
        createOrg(1);
        serviceAccountService.create(testRealm.name(), ORG, new CreateServiceAccountRequest("bot-one", null, null));

        given().contentType(ContentType.JSON)
                .body(new CreateServiceAccountRequest("bot-one", null, null))
                .when().post(BASE, ORG)
                .then().statusCode(409)
                .body("message", containsString("already exists"));
    }

    @Test
    @TestSecurity(user = "alice", roles = "org-" + ORG + ":org-admin")
    void disabledOrgBeatsTheSeatLimit() {
        createOrg(1);
        serviceAccountService.create(testRealm.name(), ORG, new CreateServiceAccountRequest("bot-one", null, null));
        orgService.setEnabled(testRealm.name(), ORG, false);

        given().contentType(ContentType.JSON)
                .body(new CreateServiceAccountRequest("bot-two", null, null))
                .when().post(BASE, ORG)
                .then().statusCode(409)
                .body("message", containsString("disabled"));
    }

    /** A service-account limit does not block a member add: the two counts are separate (SeatGuard). */
    @Test
    @TestSecurity(user = "alice", roles = "org-" + ORG + ":org-admin")
    void aFullServiceAccountLimitDoesNotBlockAMemberAdd() {
        createOrg(1);
        serviceAccountService.create(testRealm.name(), ORG, new CreateServiceAccountRequest("bot-one", null, null));

        given().contentType(ContentType.JSON)
                .body(new AddMemberRequest("bob@it-sa-seats-org.test", "Bob", "Smith"))
                .when().post("/api/organisations/{org}/members", ORG)
                .then().statusCode(201);
    }

    @Test
    @TestSecurity(user = "owner", roles = "realm-owner")
    void realmOwnerIsAlsoRefusedOnAFullOrg() {
        createOrg(1);
        serviceAccountService.create(testRealm.name(), ORG, new CreateServiceAccountRequest("bot-one", null, null));

        given().contentType(ContentType.JSON)
                .body(new CreateServiceAccountRequest("bot-two", null, null))
                .when().post(BASE, ORG)
                .then().statusCode(409)
                .body("message", containsString("no service account left"));
    }

    @Test
    @TestSecurity(user = "alice", roles = "org-" + ORG + ":org-admin")
    void seatsEndpointShowsTheServiceAccountCount() {
        createOrg(2);
        serviceAccountService.create(testRealm.name(), ORG, new CreateServiceAccountRequest("bot-one", null, null));

        given().when().get(SEATS_BASE, ORG)
                .then().statusCode(200)
                .body("serviceAccounts.used", equalTo(1))
                .body("serviceAccounts.max", equalTo(2));
    }
}
