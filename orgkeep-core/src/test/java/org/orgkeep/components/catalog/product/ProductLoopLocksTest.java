package org.orgkeep.components.catalog.product;

import org.orgkeep.api.CatalogService;
import org.orgkeep.api.OrgService;
import org.orgkeep.api.ProductService;
import org.orgkeep.api.TeamService;
import org.orgkeep.api.UserGroupService;
import org.orgkeep.api.exceptions.ResourceBusyException;
import org.orgkeep.api.model.AssignProductRequest;
import org.orgkeep.api.model.CreateCatalogRequest;
import org.orgkeep.api.model.CreateOrgRequest;
import org.orgkeep.api.model.CreateProductRequest;
import org.orgkeep.api.model.CreateUserGroupRequest;
import org.orgkeep.api.model.Feature;
import org.orgkeep.api.model.GrantProductRequest;
import org.orgkeep.api.model.ParamDefinition;
import org.orgkeep.api.model.ParamType;
import org.orgkeep.api.model.QuotaCycle;
import org.orgkeep.api.model.UpdateProductRequest;
import org.orgkeep.components.lock.HeldLocks;
import org.orgkeep.components.lock.LockMode;
import org.orgkeep.components.lock.LockRequest;
import org.orgkeep.components.lock.ResourceLocks;
import org.orgkeep.fixtures.TestRealm;
import org.orgkeep.web.CurrentRealm;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.idm.RoleRepresentation;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The loops of the realm owner over the organisations a product is assigned to (update, deleteFeature): a busy
 * organisation is skipped, the others are done, and the call answers 409 at the end. Real services against the
 * Keycloak of Dev Services, in a throwaway realm per test. The product "billing" (feature "x", no metered feature) is
 * assigned to the orgs "org-a", "org-b" and "org-c"; the test holds "org-b" from another thread.
 */
@QuarkusTest
class ProductLoopLocksTest {
    private static final String CATALOG = "prod";
    private static final String PRODUCT = "billing";
    private static final String TEAM = "ops";

    @Inject Keycloak keycloak;
    @Inject OrgService orgService;
    @Inject CatalogService catalogService;
    @Inject ProductService productService;
    @Inject TeamService teamService;
    @Inject UserGroupService userGroupService;
    @Inject ResourceLocks locks;
    @InjectMock CurrentRealm currentRealm;

    private TestRealm testRealm;

    @BeforeEach
    void beforeEach() {
        testRealm = TestRealm.create(keycloak);
        catalogService.create(realm(), new CreateCatalogRequest(CATALOG, "Production", null));
        productService.create(realm(), CATALOG, new CreateProductRequest(PRODUCT, "Billing", null,
                List.of(new Feature("x", null)), List.of()));
        Mockito.when(currentRealm.name()).thenReturn(realm());
        for (String org : List.of("org-a", "org-b", "org-c")) {
            orgService.create(realm(), new CreateOrgRequest(org, Set.of(), null, null));
            productService.assign(realm(), org, CATALOG, PRODUCT,
                    new AssignProductRequest(1000L, QuotaCycle.MONTHLY, List.of("x"), Map.of()));
            // a team per org gives the product: "org-a" lists the feature "x", the others give all they bought
            userGroupService.create(realm(), org, new CreateUserGroupRequest(TEAM));
            teamService.grantProduct(realm(), org, TEAM, CATALOG, PRODUCT, "org-a".equals(org)
                    ? new GrantProductRequest(List.of("x"), null, null, Map.of())
                    : GrantProductRequest.allFeatures());
        }
    }

