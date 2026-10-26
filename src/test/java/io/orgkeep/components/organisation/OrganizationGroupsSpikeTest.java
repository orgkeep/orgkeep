package io.orgkeep.components.organisation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.orgkeep.api.CatalogService;
import io.orgkeep.api.OrgMemberService;
import io.orgkeep.api.OrgService;
import io.orgkeep.api.ProductService;
import io.orgkeep.api.RealmProvisioner;
import io.orgkeep.api.model.AddMemberRequest;
import io.orgkeep.api.model.CreateCatalogRequest;
import io.orgkeep.api.model.CreateOrgRequest;
import io.orgkeep.api.model.CreateProductRequest;
import io.orgkeep.api.model.Feature;
import io.orgkeep.fixtures.TestRealm;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.CreatedResponseUtil;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.OrganizationGroupResource;
import org.keycloak.admin.client.resource.OrganizationResource;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spike: can the tree of groups of an org live in the groups of its Keycloak Organization (the "Groups" tab of an
 * organization) instead of under the top level group /acme of the realm? See "Groupes d'organisation" in
 * ARCHITECTURE.md.
 * <p>
 * Checks what the engine relies on: subgroups, client role mappings and attributes on such groups, the native mappers
 * of the products (User Attribute, aggregate.attrs) reading them, and who may be a member. Plain admin client calls,
 * except the catalog and the product, created by Orgkeep so that the token has the real scope and mappers.
 * The questions with an unknown answer are logged ("SPIKE ...") rather than asserted.
 * <p>
 * Result (Keycloak 26.7.4, 2026-10-26): the tree itself works (subgroups, role mappings and attributes stored, members
 * of the org only, gone with the org), but the engine cannot use it: an org group gives its members no effective
 * role and no attribute, so nothing of it reaches the token. rolesAndAttributesReachTheToken asserts that finding,
 * next to a control on a group of the realm.
 */
@Slf4j
@QuarkusTest
class OrganizationGroupsSpikeTest {
    private static final String ORG = "acme";
    private static final String PASSWORD = "password";
    private static final String ORG_POOL = "{\"pool\":\"acme\",\"limit\":1000,\"cycle\":\"monthly\"}";
    private static final String TEAM_POOL = "{\"pool\":\"acme/sales\",\"limit\":50,\"cycle\":\"monthly\"}";

    @Inject Keycloak keycloak;
    @Inject RealmProvisioner realmProvisioner;
    @Inject CatalogService catalogService;
    @Inject ProductService productService;
    @Inject OrgService orgService;
    @Inject OrgMemberService memberService;

    @ConfigProperty(name = "quarkus.keycloak.admin-client.server-url")
    String keycloakUrl;

    private final ObjectMapper json = new ObjectMapper();
    private TestRealm testRealm;

    @BeforeEach
    void beforeEach() {
        testRealm = TestRealm.create(keycloak);
        String realm = testRealm.name();
        realmProvisioner.ensureDefaults(realm);
        var loginClient = testRealm.resource().clients().get(clientUuid(realm));
        ClientRepresentation client = loginClient.toRepresentation();
        client.setDirectAccessGrantsEnabled(true);
        loginClient.update(client);

        catalogService.create(realm, new CreateCatalogRequest("prod", "Production", null));
        productService.create(realm, "prod", new CreateProductRequest("billing", "Billing", null,
                List.of(new Feature("stats", null)), List.of()));
        orgService.create(realm, new CreateOrgRequest(ORG, Set.of(), null, null));
    }

    @AfterEach
    void afterEach() {
        testRealm.close();
    }

