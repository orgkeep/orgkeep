package io.orgkeep.lifecycle;

import io.orgkeep.api.CatalogRoleService;
import io.orgkeep.api.CatalogService;
import io.orgkeep.api.OrgMemberService;
import io.orgkeep.api.OrgService;
import io.orgkeep.api.ProductService;
import io.orgkeep.api.TeamService;
import io.orgkeep.api.UserGroupService;
import io.orgkeep.api.model.AddMemberRequest;
import io.orgkeep.api.model.AssignProductRequest;
import io.orgkeep.api.model.Catalogs;
import io.orgkeep.api.model.CreateCatalogRequest;
import io.orgkeep.api.model.CreateCatalogRoleRequest;
import io.orgkeep.api.model.CreateOrgRequest;
import io.orgkeep.api.model.CreateProductRequest;
import io.orgkeep.api.model.CreateUserGroupRequest;
import io.orgkeep.api.model.GrantProductRequest;
import io.orgkeep.api.model.OrgGroups;
import io.orgkeep.api.model.ProductRole;
import io.orgkeep.api.model.QuotaCycle;
import io.orgkeep.config.OrgkeepConfig;
import io.orgkeep.repository.keycloak.KeycloakClients;
import io.orgkeep.repository.keycloak.KeycloakOrgs;
import io.orgkeep.repository.keycloak.KeycloakUsers;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.interceptor.Interceptor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.UserRepresentation;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Seeds an organisation, its owner, members, and the two examples of catalogs, products and teams of ARCHITECTURE.md
 * for manual testing (Bruno, Postman, the dashboard), when orgkeep.demo.enabled is true:
 * always in dev mode, ORGKEEP_DEMO_ENABLED=true in the Docker image.
 * The realm and its owner are created beforehand by OnPremiseBootstrap.
 * Idempotent: does nothing if the organisation already exists.
 */
@Slf4j
@ApplicationScoped
@RequiredArgsConstructor
public class DemoSeeder {
    static final String ORG = "acme";
    static final String ORG_OWNER = "org-owner";
    static final String ORG_OWNER_EMAIL = "org-owner@acme.test";
    static final String ORG_USER = "org-user";
    static final String ORG_USER_EMAIL = "org-user@acme.test";
    static final String PASSWORD = "password";
    static final String PRODUCT = "billing";
    static final String PROD = "prod";
    static final String SANDBOX = "sandbox";
    static final String SHOP_REPOSITORY = "shop-repository";
    static final String LEGAL_REPOSITORY = "legal-repository";
    static final String ANALYSTS = "analysts";
    static final String OPS = "ops";
    static final String PAUL = "paul";
    static final String PAUL_EMAIL = "paul@acme.test";
    // must match quarkus.oidc.client-id and the dev secret in the application.properties of orgkeep-dashboard
    static final String DASHBOARD_CLIENT_ID = "orgkeep-dashboard";
    static final String DASHBOARD_SECRET = "dashboard-dev-secret";
    static final String DASHBOARD_URL = "http://localhost:8284";

    private final OrgkeepConfig config;
    private final OrgService orgService;
    private final OrgMemberService memberService;
    private final CatalogService catalogService;
    private final ProductService productService;
    private final CatalogRoleService catalogRoleService;
    private final UserGroupService userGroupService;
    private final TeamService teamService;
    private final KeycloakOrgs orgs;
    private final KeycloakUsers users;
    private final KeycloakClients clients;

    // observers of the same event run by ascending priority: OnPremiseBootstrap has the default one
    // (APPLICATION + 500), this one must run after it, once the realm exists
    void onStart(@Observes @Priority(Interceptor.Priority.APPLICATION + 1000) StartupEvent ev) {
        if (!config.demo().enabled()) {
            return;
        }
        String realm = config.realmName()
                .orElseThrow(() -> new IllegalStateException("orgkeep.realm-name is required by the demo seeder"));

        enablePasswordGrant(realm);
        ensureDashboardClient(realm);

        ensureOrg(realm);
        ensureOrgUser(realm);
        ensureEnvironments(realm);
    }

    private void ensureOrg(String realm) {
        if (orgs.findByName(realm, ORG).isPresent()) {
            log.info("Demo organisation '{}' already present in realm '{}'", ORG, realm);
            return;
        }

        // the org owner is created first with a username and a final password, OrgService then reuses it as owner
        if (users.findIdByEmail(realm, ORG_OWNER_EMAIL).isEmpty()) {
            users.create(realm, demoUser(ORG_OWNER, ORG_OWNER_EMAIL, "Bob", "Durand"));
        }
        orgService.create(realm, new CreateOrgRequest(ORG, Set.of("acme.test"), ORG_OWNER_EMAIL, null));
        assignDemoProduct(realm);

        log.info("Demo data created in realm '{}': organisation '{}', owner '{}' / '{}'", realm, ORG, ORG_OWNER, PASSWORD);
    }

