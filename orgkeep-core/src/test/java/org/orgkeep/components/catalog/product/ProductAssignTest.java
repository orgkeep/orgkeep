package org.orgkeep.components.catalog.product;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mockito.Mockito;
import org.orgkeep.api.CatalogService;
import org.orgkeep.api.OrgService;
import org.orgkeep.api.ProductService;
import org.orgkeep.api.ServiceAccountService;
import org.orgkeep.api.exceptions.OrgNotFoundException;
import org.orgkeep.api.exceptions.ProductAlreadyAssignedException;
import org.orgkeep.api.model.AssignProductRequest;
import org.orgkeep.api.model.CreateCatalogRequest;
import org.orgkeep.api.model.CreateOrgRequest;
import org.orgkeep.api.model.CreateProductRequest;
import org.orgkeep.api.model.CreateServiceAccountRequest;
import org.orgkeep.api.model.OrgGroups;
import org.orgkeep.api.model.OrgProduct;
import org.orgkeep.api.model.QuotaCycle;
import org.orgkeep.components.organisation.serviceaccount.ServiceAccounts;
import org.orgkeep.components.organisation.usergroup.team.TeamAlignment;
import org.orgkeep.fixtures.TestRealm;
import org.orgkeep.repository.keycloak.KeycloakGroups;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

/**
 * {@code assign} (#29), with real services against the Keycloak of Dev Services, in one realm for the whole class.
 * Spies on {@link TeamAlignment} (is it called?) and {@link KeycloakGroups} (a group "created meanwhile" is
 * simulated by a first read that finds nothing, while the group is there).
 * <p>
 * Every org and every catalog (including the ones a test creates, like "sandbox" or "late") is deleted after each
 * test (@AfterEach), so the fixture of the next test starts from a clean realm.
 */