    // the tree of an org: /user-groups/sales and /catalogs/prod/billing, three levels deep
    @Test
    void subgroups() {
        OrganizationResource org = organization(ORG);
        String userGroups = topLevel(org, "user-groups");
        String sales = subGroup(org, userGroups, "sales");
        String catalogs = topLevel(org, "catalogs");
        String billing = subGroup(org, subGroup(org, catalogs, "prod"), "billing");

        assertEquals(sales, org.groups().getGroupByPath("/user-groups/sales", false).getId());
        assertEquals(billing, org.groups().getGroupByPath("/catalogs/prod/billing", false).getId());
        log.info("SPIKE features of the server about organizations: {}", keycloak.serverInfo().getInfo()
                .getFeatures().stream().filter(feature -> feature.getName().toUpperCase().contains("ORGANIZATION"))
                .map(feature -> feature.getName() + " " + feature.getType() + " enabled=" + feature.isEnabled()).toList());
        GroupRepresentation salesGroup = org.groups().group(sales).toRepresentation(false);
        log.info("SPIKE path of an org group: {}, parent {}", salesGroup.getPath(), salesGroup.getParentId());

        Set<String> realmTopLevel = testRealm.resource().groups().groups().stream()
                .map(GroupRepresentation::getName).collect(Collectors.toSet());
        log.info("SPIKE top level groups of the realm (org groups listed there?): {}", realmTopLevel);
        try {
            GroupRepresentation byRealmPath = testRealm.resource().getGroupByPath("/user-groups/sales");
            log.info("SPIKE realm getGroupByPath(/user-groups/sales) finds the org group: {}", byRealmPath.getId());
        } catch (Exception e) {
            log.info("SPIKE realm getGroupByPath(/user-groups/sales) fails: {}", e.toString());
        }
    }

    // two orgs, the same names: the top level groups of a realm have unique names, hence the /acme prefix today
    @Test
    void twoOrgsWithTheSameTree() {
        orgService.create(testRealm.name(), new CreateOrgRequest("globex", Set.of(), null, null));
        topLevel(organization(ORG), "user-groups");
        try {
            String other = topLevel(organization("globex"), "user-groups");
            log.info("SPIKE a second org may have its own /user-groups: {}", other);
        } catch (Exception e) {
            log.info("SPIKE a second org may NOT have its own /user-groups: {}", e.toString());
            throw e;
        }
        // a realm group of the same name as an org group
        try (Response response = testRealm.resource().groups().add(group("user-groups"))) {
            log.info("SPIKE a realm group named like an org group: HTTP {}", response.getStatus());
        }
    }

    // the engine: client roles and attributes on the team, read by the native mappers of the product
    @Test
    void rolesAndAttributesReachTheToken() throws Exception {
        OrganizationResource org = organization(ORG);
        String userGroups = topLevel(org, "user-groups");
        String sales = subGroup(org, userGroups, "sales");
        OrganizationGroupResource salesGroup = org.groups().group(sales);

        String prod = clientUuid("prod");
        List<RoleRepresentation> roles = List.of(
                testRealm.resource().clients().get(prod).roles().get("billing").toRepresentation(),
                testRealm.resource().clients().get(prod).roles().get("billing:stats").toRepresentation());
        salesGroup.roles().clientLevel(prod).add(roles);
        log.info("SPIKE role mappings read back on the org group: {}", salesGroup.roles().clientLevel(prod).listAll()
                .stream().map(RoleRepresentation::getName).toList());

        // the team pool on the team, the org pool on its parent: aggregate.attrs reads the parents too
        setAttribute(salesGroup, "prod.billing.quotas", TEAM_POOL);
        setAttribute(org.groups().group(userGroups), "prod.billing.quotas", ORG_POOL);

        String paul = member("paul");
        salesGroup.addMember(paul);
        // a mapper on the login client itself (no scope, no role condition): reads an attribute of the org group?
        addAttributeMapper("spike", "spike");
        setAttribute(salesGroup, "spike", "from-the-org-group");

        // read before the control below: paul is also in /acme/user-groups/users, like every member
        List<String> effectiveRoles = testRealm.resource().users().get(paul).roles().clientLevel(prod).listEffective()
                .stream().map(RoleRepresentation::getName).toList();
        log.info("SPIKE effective prod roles of paul by the admin API: {}", effectiveRoles);
        JsonNode claims = claims(logIn("paul", "openid prod"));
        log.info("SPIKE token of paul, member of the org group sales: {}", claims.toPrettyString());
        log.info("SPIKE org group: roles {}, quotas {}, scope '{}', direct mapper {}",
                strings(claims.at("/resource_access/prod/roles")), claims.at("/prod/billing/quotas"),
                claims.path("scope").asText(), claims.path("spike"));

        // control: the same on a group of the realm, as Orgkeep does today
        GroupRepresentation control = testRealm.resource().getGroupByPath("/acme/user-groups/users");
        var controlGroup = testRealm.resource().groups().group(control.getId());
        controlGroup.roles().clientLevel(prod).add(roles);
        GroupRepresentation controlRepresentation = controlGroup.toRepresentation();
        controlRepresentation.setAttributes(Map.of("prod.billing.quotas", List.of(TEAM_POOL), "spike", List.of("from-the-realm-group")));
        controlGroup.update(controlRepresentation);
        String emma = member("emma");
        JsonNode controlClaims = claims(logIn("emma", "openid prod"));
        log.info("SPIKE realm group (control): roles {}, quotas {}, scope '{}', direct mapper {}",
                strings(controlClaims.at("/resource_access/prod/roles")), controlClaims.at("/prod/billing/quotas"),
                controlClaims.path("scope").asText(), controlClaims.path("spike"));
        assertEquals(Set.of("billing", "billing:stats"), strings(controlClaims.at("/resource_access/prod/roles")));
        assertEquals("from-the-realm-group", controlClaims.at("/spike/0").asText(), controlClaims.toString());

        // the finding (Keycloak 26.7.4): an org group gives no effective role and no attribute. Without a role of
        // the catalog, Keycloak even drops the scope prod. Fails the day Keycloak changes it: then look again
        assertTrue(effectiveRoles.isEmpty(), effectiveRoles.toString());
        assertTrue(claims.at("/resource_access/prod").isMissingNode(), claims.toString());
        assertTrue(claims.path("prod").isMissingNode(), claims.toString());
        assertTrue(claims.path("spike").isMissingNode(), claims.toString());
    }

