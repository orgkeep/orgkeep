package org.orgkeep.components.catalog.product;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;
import org.mockito.Mockito;
import org.orgkeep.api.CatalogService;
import org.orgkeep.api.OrgService;
import org.orgkeep.api.ProductService;
import org.orgkeep.api.exceptions.ProductAlreadyAssignedException;
import org.orgkeep.api.model.AssignProductRequest;
import org.orgkeep.api.model.CreateCatalogRequest;
import org.orgkeep.api.model.CreateOrgRequest;
import org.orgkeep.api.model.CreateProductRequest;
import org.orgkeep.api.model.OrgGroups;
import org.orgkeep.api.model.OrgProduct;
import org.orgkeep.api.model.QuotaCycle;
import org.orgkeep.components.organisation.usergroup.team.TeamAlignment;
import org.orgkeep.fixtures.TestRealm;
import org.orgkeep.repository.keycloak.KeycloakGroups;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

/**
 * {@code assign} (#29), with real services against the Keycloak of Dev Services, in a throwaway realm per test.
 * Spies on {@link TeamAlignment} (is it called?) and {@link KeycloakGroups} (a group "created meanwhile" is
 * simulated by a first read that finds nothing, while the group is there).
 */
@QuarkusTest
class ProductAssignTest {
    private static final String CATALOG = "prod";
    private static final String ORG = "acme";

    @Inject Keycloak keycloak;
    @Inject OrgService orgService;
    @Inject CatalogService catalogService;
    @Inject ProductService productService;
    @InjectSpy TeamAlignment teamAlignment;
    @InjectSpy KeycloakGroups groups;

    private TestRealm testRealm;

    @BeforeEach
    void beforeEach() {
        testRealm = TestRealm.create(keycloak);
        catalogService.create(realm(), new CreateCatalogRequest(CATALOG, "Production", null));
        orgService.create(realm(), new CreateOrgRequest(ORG, Set.of(), null, null));
        for (String product : new String[]{"a", "b"}) {
            productService.create(realm(), CATALOG, new CreateProductRequest(product, product, null));
        }
        Mockito.clearInvocations(teamAlignment);
    }

    @AfterEach
    void afterEach() {
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

    private AssignProductRequest request() {
        return new AssignProductRequest(1000L, QuotaCycle.MONTHLY);
    }

    private String realm() {
        return testRealm.name();
    }
}
