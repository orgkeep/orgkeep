package io.orgkeep.dashboard.organisation;

import io.orgkeep.dashboard.core.CoreApiException;
import io.orgkeep.dashboard.core.OrgMembersClient;
import io.orgkeep.dashboard.core.OrganisationsClient;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The relay between the front and the core, without a core: the REST client is a Mockito mock.
 * <p>
 * {@code @InjectMock} replaces the client bean by a mock for each test (a Spring {@code @MockBean}), so the tests
 * check what the dashboard does with the answers of the core, not the core itself (tested in orgkeep-core).
 * {@code @TestSecurity} runs the HTTP call as a logged in user with the given roles, without Keycloak.
 */
@QuarkusTest
class OrganisationsResourceTest {

    @InjectMock
    @RestClient
    OrganisationsClient organisationsClient;

    @InjectMock
    @RestClient
    OrgMembersClient orgMembersClient;

    @Test
    @TestSecurity(user = "alice", roles = "realm-owner")
    void listsTheOrganisationsOfTheCore() {
        when(organisationsClient.list("ac", "prod", "billing", 0, OrganisationsResource.MAX_ORGS))
                .thenReturn(List.of(new OrgSummary("id-1", "acme", Set.of("acme.test"), true,
                        List.of(new OrgSummary.OrgOwner("user-1", "bob", "bob@acme.test", "Bob", "Durand")),
                        12, List.of("prod/billing", "prod/storage"))));

        // the three filters are passed on to the core
        given().queryParam("search", "ac").queryParam("catalog", "prod").queryParam("product", "billing")
                .when().get("/api/organisations")
                .then().statusCode(200)
                .body("[0].name", equalTo("acme"))
                .body("[0].emailDomains[0]", equalTo("acme.test"))
                .body("[0].enabled", equalTo(true))
                .body("[0].owners[0].firstName", equalTo("Bob"))
                .body("[0].memberCount", equalTo(12))
                .body("[0].products[1]", equalTo("prod/storage"));
    }

    // without the query params, the client receives nulls: the REST client then leaves them out of the core URL
    @Test
    @TestSecurity(user = "alice", roles = "realm-owner")
    void listsWithoutFilters() {
        when(organisationsClient.list(null, null, null, 0, OrganisationsResource.MAX_ORGS)).thenReturn(List.of());

        given().when().get("/api/organisations")
                .then().statusCode(200)
                .body("size()", equalTo(0));

        verify(organisationsClient).list(null, null, null, 0, OrganisationsResource.MAX_ORGS);
    }

    @Test
    @TestSecurity(user = "alice", roles = "realm-owner")
    void createsAnOrganisation() {
        CreateOrgRequest request = new CreateOrgRequest("globex", Set.of("globex.com"), "carol@globex.com", "Carol", "Petit");
        when(organisationsClient.create(request))
                .thenReturn(new CreateOrgResponse("id-2", "globex", Set.of("globex.com"), "user-1"));

        given().contentType(ContentType.JSON).body(request)
                .when().post("/api/organisations")
                .then().statusCode(201)
                .body("name", equalTo("globex"));

        // records have equals(): the core received exactly what the front sent
        verify(organisationsClient).create(request);
    }

    // The front shows the message of the core as is: the status and the message must survive the relay
    @Test
    @TestSecurity(user = "alice", roles = "realm-owner")
    void relaysTheErrorOfTheCore() {
        when(organisationsClient.create(any()))
                .thenThrow(new CoreApiException(409, "Organisation 'acme' already exists"));

        given().contentType(ContentType.JSON).body(new CreateOrgRequest("acme", Set.of(), null, null, null))
                .when().post("/api/organisations")
                .then().statusCode(409)
                .body("message", equalTo("Organisation 'acme' already exists"));
    }

    @Test
    @TestSecurity(user = "alice", roles = "realm-owner")
    void getsAnOrganisation() {
        when(organisationsClient.get("acme")).thenReturn(new OrgDetails("id-1", "acme", Set.of("acme.test"), true,
                "org-acme", List.of(new OrgSummary.OrgOwner("user-1", "bob", "bob@acme.test", "Bob", "Durand"))));

        given().when().get("/api/organisations/{org}", "acme")
                .then().statusCode(200)
                .body("clientId", equalTo("org-acme"))
                .body("owners[0].lastName", equalTo("Durand"));
    }

    // the members come from another client of the core (path of the org admins)
    @Test
    @TestSecurity(user = "alice", roles = "realm-owner")
    void listsTheMembersOfAnOrganisation() {
        when(orgMembersClient.list("acme")).thenReturn(List.of(
                new OrgMember("user-1", "bob", "bob@acme.test", "Bob", "Durand", true, "ORG_OWNER")));

        given().when().get("/api/organisations/{org}/members", "acme")
                .then().statusCode(200)
                .body("[0].role", equalTo("ORG_OWNER"));
    }

    @Test
    @TestSecurity(user = "alice", roles = "realm-owner")
    void assignsAProduct() {
        AssignProductRequest request = new AssignProductRequest(1000L, "MONTHLY", List.of("read"), Map.of("region", "eu"));
        when(organisationsClient.assignProduct("acme", "prod", "billing", request))
                .thenReturn(new OrgProduct("prod", "billing", 1000L, "MONTHLY", List.of("read"), Map.of("region", "eu")));

        given().contentType(ContentType.JSON).body(request)
                .when().put("/api/organisations/{org}/catalogs/{catalog}/products/{product}", "acme", "prod", "billing")
                .then().statusCode(200)
                .body("quota", equalTo(1000))
                .body("boughtRoles[0]", equalTo("read"))
                .body("params.region", equalTo("eu"));

        verify(organisationsClient).assignProduct("acme", "prod", "billing", request);
    }

    @Test
    @TestSecurity(user = "alice", roles = "realm-owner")
    void deletesAnOrganisation() {
        given().when().delete("/api/organisations/{org}", "acme")
                .then().statusCode(204);

        verify(organisationsClient).delete("acme");
    }

    // An org owner is logged in too, but must not reach the core with this call
    @Test
    @TestSecurity(user = "bob", roles = "org-owner")
    void refusesAUserWhoIsNotRealmOwner() {
        given().when().get("/api/organisations")
                .then().statusCode(403);

        verifyNoInteractions(organisationsClient);
    }
}
