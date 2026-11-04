package org.orgkeep.lifecycle;

import org.orgkeep.api.CatalogRoleService;
import org.orgkeep.api.CatalogService;
import org.orgkeep.api.OrgMemberService;
import org.orgkeep.api.OrgService;
import org.orgkeep.api.ProductService;
import org.orgkeep.api.RealmProvisioner;
import org.orgkeep.api.TeamService;
import org.orgkeep.api.UserGroupService;
import org.orgkeep.api.model.AddMemberRequest;
import org.orgkeep.api.model.AssignProductRequest;
import org.orgkeep.api.model.Catalogs;
import org.orgkeep.api.model.CreateCatalogRequest;
import org.orgkeep.api.model.CreateCatalogRoleRequest;
import org.orgkeep.api.model.CreateOrgRequest;
import org.orgkeep.api.model.CreateProductRequest;
import org.orgkeep.api.model.CreateUserGroupRequest;
import org.orgkeep.api.model.Feature;
import org.orgkeep.api.model.FeatureQuota;
import org.orgkeep.api.model.GrantProductRequest;
import org.orgkeep.api.model.TeamFeatureQuota;
import org.orgkeep.api.model.OrgGroups;
import org.orgkeep.api.model.QuotaCycle;
import org.orgkeep.config.OrgkeepConfig;
import org.orgkeep.repository.keycloak.KeycloakClients;
import org.orgkeep.repository.keycloak.KeycloakOrgs;
import org.orgkeep.repository.keycloak.KeycloakUsers;
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
 * Seeds an organisation, its owner, members, and two examples of catalogs, products and teams (a simple one, and one
 * with several environments) for manual testing (Bruno, Postman, the dashboard), when orgkeep.demo.enabled is true:
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
    // a product only exists in its catalog: the same name in "products" (simple) and in prod / sandbox (metered)
    static final String BILLING = "billing";
    static final String SHOP = "shop";
    static final String PROD = "prod";
    static final String SANDBOX = "sandbox";
    static final String ACCOUNTING = "accounting";
    static final String SALES = "sales";
    static final String PAUL = "paul";
    static final String PAUL_EMAIL = "paul@acme.test";
    static final String EMMA = "emma";
    static final String EMMA_EMAIL = "emma@acme.test";
    // must match quarkus.oidc.client-id and the dev secret in the application.properties of orgkeep-dashboard,
    // and orgkeep.dashboard.client-id (its default)
    static final String DASHBOARD_CLIENT_ID = "orgkeep-dashboard";
    static final String DASHBOARD_SECRET = "dashboard-dev-secret";
    static final String DASHBOARD_URL = "http://localhost:8284";
    // fixed secrets of the catalog clients prod and sandbox (prod-demo-secret...): the demo APIs of the compose
    // (orgkeep-examples) introspect with them, without a secret generated in the dashboard first
    static final String CATALOG_SECRET_SUFFIX = "-demo-secret";

    private final OrgkeepConfig config;
    private final OrgService orgService;
    private final OrgMemberService memberService;
    private final CatalogService catalogService;
    private final ProductService productService;
    private final CatalogRoleService catalogRoleService;
    private final UserGroupService userGroupService;
    private final TeamService teamService;
    private final RealmProvisioner realmProvisioner;
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
        orgService.create(realm, new CreateOrgRequest(ORG, Set.of("acme.test"), ORG_OWNER_EMAIL, null, "Bob", "Durand"));
        assignDemoProduct(realm);

        log.info("Demo data created in realm '{}': organisation '{}', owner '{}' / '{}'", realm, ORG, ORG_OWNER, PASSWORD);
    }

    /**
     * The simple case: a product without features nor parameters, in the default catalog, assigned to
     * the org and given to every member by the predefined team users. Skipped if the realm owner deleted the default
     * catalog.
     */
    private void assignDemoProduct(String realm) {
        if (catalogService.list(realm).stream().noneMatch(catalog -> Catalogs.DEFAULT.equals(catalog.name()))) {
            return;
        }
        if (productService.list(realm, Catalogs.DEFAULT).stream().noneMatch(product -> BILLING.equals(product.name()))) {
            productService.create(realm, Catalogs.DEFAULT, new CreateProductRequest(BILLING, "Billing API", "Invoices"));
        }
        productService.assign(realm, ORG, Catalogs.DEFAULT, BILLING, new AssignProductRequest(1000L, QuotaCycle.MONTHLY));
        teamService.grantProduct(realm, ORG, OrgGroups.USERS, Catalogs.DEFAULT, BILLING, GrantProductRequest.allFeatures());
    }

    /**
     * The case with several environments and metered features: catalogs prod and sandbox, repositories billing and
     * shop, permissions (read, write, stats) and engines bought with a quota each (search, dig). Two teams: accounting
     * (Emma) and sales (Paul).
     * Checked on its own, not with the org: a realm seeded before this example existed gets it too.
     */
    private void ensureEnvironments(String realm) {
        if (catalogService.list(realm).stream().anyMatch(catalog -> PROD.equals(catalog.name()))) {
            return;
        }
        Feature read = new Feature("read", "Read");
        Feature write = new Feature("write", "Write");
        Feature stats = new Feature("stats", "Statistics");
        Feature search = new Feature("search", "Search engine", true);
        Feature dig = new Feature("dig", "Dig engine", true);
        for (String catalog : List.of(PROD, SANDBOX)) {
            catalogService.create(realm, new CreateCatalogRequest(catalog, PROD.equals(catalog) ? "Production" : "Sandbox", null));
            setDemoSecret(realm, catalog);
            productService.create(realm, catalog, new CreateProductRequest(BILLING, "Billing repository",
                    "Invoices and payments", List.of(read, write, stats, search, dig), List.of()));
            productService.create(realm, catalog, new CreateProductRequest(SHOP, "Shop repository",
                    "Orders of the shops", List.of(read, write, stats, search), List.of()));
            catalogRoleService.create(realm, catalog, new CreateCatalogRoleRequest("anonymous", "Test account",
                    "Excluded from the statistics", false));
        }

        // what acme negotiated: the bundle of each repository, a quota per engine. Larger quotas in the sandbox
        for (String catalog : List.of(PROD, SANDBOX)) {
            boolean prod = PROD.equals(catalog);
            productService.assign(realm, ORG, catalog, BILLING, AssignProductRequest.metered(
                    List.of("read", "write", "stats", "search", "dig"),
                    Map.of("search", daily(prod ? 200 : 1000), "dig", daily(prod ? 100 : 1000))));
            productService.assign(realm, ORG, catalog, SHOP, AssignProductRequest.metered(
                    List.of("write", "stats", "search"), Map.of("search", daily(prod ? 100 : 1000))));
        }

        // accounting: everything on billing; sales: write and search on shop, stats and search on billing. In prod, sales
        // has its own budget for search on billing (50 a day, pool "/sales"), on top of the pool of acme ("/")
        userGroupService.create(realm, ORG, new CreateUserGroupRequest(ACCOUNTING));
        userGroupService.create(realm, ORG, new CreateUserGroupRequest(SALES));
        for (String catalog : List.of(PROD, SANDBOX)) {
            teamService.grantProduct(realm, ORG, ACCOUNTING, catalog, BILLING, GrantProductRequest.allFeatures());
            teamService.grantProduct(realm, ORG, SALES, catalog, SHOP, features("write", "search"));
            teamService.grantProduct(realm, ORG, SALES, catalog, BILLING, PROD.equals(catalog)
                    ? new GrantProductRequest(List.of("stats", "search"), null, null,
                            Map.of("search", new TeamFeatureQuota(50L, QuotaCycle.DAILY)))
                    : features("stats", "search"));
        }

        addToTeam(realm, demoUser(PAUL, PAUL_EMAIL, "Paul", "Lefebvre"), SALES);
        addToTeam(realm, demoUser(EMMA, EMMA_EMAIL, "Emma", "Roux"), ACCOUNTING);
        log.info("Demo catalogs '{}' and '{}' created in realm '{}', members '{}' ({}) and '{}' ({}), password '{}'",
                PROD, SANDBOX, realm, PAUL, SALES, EMMA, ACCOUNTING, PASSWORD);
    }

    /**
     * A known secret on the catalog client, instead of the random one Keycloak gave it: regenerating it in the dashboard
     * still works, the demo APIs then need the new one.
     */
    private void setDemoSecret(String realm, String catalog) {
        String clientUuid = clients.findUuid(realm, catalog)
                .orElseThrow(() -> new IllegalStateException("Catalog client '%s' not found".formatted(catalog)));
        ClientRepresentation client = clients.get(realm, clientUuid);
        client.setSecret(catalog + CATALOG_SECRET_SUFFIX);
        clients.update(realm, clientUuid, client);
    }

    /** Created first with a password, like the owner: OrgMemberService reuses the user and sends no invitation. */
    private void addToTeam(String realm, UserRepresentation user, String team) {
        String userId = users.findIdByEmail(realm, user.getEmail())
                .orElseGet(() -> users.create(realm, user));
        if (orgs.organisationsOf(realm, userId).isEmpty()) {
            memberService.add(realm, ORG, new AddMemberRequest(user.getEmail(), user.getFirstName(), user.getLastName()));
        }
        userGroupService.addMember(realm, ORG, team, userId);
    }

    private static FeatureQuota daily(long quota) {
        return new FeatureQuota(quota, QuotaCycle.DAILY);
    }

    private static GrantProductRequest features(String... features) {
        return new GrantProductRequest(List.of(features), null, null);
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
        memberService.add(realm, ORG, new AddMemberRequest(ORG_USER_EMAIL, "Claire", "Petit"));
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
        createDashboardClient(realm);
        // OnPremiseBootstrap, which sets it at each startup, ran before the client existed
        config.dashboard().loginTheme()
                .ifPresent(theme -> realmProvisioner.setLoginTheme(realm, DASHBOARD_CLIENT_ID, theme));
    }

    private void createDashboardClient(String realm) {
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
