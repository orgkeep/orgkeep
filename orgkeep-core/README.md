# Orgkeep core

Orgkeep makes multi-tenant B2B on Keycloak simple. A *realm owner* (a B2B company) creates one **organisation** per
customer and names its **org admins**, who invite their users and give them access through **teams**. The realm owner
sells **products** from **catalogs**, with features and quotas, and the tokens of the users carry what their
organisation bought, in the standard `resource_access` claim plus a claim per product.

The core is the REST API that does all this on top of Keycloak 26 (its native Organizations included). It owns the
integrity of what it writes in Keycloak and needs no database of its own: everything is stored in Keycloak.

- **Core alone** (this module, image `ghcr.io/orgkeep/orgkeep-core`): integrate Orgkeep through its REST API,
  with your own screens and tools.
- **Core + dashboard** ([orgkeep-dashboard](../orgkeep-dashboard), image `ghcr.io/orgkeep/orgkeep`): the same API,
  plus a web UI for the realm owner and the org admins.

To try it without building anything: the demo stack of
[orgkeep-examples](https://github.com/orgkeep/orgkeep-examples) (`docker compose up`).

> **Status: early preview (0.x).** Orgkeep is ready to be tried, not yet to run in production. Feedback is welcome
> in the [issues](https://github.com/orgkeep/orgkeep/issues).
> - No upgrade path between 0.x versions: a change of format applies to new objects only, and a realm may have to
>   be created again.
> - Known limitations: Orgkeep calls
>   Keycloak with an admin account of the `master` realm; outside the demo, the `orgkeep-dashboard` client is
>   created by hand in Keycloak; one realm per Orgkeep instance.

## Requirements

- Keycloak 26 (tested with 26.6), and the credentials of an admin of its `master` realm;
- to build: Java 25 and Maven 3.9; Docker, for the dev mode and the tests (Keycloak is started by Quarkus Dev
  Services).

## Run it in dev mode

From this directory:

```
mvn quarkus:dev
```

- API on http://localhost:8282/api, Swagger UI on http://localhost:8282/q/swagger-ui (login with a username and a
  password);
- Keycloak on http://localhost:8280 (admin console `admin` / `admin`), started by Dev Services, with the realm `demo`
  created by Orgkeep at startup;
- Mailpit on http://localhost:8025, which catches the emails of the realm (invitations).

Demo accounts, all with the password `password`: `realm-owner` (the realm owner), `org-owner` (owner of the
organisation `acme`), `org-user` (a member of `acme`), `paul` and `emma` (members of teams of `acme` with products in
the catalogs `prod` and `sandbox`). Ready-made requests in [`src/test/http/demo.http`](src/test/http/demo.http).

The dev mode mounts the login theme from its sources, in the module [orgkeep-theme](../orgkeep-theme).

Tests: `mvn test` (they start their own Keycloak and work in throwaway realms).

## Run it with Docker

Build the image (a Docker daemon is needed):

```
mvn package -Pdocker        # ghcr.io/orgkeep/orgkeep-core:<version>
```

Run it against your Keycloak:

```
docker run -p 8282:8282 \
  -e KEYCLOAK_URL=https://keycloak.example.com \
  -e KEYCLOAK_ADMIN_USER=admin -e KEYCLOAK_ADMIN_PASSWORD=... \
  -e ORGKEEP_REALM_NAME=acme \
  -e ORGKEEP_OWNER_EMAIL=owner@acme.com -e ORGKEEP_OWNER_PASSWORD=... \
  -e ORGKEEP_OWNER_FIRST_NAME=Alice -e ORGKEEP_OWNER_LAST_NAME=Martin \
  ghcr.io/orgkeep/orgkeep-core:0.1.0
```

The application listens on port **8282** (`QUARKUS_HTTP_PORT` to change it). At the first startup, it creates the
realm, its owner (who changes the password at the first login), the login client of the realm and the default
catalog `products`. At every startup, it adds what is missing, so an existing realm is completed, never reset.

## Configuration

Orgkeep is configured with environment variables (or the matching properties in `application.properties`,
`orgkeep.realm-name` for `ORGKEEP_REALM_NAME`...). Four are required: `KEYCLOAK_URL`, `ORGKEEP_REALM_NAME`,
`ORGKEEP_OWNER_EMAIL` and `ORGKEEP_OWNER_PASSWORD` (plus the first and last name of the owner when the realm is
created). The Keycloak admin account, the SMTP server, the dashboard client, Swagger UI and the demo data have their
own settings.

The complete reference, with every variable and its default, is in the documentation:
https://orgkeep.org/docs/latest/reference/configuration/ (its source is [`documentation/`](../documentation)).

## The API

Everything lives under `/api`, for two audiences:

- `/api/admin/...`: the realm owner (realm role `realm-owner`): organisations, catalogs, products, assignments of
  products to organisations, catalog roles, login client;
- `/api/organisations/{org}/...`: the org admins of `{org}` (and the realm owner): members, teams, what the teams give,
  service accounts.

Callers send an access token of the realm (`Authorization: Bearer ...`). The full contract is on `/q/openapi`, and in
Swagger UI when enabled.

### Service accounts

An organisation gets machine-to-machine access to your APIs with **service accounts**: the org admins create them
(`/api/organisations/{org}/service-accounts`), and each is an OAuth2 client with the client credentials grant, named
`org-<org>.<name>`. The secret is shown once, at creation and at each rotation. A service account gets its products the
way a person does, by being put in a **team** (`PUT /api/organisations/{org}/user-groups/{team}/service-accounts/{name}`),
and its token carries the same catalog claims as a person's, plus `organization: ["<org>"]`. The realm owner can also
give it a reserved catalog role directly. A service account never carries an org role: it cannot call this API.

Disabling a service account (or its organisation) through Orgkeep stops new tokens, closes its sessions and sets the
"not before" time of its user: an introspection answers `active: false` for every token issued before, for good (also
after enabling it again). An API that only checks the signature of a JWT still accepts it until it expires. For a fast
cut-off, introspect the tokens or keep them short.

**Teams share their pools.** All the members of a team, people and service accounts alike, draw on the same quotas of
the team, and everybody in the organisation draws on the pool of the organisation. A team that mixes people and
service accounts is allowed, but people can then use up its quota and block an application. The pattern to follow:
**one team per application**, and a **team quota on the teams of people**. A team quota is a cap, so it leaves room for
the application. For example, with an organisation quota of 1000 and a team of people capped at 700, the team of the
application, without a team quota, always keeps at least 300. (Separating people and applications into different teams
does not by itself protect the application: they still share the pool of the organisation.)

## Contributing

Contributions are welcome: see [CONTRIBUTING.md](../CONTRIBUTING.md). Commits are signed off (DCO), no contributor
license agreement.

## License

[Apache License 2.0](../LICENSE).