    /**
     * The simple case of ARCHITECTURE.md: a product without roles nor parameters, in the default catalog, assigned to
     * the org and given to every member by the predefined team users. Skipped if the realm owner deleted the default
     * catalog.
     */
    private void assignDemoProduct(String realm) {
        if (catalogService.list(realm).stream().noneMatch(catalog -> Catalogs.DEFAULT.equals(catalog.name()))) {
            return;
        }
        if (productService.list(realm, Catalogs.DEFAULT).stream().noneMatch(product -> PRODUCT.equals(product.name()))) {
            productService.create(realm, Catalogs.DEFAULT, new CreateProductRequest(PRODUCT, "Billing API", "Invoices"));
        }
        productService.assign(realm, ORG, Catalogs.DEFAULT, PRODUCT, new AssignProductRequest(1000L, QuotaCycle.MONTHLY));
        teamService.grantProduct(realm, ORG, OrgGroups.USERS, Catalogs.DEFAULT, PRODUCT, GrantProductRequest.allRoles());
    }

    /**
     * The case with several environments of ARCHITECTURE.md: catalogs prod and sandbox, products with roles bought by
     * the org, catalog roles, and two teams, one with a quota of its own. Paul is in the team ops.
     * Checked on its own, not with the org: a realm seeded before this example existed gets it too.
     */
    private void ensureEnvironments(String realm) {
        if (catalogService.list(realm).stream().anyMatch(catalog -> PROD.equals(catalog.name()))) {
            return;
        }
        List<ProductRole> engines = List.of(new ProductRole("scan", "Scan engine"), new ProductRole("flow", "Flow engine"));
        for (String catalog : List.of(PROD, SANDBOX)) {
            catalogService.create(realm, new CreateCatalogRequest(catalog, PROD.equals(catalog) ? "Production" : "Sandbox", null));
            productService.create(realm, catalog, new CreateProductRequest(SHOP_REPOSITORY, "Shop repository",
                    "Repositories of the shops", engines, List.of()));
            productService.create(realm, catalog, new CreateProductRequest(LEGAL_REPOSITORY, "Legal repository",
                    "Legal documents", engines, List.of()));
            catalogRoleService.create(realm, catalog, new CreateCatalogRoleRequest("scanner", "Scanner", null, true));
            catalogRoleService.create(realm, catalog, new CreateCatalogRoleRequest("stats", "Statistics", null, true));
            catalogRoleService.create(realm, catalog, new CreateCatalogRoleRequest("anonymous", "Test account",
                    "Excluded from the statistics", false));
        }

        // what acme negotiated
        productService.assign(realm, ORG, PROD, SHOP_REPOSITORY,
                new AssignProductRequest(1000L, QuotaCycle.MONTHLY, List.of("scan"), Map.of()));
        productService.assign(realm, ORG, PROD, LEGAL_REPOSITORY,
                new AssignProductRequest(500L, QuotaCycle.MONTHLY, List.of("scan", "flow"), Map.of()));
        productService.assign(realm, ORG, SANDBOX, SHOP_REPOSITORY,
                new AssignProductRequest(100L, QuotaCycle.DAILY, List.of("scan", "flow"), Map.of()));

        // analysts: both products of prod and the statistics; ops: shop-repository everywhere, with its own quota in prod
        userGroupService.create(realm, ORG, new CreateUserGroupRequest(ANALYSTS));
        teamService.grantProduct(realm, ORG, ANALYSTS, PROD, SHOP_REPOSITORY, GrantProductRequest.allRoles());
        teamService.grantProduct(realm, ORG, ANALYSTS, PROD, LEGAL_REPOSITORY, GrantProductRequest.allRoles());
        teamService.grantCatalogRole(realm, ORG, ANALYSTS, PROD, "stats");
        userGroupService.create(realm, ORG, new CreateUserGroupRequest(OPS));
        teamService.grantProduct(realm, ORG, OPS, PROD, SHOP_REPOSITORY, new GrantProductRequest(null, 300L, null));
        teamService.grantProduct(realm, ORG, OPS, SANDBOX, SHOP_REPOSITORY, GrantProductRequest.allRoles());
        teamService.grantCatalogRole(realm, ORG, OPS, PROD, "scanner");
        teamService.grantCatalogRole(realm, ORG, OPS, SANDBOX, "scanner");

        String paulId = users.findIdByEmail(realm, PAUL_EMAIL)
                .orElseGet(() -> users.create(realm, demoUser(PAUL, PAUL_EMAIL, "Paul", "Lefebvre")));
        if (orgs.organisationsOf(realm, paulId).isEmpty()) {
            memberService.add(realm, ORG, new AddMemberRequest(PAUL_EMAIL, null, null));
        }
        userGroupService.addMember(realm, ORG, OPS, paulId);
        log.info("Demo catalogs '{}' and '{}' created in realm '{}', member '{}' / '{}' in the team '{}'",
                PROD, SANDBOX, realm, PAUL, PASSWORD, OPS);
    }

