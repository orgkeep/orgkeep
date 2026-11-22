package org.orgkeep.components.catalog.role;

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
import org.orgkeep.api.CatalogRoleService;
import org.orgkeep.api.CatalogService;
import org.orgkeep.api.OrgService;
import org.orgkeep.api.ServiceAccountService;
import org.orgkeep.api.model.CreateCatalogRequest;
import org.orgkeep.api.model.CreateCatalogRoleRequest;
import org.orgkeep.api.model.CreateOrgRequest;
import org.orgkeep.api.model.CreateServiceAccountRequest;
import org.orgkeep.fixtures.RequestScope;
import org.orgkeep.fixtures.TestRealm;
import org.orgkeep.web.CurrentRealm;

import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;

/**
 * The reserved catalog roles given directly, over REST: realm owner only, and to a service account too.
 * <p>
 * One realm and one fixture for the whole class (@BeforeAll): no test here destroys the catalog, the org, the
 * role or the service account, so it does not need rebuilding between tests.
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CatalogRolesResourceTest {
    private static final String HOLDERS = "/api/admin/catalogs/prod/roles/anonymous/users";
    private static final String ONE = HOLDERS + "/{userId}";

    @Inject OrgService orgService;
    @Inject CatalogService catalogService;
    @Inject CatalogRoleService catalogRoleService;
    @Inject ServiceAccountService serviceAccountService;
    @InjectMock CurrentRealm currentRealm;

    private TestRealm testRealm;
    private String batchUserId;

    @BeforeAll
    void createRealm() {
        testRealm = TestRealm.create();
        RequestScope.run(() -> {
            catalogService.create(testRealm.name(), new CreateCatalogRequest("prod", "Production", null));
            catalogRoleService.create(testRealm.name(), "prod", new CreateCatalogRoleRequest("anonymous", null, null, false));
            orgService.create(testRealm.name(), new CreateOrgRequest("acme", Set.of(), null, null));
            batchUserId = serviceAccountService.create(testRealm.name(), "acme",
                    new CreateServiceAccountRequest("batch", null, null)).serviceAccount().userId();
        });
    }

    @BeforeEach
    void stubCurrentRealm() {
        Mockito.when(currentRealm.name()).thenReturn(testRealm.name());
    }

    // userOfAConsoleClientGets409 creates it directly
    @AfterEach
    void removeConsoleClient() {
        testRealm.resource().clients().findByClientId("console-client")
                .forEach(client -> testRealm.resource().clients().get(client.getId()).remove());
    }

    @AfterAll
    void deleteRealm() {
        testRealm.close();
    }

    @Test
    @TestSecurity(user = "bob", roles = "org-acme:org-admin")
    void orgAdminGets403() {
        given().when().put(ONE, batchUserId).then().statusCode(403);
        given().when().get(HOLDERS).then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "alice", roles = "realm-owner")
    void realmOwnerGrantsToAServiceAccountAndSeesItAsAHolder() {
        given().when().put(ONE, batchUserId).then().statusCode(204);

        given().when().get(HOLDERS).then().statusCode(200)
                .body("userId", contains(batchUserId))
                .body("serviceAccount", contains("org-acme.batch"))
                .body("org", contains("acme"))
                .body("[0].email", nullValue());
    }

    // the user of a client of the console, put in an org by hand, is neither a person nor a service account of Orgkeep
    @Test
    @TestSecurity(user = "alice", roles = "realm-owner")
    void userOfAConsoleClientGets409() {
        String uuid = testRealm.createClient("console-client");
        var rep = testRealm.resource().clients().get(uuid).toRepresentation();
        rep.setServiceAccountsEnabled(true);
        testRealm.resource().clients().get(uuid).update(rep);
        String userId = testRealm.resource().clients().get(uuid).getServiceAccountUser().getId();
        String orgId = testRealm.resource().organizations().search("acme", true, 0, 1).getFirst().getId();
        testRealm.resource().organizations().get(orgId).members().addMember(userId).close();

        given().when().put(ONE, userId).then().statusCode(409);
    }
}