    private void addAttributeMapper(String attribute, String claim) {
        var mapper = new org.keycloak.representations.idm.ProtocolMapperRepresentation();
        mapper.setName(attribute);
        mapper.setProtocol("openid-connect");
        mapper.setProtocolMapper("oidc-usermodel-attribute-mapper");
        mapper.setConfig(Map.of("user.attribute", attribute, "claim.name", claim, "jsonType.label", "String",
                "multivalued", "true", "aggregate.attrs", "true", "access.token.claim", "true"));
        try (Response response = testRealm.resource().clients().get(clientUuid(testRealm.name()))
                .getProtocolMappers().createMapper(mapper)) {
            log.info("SPIKE direct mapper {} on the login client: HTTP {}", attribute, response.getStatus());
        }
    }

    // who may join an org group, and what the user side shows
    @Test
    void membership() {
        OrganizationResource org = organization(ORG);
        String sales = subGroup(org, topLevel(org, "user-groups"), "sales");
        OrganizationGroupResource salesGroup = org.groups().group(sales);

        String stranger = testRealm.createUser("stranger", "stranger@other.test");
        try {
            salesGroup.addMember(stranger);
            log.info("SPIKE a user who is not a member of the org joined its group");
        } catch (Exception e) {
            log.info("SPIKE a user who is not a member of the org may NOT join its group: {}", e.toString());
        }
        try {
            testRealm.resource().users().get(stranger).joinGroup(sales);
            log.info("SPIKE the realm API (users/{id}/groups) let a non member join an org group");
        } catch (Exception e) {
            log.info("SPIKE the realm API (users/{id}/groups) refuses an org group: {}", e.toString());
        }

        String paul = member("paul");
        salesGroup.addMember(paul);
        log.info("SPIKE groups of paul by the realm API: {}", testRealm.resource().users().get(paul).groups().stream()
                .map(GroupRepresentation::getPath).toList());
        log.info("SPIKE groups of paul by the org API: {}", org.members().member(paul).groups(null, null, true).stream()
                .map(GroupRepresentation::getPath).toList());
        log.info("SPIKE members of sales: {}", salesGroup.getMembers(null, null, true).stream()
                .map(UserRepresentation::getUsername).toList());

        // paul leaves the org: does he leave its groups?
        try (Response response = org.members().member(paul).delete()) {
            log.info("SPIKE paul removed from the org: HTTP {}", response.getStatus());
        }
        log.info("SPIKE groups of paul after leaving the org: {}", testRealm.resource().users().get(paul).groups()
                .stream().map(GroupRepresentation::getPath).toList());
        log.info("SPIKE members of sales after paul left the org: {}", salesGroup.getMembers(null, null, true).stream()
                .map(UserRepresentation::getUsername).toList());
    }