    /**
     * A plain member of the org, in the default group /acme/user-groups/users (role org-user).
     * Checked on its own, not with the org: a realm seeded before this user existed gets it too.
     */
    private void ensureOrgUser(String realm) {
        // created first with a password, like the owner: OrgMemberService reuses it and sends no invitation
        String userId = users.findIdByEmail(realm, ORG_USER_EMAIL)
                .orElseGet(() -> users.create(realm, demoUser(ORG_USER, ORG_USER_EMAIL, "Claire", "Petit")));
        if (!orgs.organisationsOf(realm, userId).isEmpty()) {
            return;
        }
        memberService.add(realm, ORG, new AddMemberRequest(ORG_USER_EMAIL, null, null));
        log.info("Demo member '{}' / '{}' added to organisation '{}'", ORG_USER, PASSWORD, ORG);
    }

    /**
     * Tokens of the demo accounts are asked with a password grant (demo.http, Bruno, Postman), through the login
     * client of the realm: its tokens are the ones the apps of the realm owner get. admin-cli is not an option,
     * its tokens are "lightweight" since Keycloak 26.6 (no subject, no roles) and the API answers 403.
     * Demo only: the login client allows the authorization code flow only.
     */
    private void enablePasswordGrant(String realm) {
        // the login client is created by OnPremiseBootstrap, its clientId is the realm name
        String clientUuid = clients.findUuid(realm, realm)
                .orElseThrow(() -> new IllegalStateException("Login client not found in realm '%s'".formatted(realm)));
        ClientRepresentation client = clients.get(realm, clientUuid);
        if (Boolean.TRUE.equals(client.isDirectAccessGrantsEnabled())) {
            return;
        }
        client.setDirectAccessGrantsEnabled(true);
        clients.update(realm, clientUuid, client);
        log.info("Password grant enabled on the login client '{}' for the demo accounts", realm);
    }

    /**
     * Login client of orgkeep-dashboard, run on http://localhost:8284 (mvn quarkus:dev or the demo compose).
     * Created here because the dashboard never calls Keycloak itself: it only talks to the API of the core.
     * Outside of the demo, the operator creates this client with its own secret and URL.
     */
    private void ensureDashboardClient(String realm) {
        if (clients.findUuid(realm, DASHBOARD_CLIENT_ID).isPresent()) {
            return;
        }
        ClientRepresentation client = new ClientRepresentation();
        client.setClientId(DASHBOARD_CLIENT_ID);
        client.setName("Dashboard (demo)");
        // confidential client: the dashboard backend holds the secret, the browser never talks to the token endpoint
        client.setPublicClient(false);
        client.setSecret(DASHBOARD_SECRET);
        client.setStandardFlowEnabled(true);
        client.setDirectAccessGrantsEnabled(false);
        client.setRedirectUris(List.of(DASHBOARD_URL + "/*"));
        // "+": the logout redirect accepts the same URIs as the login redirect
        client.setAttributes(Map.of("post.logout.redirect.uris", "+"));
        clients.create(realm, client);
        log.info("Client '{}' created in realm '{}' for the dashboard on {}", DASHBOARD_CLIENT_ID, realm, DASHBOARD_URL);
    }

    // first and last name are required by Keycloak at login, a password grant fails without them
    private static UserRepresentation demoUser(String username, String email, String firstName, String lastName) {
        CredentialRepresentation password = new CredentialRepresentation();
        password.setType(CredentialRepresentation.PASSWORD);
        password.setValue(PASSWORD);
        password.setTemporary(false);

        UserRepresentation user = new UserRepresentation();
        user.setUsername(username);
        user.setEmail(email);
        user.setFirstName(firstName);
        user.setLastName(lastName);
        user.setEmailVerified(true);
        user.setEnabled(true);
        user.setCredentials(List.of(password));
        return user;
    }
}