@Slf4j
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProductAssignTest {
    private static final String CATALOG = "prod";
    private static final String ORG = "acme";

    @Inject OrgService orgService;
    @Inject CatalogService catalogService;
    @Inject ProductService productService;
    @Inject ServiceAccountService serviceAccountService;
    @InjectSpy TeamAlignment teamAlignment;
    @InjectSpy KeycloakGroups groups;
    @InjectSpy ServiceAccounts serviceAccountsSpy;

    private TestRealm testRealm;

    @BeforeAll
    void createRealm() {
        testRealm = TestRealm.create();
    }

    @BeforeEach
    void setUpFixture() {
        catalogService.create(realm(), new CreateCatalogRequest(CATALOG, "Production", null));
        orgService.create(realm(), new CreateOrgRequest(ORG, Set.of(), null, null));
        for (String product : new String[]{"a", "b"}) {
            productService.create(realm(), CATALOG, new CreateProductRequest(product, product, null));
        }
        Mockito.clearInvocations(teamAlignment);
    }

    @AfterEach
    void cleanUp() {
        try {
            try {
                orgService.delete(realm(), ORG);
            } catch (OrgNotFoundException ignored) {
                // not created by this test
            }
            for (var catalog : catalogService.list(realm())) {
                for (var product : productService.list(realm(), catalog.name())) {
                    productService.delete(realm(), catalog.name(), product.name());
                }
                catalogService.delete(realm(), catalog.name());
            }
        } catch (RuntimeException e) {
            // a spy stub armed by a failed test (Quarkus resets @InjectSpy after this method, not before) would
            // otherwise fail every following test
            log.warn("Cleanup failed, recreating the test realm", e);
            testRealm.close();
            testRealm = TestRealm.create();
        }
    }

    @AfterAll
    void deleteRealm() {
        testRealm.close();
    }

    @Test
    void firstAssignmentDoesNotAlignTheTeams() {
        productService.assign(realm(), ORG, CATALOG, "a", request());

        Mockito.verify(teamAlignment, Mockito.never()).align(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void renegotiationAlignsTheTeams() {
        productService.assign(realm(), ORG, CATALOG, "a", request());
        productService.assign(realm(), ORG, CATALOG, "a", request());

        Mockito.verify(teamAlignment, Mockito.times(1)).align(realm(), ORG, CATALOG, "a");
    }

    // The group of the catalogue is created between the read and the create: it is reused
    @Test
    void catalogGroupCreatedMeanwhileIsReused() {
        productService.assign(realm(), ORG, CATALOG, "a", request());
        String catalogPath = OrgGroups.catalog(ORG, CATALOG);
        Mockito.doReturn(Optional.empty()).doCallRealMethod().when(groups).findId(eq(realm()), eq(catalogPath));

        OrgProduct assigned = productService.assign(realm(), ORG, CATALOG, "b", request());

        assertEquals("b", assigned.product());
        // the conflict branch really ran: the creation of the catalogue group was tried, and refused by Keycloak
        // (twice: the first assignment created the group, the second one tried again and got the conflict)
        Mockito.verify(groups, Mockito.times(2)).createChildIfAbsent(eq(realm()), anyString(), eq(CATALOG), anyMap());
        assertTrue(groups.findId(realm(), OrgGroups.assignment(ORG, CATALOG, "b")).isPresent());
        assertTrue(groups.findId(realm(), OrgGroups.assignment(ORG, CATALOG, "a")).isPresent());
    }

    // The group of the product is created between the read and the create: 409 through AlreadyExistsException
    @Test
    void productGroupCreatedMeanwhileIsAConflict() {
        productService.assign(realm(), ORG, CATALOG, "a", request());
        String assignmentPath = OrgGroups.assignment(ORG, CATALOG, "a");
        Mockito.doReturn(Optional.empty()).doCallRealMethod().when(groups).findId(eq(realm()), eq(assignmentPath));

        assertThrowsExactly(ProductAlreadyAssignedException.class,
                () -> productService.assign(realm(), ORG, CATALOG, "a", request()));

        assertTrue(groups.findId(realm(), assignmentPath).isPresent());
    }

    // the last product of a catalog goes: it is optional again on the service accounts that had it by default. Another
    // catalog stays as it is, and so does a catalog while the org still has a product in it
    @Test
    void unassigningTheLastProductOfACatalogMakesItOptionalOnTheServiceAccounts() {
        catalogService.create(realm(), new CreateCatalogRequest("sandbox", "Sandbox", null));
        productService.create(realm(), "sandbox", new CreateProductRequest("s", "s", null));
        productService.assign(realm(), ORG, CATALOG, "a", request());
        productService.assign(realm(), ORG, CATALOG, "b", request());
        productService.assign(realm(), ORG, "sandbox", "s", request());
        serviceAccountService.create(realm(), ORG,
                new CreateServiceAccountRequest("batch", null, java.util.List.of(CATALOG, "sandbox")));
        String uuid = testRealm.resource().clients().findByClientId("org-acme.batch").getFirst().getId();

        productService.unassign(realm(), ORG, CATALOG, "a");
        assertEquals(Set.of(CATALOG, "sandbox"), defaultCatalogs(uuid), "a product is left in the catalog");

        productService.unassign(realm(), ORG, CATALOG, "b");
        assertEquals(Set.of("sandbox"), defaultCatalogs(uuid));
        assertTrue(testRealm.resource().clients().get(uuid).getOptionalClientScopes().stream()
                .anyMatch(scope -> CATALOG.equals(scope.getName())));
    }

    // the first product of the org in a catalog created after the service account: the catalog becomes optional on it,
    // a catalog the org has nothing in is not touched, and a default one stays default
    @Test
    void firstAssignmentInACatalogMakesItOptionalOnTheServiceAccounts() {
        serviceAccountService.create(realm(), ORG, new CreateServiceAccountRequest("batch", null, null));
        String uuid = testRealm.resource().clients().findByClientId("org-acme.batch").getFirst().getId();
        catalogService.create(realm(), new CreateCatalogRequest("late", "Late", null));
        catalogService.create(realm(), new CreateCatalogRequest("other", "Other", null));
        productService.create(realm(), "late", new CreateProductRequest("l", "l", null));
        assertEquals(Set.of(), optionalOf(uuid, "late", "other"));

        productService.assign(realm(), ORG, "late", "l", request());

        assertEquals(Set.of("late"), optionalOf(uuid, "late", "other"));
        assertEquals(Set.of(), defaultCatalogs(uuid));
    }

    // the assignment fails after its group is created (the spy throws once): the repeated assign takes the
    // renegotiation branch and finishes the job
    @Test
    void repeatedAssignFinishesTheScopeOfTheServiceAccounts() {
        serviceAccountService.create(realm(), ORG, new CreateServiceAccountRequest("batch", null, null));
        String uuid = testRealm.resource().clients().findByClientId("org-acme.batch").getFirst().getId();
        catalogService.create(realm(), new CreateCatalogRequest("late", "Late", null));
        productService.create(realm(), "late", new CreateProductRequest("l", "l", null));
        Mockito.doThrow(new IllegalStateException("Keycloak unavailable"))
                .doCallRealMethod()
                .when(serviceAccountsSpy).makeCatalogAvailable(anyString(), anyString(), anyString());

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> productService.assign(realm(), ORG, "late", "l", request()));
        assertEquals(Set.of(), optionalOf(uuid, "late"));

        productService.assign(realm(), ORG, "late", "l", request());

        assertEquals(Set.of("late"), optionalOf(uuid, "late"));
    }

    private Set<String> optionalOf(String clientUuid, String... names) {
        Set<String> wanted = Set.of(names);
        return testRealm.resource().clients().get(clientUuid).getOptionalClientScopes().stream()
                .map(scope -> scope.getName())
                .filter(wanted::contains)
                .collect(java.util.stream.Collectors.toSet());
    }

    private Set<String> defaultCatalogs(String clientUuid) {
        return testRealm.resource().clients().get(clientUuid).getDefaultClientScopes().stream()
                .map(scope -> scope.getName())
                .filter(name -> name.equals(CATALOG) || name.equals("sandbox"))
                .collect(java.util.stream.Collectors.toSet());
    }

    private AssignProductRequest request() {
        return new AssignProductRequest(1000L, QuotaCycle.MONTHLY);
    }

    private String realm() {
        return testRealm.name();
    }
}