    // the org deleted: do its groups go with it?
    @Test
    void deletingTheOrg() {
        OrganizationResource org = organization(ORG);
        String sales = subGroup(org, topLevel(org, "user-groups"), "sales");
        try (Response response = org.delete()) {
            log.info("SPIKE org deleted: HTTP {}", response.getStatus());
        }
        try {
            testRealm.resource().groups().group(sales).toRepresentation();
            log.info("SPIKE the group of a deleted org still exists");
        } catch (Exception e) {
            log.info("SPIKE the group of a deleted org is gone: {}", e.toString());
        }
    }

    private OrganizationResource organization(String alias) {
        String id = testRealm.resource().organizations().search(alias).stream()
                .filter(org -> alias.equals(org.getAlias())).findFirst().orElseThrow().getId();
        return testRealm.resource().organizations().get(id);
    }

    private static GroupRepresentation group(String name) {
        GroupRepresentation group = new GroupRepresentation();
        group.setName(name);
        return group;
    }

    private static String topLevel(OrganizationResource org, String name) {
        try (Response response = org.groups().addTopLevelGroup(group(name))) {
            return CreatedResponseUtil.getCreatedId(response);
        }
    }

    private static String subGroup(OrganizationResource org, String parentId, String name) {
        try (Response response = org.groups().group(parentId).addSubGroup(group(name))) {
            return CreatedResponseUtil.getCreatedId(response);
        }
    }

    private static void setAttribute(OrganizationGroupResource group, String name, String value) {
        GroupRepresentation representation = group.toRepresentation(false);
        representation.setAttributes(Map.of(name, List.of(value)));
        try (Response response = group.update(representation)) {
            log.info("SPIKE attribute {} set on {}: HTTP {}", name, representation.getName(), response.getStatus());
        }
    }

    private String clientUuid(String clientId) {
        return testRealm.resource().clients().findByClientId(clientId).get(0).getId();
    }

    private String member(String username) {
        UserRepresentation user = new UserRepresentation();
        user.setUsername(username);
        user.setEmail(username + "@acme.test");
        user.setEmailVerified(true);
        user.setFirstName(username);
        user.setLastName("Test");
        user.setEnabled(true);
        CredentialRepresentation credential = new CredentialRepresentation();
        credential.setType(CredentialRepresentation.PASSWORD);
        credential.setValue(PASSWORD);
        credential.setTemporary(false);
        user.setCredentials(List.of(credential));
        testRealm.createUser(user);
        return memberService.add(testRealm.name(), ORG,
                new AddMemberRequest(user.getEmail(), user.getFirstName(), user.getLastName())).id();
    }

    private String logIn(String username, String scope) throws Exception {
        String form = Map.of("grant_type", "password", "client_id", testRealm.name(),
                        "username", username, "password", PASSWORD, "scope", scope)
                .entrySet().stream()
                .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(keycloakUrl + "/realms/" + testRealm.name() + "/protocol/openid-connect/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        HttpResponse<String> response;
        try (HttpClient http = HttpClient.newHttpClient()) {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        }
        assertEquals(200, response.statusCode(), response.body());
        return json.readTree(response.body()).path("access_token").asText();
    }

    private JsonNode claims(String token) throws Exception {
        return json.readTree(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
    }

    private static Set<String> strings(JsonNode array) {
        Set<String> values = new HashSet<>();
        array.forEach(element -> values.add(element.asText()));
        return values;
    }

    private static Set<String> pools(JsonNode quotas) {
        assertTrue(quotas.isArray(), quotas.toString());
        Set<String> pools = new HashSet<>();
        quotas.forEach(pool -> pools.add(pool.path("pool").asText()));
        return pools;
    }
}