    // REST: the loop answers 409 with its own message, and the same call finishes the job once the org is free
    @Test
    @TestSecurity(user = "alice", roles = "realm-owner")
    void updateAnswers409WithTheLoopMessage() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (LockHolder holder = new LockHolder(executor, LockRequest.org(realm(), "org-b", LockMode.EXCLUSIVE))) {
            given().contentType(ContentType.JSON).body("{\"displayName\": \"Billing\", \"params\": []}")
                    .when().put("/api/admin/catalogs/{catalog}/products/{product}", CATALOG, PRODUCT)
                    .then().statusCode(409)
                    .body("message", equalTo(ResourceBusyException.LOOP_MESSAGE));
        } finally {
            executor.shutdownNow();
        }
        given().contentType(ContentType.JSON).body("{\"displayName\": \"Billing\", \"params\": []}")
                .when().put("/api/admin/catalogs/{catalog}/products/{product}", CATALOG, PRODUCT)
                .then().statusCode(200);
    }

    @Test
    @TestSecurity(user = "alice", roles = "realm-owner")
    void deleteFeatureAnswers409WithTheLoopMessage() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (LockHolder holder = new LockHolder(executor, LockRequest.org(realm(), "org-b", LockMode.EXCLUSIVE))) {
            given().when().delete("/api/admin/catalogs/{catalog}/products/{product}/features/{feature}",
                            CATALOG, PRODUCT, "x")
                    .then().statusCode(409)
                    .body("message", equalTo(ResourceBusyException.LOOP_MESSAGE));
        } finally {
            executor.shutdownNow();
        }
        given().when().delete("/api/admin/catalogs/{catalog}/products/{product}/features/{feature}",
                        CATALOG, PRODUCT, "x")
                .then().statusCode(200);
    }

    @AfterEach
    void afterEach() {
        testRealm.close();
    }

    // The new parameter reaches the free organisations, the busy one is skipped and named nowhere in the answer;
    // the same call repeated once it is free finishes the job
    @Test
    void updateSkipsABusyOrganisationAndTheSameCallFinishesTheJob() throws Exception {
        UpdateProductRequest request = new UpdateProductRequest("Billing", null,
                List.of(new ParamDefinition("tier", ParamType.STRING, true, "basic", List.of())));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (LockHolder holder = new LockHolder(executor, LockRequest.org(realm(), "org-b", LockMode.EXCLUSIVE))) {
            ResourceBusyException busy = assertThrowsExactly(ResourceBusyException.class,
                    () -> productService.update(realm(), CATALOG, PRODUCT, request));
            assertEquals(ResourceBusyException.LOOP_MESSAGE, busy.getMessage());

            assertEquals(List.of("basic"), assignment("org-a").get("param.tier"));
            assertNull(assignment("org-b").get("param.tier"));
            assertEquals(List.of("basic"), assignment("org-c").get("param.tier"));
            // the teams carry a copy of the values of their org: only for the organisations that were done
            assertEquals(List.of("{\"tier\":\"basic\"}"), team("org-a").get("prod.billing.params"));
            assertNull(team("org-b").get("prod.billing.params"));
            assertEquals(List.of("{\"tier\":\"basic\"}"), team("org-c").get("prod.billing.params"));
        } finally {
            executor.shutdownNow();
        }

        productService.update(realm(), CATALOG, PRODUCT, request);
        assertEquals(List.of("basic"), assignment("org-b").get("param.tier"));
        assertEquals(List.of("{\"tier\":\"basic\"}"), team("org-b").get("prod.billing.params"));
    }

    // The feature goes from the free organisations first, and the role itself stays while one was skipped, so that
    // the same call can finish (once the role is gone the call would answer 404)
    @Test
    void deleteFeatureSkipsABusyOrganisationAndKeepsTheFeatureUntilTheCallIsRepeated() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (LockHolder holder = new LockHolder(executor, LockRequest.org(realm(), "org-b", LockMode.EXCLUSIVE))) {
            ResourceBusyException busy = assertThrowsExactly(ResourceBusyException.class,
                    () -> productService.deleteFeature(realm(), CATALOG, PRODUCT, "x"));
            assertEquals(ResourceBusyException.LOOP_MESSAGE, busy.getMessage());

            assertEquals(List.of(), bought("org-a"));
            assertEquals(List.of("x"), bought("org-b"));
            assertEquals(List.of(), bought("org-c"));
            // the teams of the processed organisations no longer give the feature, in their choices and in their
            // role mappings (so in their tokens) while the call is still pending; the skipped one is untouched
            assertNull(team("org-a").get("grant:prod:billing:features"));
            assertEquals(Set.of("billing"), roles("org-a"));
            assertEquals(Set.of("billing"), roles("org-c"));
            assertEquals(Set.of("billing", "billing:x"), roles("org-b"));
            assertNotNull(productService.get(realm(), CATALOG, PRODUCT).features().stream()
                    .filter(feature -> "x".equals(feature.name())).findFirst().orElse(null));
        } finally {
            executor.shutdownNow();
        }

        productService.deleteFeature(realm(), CATALOG, PRODUCT, "x");
        assertFalse(productService.get(realm(), CATALOG, PRODUCT).features().stream()
                .anyMatch(feature -> "x".equals(feature.name())));
        assertEquals(List.of(), bought("org-b"));
        assertEquals(Set.of("billing"), roles("org-b"));
    }

    private String realm() {
        return testRealm.name();
    }

    private Map<String, List<String>> assignment(String org) {
        return testRealm.resource().getGroupByPath("/" + org + "/catalogs/" + CATALOG + "/" + PRODUCT).getAttributes();
    }

    private Map<String, List<String>> team(String org) {
        Map<String, List<String>> attributes =
                testRealm.resource().getGroupByPath("/" + org + "/user-groups/" + TEAM).getAttributes();
        return attributes == null ? Map.of() : attributes;
    }

    /** The roles of the catalog client mapped on the team of the org. */
    private Set<String> roles(String org) {
        String teamId = testRealm.resource().getGroupByPath("/" + org + "/user-groups/" + TEAM).getId();
        String clientUuid = testRealm.resource().clients().findByClientId(CATALOG).get(0).getId();
        return testRealm.resource().groups().group(teamId).roles().clientLevel(clientUuid).listAll().stream()
                .map(RoleRepresentation::getName)
                .collect(Collectors.toSet());
    }

    private List<String> bought(String org) {
        return assignment(org).getOrDefault("bought-features", List.of());
    }

    /** Takes a lock on its own thread and keeps it until closed, so the test thread really finds it held. */
    private final class LockHolder implements AutoCloseable {
        private final CountDownLatch release = new CountDownLatch(1);
        private final Future<?> held;

        LockHolder(ExecutorService executor, LockRequest request) throws Exception {
            CountDownLatch taken = new CountDownLatch(1);
            held = executor.submit(() -> {
                try (HeldLocks ignored = locks.acquire(request)) {
                    taken.countDown();
                    release.await();
                }
                return null;
            });
            assertTrue(taken.await(30, TimeUnit.SECONDS));
        }

        @Override
        public void close() throws Exception {
            release.countDown();
            held.get(30, TimeUnit.SECONDS);
        }
    }
}
