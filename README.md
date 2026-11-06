# Orgkeep core

Orgkeep makes multi-tenant B2B on Keycloak simple. A *realm owner* (a B2B company) creates one **organisation** per
customer and names its **org admins**, who invite their users and give them access through **teams**. The realm owner
sells **products** from **catalogs**, with features and quotas, and the tokens of the users carry what their
organisation bought, in the standard `resource_access` claim plus a claim per product.

The core is the REST API that does all this on top of Keycloak 26 (its native Organizations included). It owns the
integrity of what it writes in Keycloak and needs no database of its own: everything is stored in Keycloak.

- **Core alone** (this repository, image `ghcr.io/orgkeep/orgkeep-core`): integrate Orgkeep through its REST API,
  with your own screens and tools.
- **Core + dashboard** ([orgkeep-dashboard](https://github.com/orgkeep/orgkeep-dashboard), image
  `ghcr.io/orgkeep/orgkeep`): the same API, plus a web UI for the realm owner and the org admins.

To try it without building anything: the demo stack of
[orgkeep-examples](https://github.com/orgkeep/orgkeep-examples) (`docker compose up`).

## Requirements

- Keycloak 26 (tested with 26.6), and the credentials of an admin of its `master` realm;
- to build: Java 25 and Maven 3.9; Docker, for the dev mode and the tests (Keycloak is started by Quarkus Dev
  Services).

## Run it in dev mode

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

The dev mode mounts the login theme from [orgkeep-theme](https://github.com/orgkeep/orgkeep-theme), cloned next to
this repository (`../orgkeep-theme`).

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

Environment variables (or the matching properties in `application.properties`, `orgkeep.realm-name` for
`ORGKEEP_REALM_NAME`...).

### Keycloak

| Variable | Default | What |
|---|---|---|
| `KEYCLOAK_URL` | **required** | Base URL of Keycloak as Orgkeep reaches it, without `/realms/...` (`http://keycloak:8080` inside Docker). Used for the admin API and to verify the tokens |
| `KEYCLOAK_ADMIN_USER` | `admin` | Admin of the `master` realm, through the client `admin-cli` |
| `KEYCLOAK_ADMIN_PASSWORD` | `admin` | Its password |
| `ORGKEEP_KEYCLOAK_PUBLIC_URL` | the base of `KEYCLOAK_URL` | Keycloak as the browsers see it (`https://login.example.com`): the issuer of the tokens and the console link shown to the realm owner. Set it when `KEYCLOAK_URL` is an internal URL |

### Realm and its owner

| Variable | Default | What |
|---|---|---|
| `ORGKEEP_REALM_NAME` | **required** | The realm managed by Orgkeep, created at the first startup. The API only accepts tokens of this realm |
| `ORGKEEP_OWNER_EMAIL` | **required** | The initial realm owner |
| `ORGKEEP_OWNER_PASSWORD` | **required** | Its initial password |
| `ORGKEEP_OWNER_USERNAME` | the email | Its username |
| `ORGKEEP_OWNER_FIRST_NAME` / `ORGKEEP_OWNER_LAST_NAME` | **required** when the realm is created | Keycloak asks for them at the first login otherwise |
| `ORGKEEP_OWNER_TEMPORARY_PASSWORD` | `true` | The owner must change the password at the first login |
| `ORGKEEP_MODE` | `on-prem` | `on-prem`: the realm owner also gets the Keycloak admin console of the realm. `saas` is reserved for the hosted offer |

### Emails

Invitations are sent by Keycloak, through the SMTP server of the realm. When `ORGKEEP_SMTP_HOST` is set, Orgkeep
writes these settings on the realm at every startup; otherwise the realm keeps its own (set in the Keycloak console).

| Variable | Default | What |
|---|---|---|
| `ORGKEEP_SMTP_HOST` | none | SMTP server |
| `ORGKEEP_SMTP_PORT` | `25` | |
| `ORGKEEP_SMTP_FROM` | **required** with a host | Sender address |
| `ORGKEEP_SMTP_FROM_DISPLAY_NAME` | none | Sender name |
| `ORGKEEP_SMTP_USERNAME` / `ORGKEEP_SMTP_PASSWORD` | none | Authentication, enabled when the username is set |
| `ORGKEEP_SMTP_STARTTLS` / `ORGKEEP_SMTP_SSL` | `false` | |
| `ORGKEEP_INVITATION_LINK_LIFESPAN` | `72h` | Validity of the link of the invitation email |

### Dashboard client

Read by the core even without the dashboard, since the core manages the realm.

| Variable | Default | What |
|---|---|---|
| `ORGKEEP_DASHBOARD_CLIENT_ID` | `orgkeep-dashboard` | The Keycloak client of the dashboard |
| `ORGKEEP_DASHBOARD_LOGIN_THEME` | none | Login theme set on the dashboard client only (`orgkeep`, see orgkeep-theme). The users of the organisations keep the theme of the realm |

### API documentation and demo

| Variable | Default | What |
|---|---|---|
| `QUARKUS_SWAGGER_UI_ENABLE` | `false` | Swagger UI on `/q/swagger-ui` (always in the image, off by default) |
| `ORGKEEP_SWAGGER_UI_LOGIN` | `false` | Login with a username and a password in Swagger UI, instead of pasting a token |
| `ORGKEEP_SWAGGER_UI_AUTH_SERVER_URL` | the realm under `ORGKEEP_KEYCLOAK_PUBLIC_URL` | The realm as the browser sees it, for this login |
| `ORGKEEP_DEMO_ENABLED` | `false` | Creates the demo organisation, accounts and catalogs (fixed passwords: never in production) |

Any other [Quarkus setting](https://quarkus.io/guides/all-config) works the same way (`QUARKUS_LOG_LEVEL`...).

## The API

Everything lives under `/api`, for two audiences:

- `/api/admin/...`: the realm owner (realm role `realm-owner`): organisations, catalogs, products, assignments of
  products to organisations, catalog roles, login client;
- `/api/organisations/{org}/...`: the org admins of `{org}` (and the realm owner): members, teams, what the teams give.

Callers send an access token of the realm (`Authorization: Bearer ...`). The full contract is on `/q/openapi`, and in
Swagger UI when enabled.

## Contributing

Contributions are welcome: see [CONTRIBUTING.md](CONTRIBUTING.md). Commits are signed off (DCO), no contributor
license agreement.

## License

[Apache License 2.0](LICENSE).
