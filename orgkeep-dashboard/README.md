# Orgkeep dashboard

The web UI of [Orgkeep](../README.md), for the realm owner (organisations, catalogs,
products, login client, token previews) and the org admins (members and teams of their organisation).

The dashboard embeds the core: **one application**, the REST API of the core under `/api` plus the front (React,
served by Quarkus), so a single image to deploy, `ghcr.io/orgkeep/orgkeep`. The browser logs in through Keycloak and
keeps an encrypted session cookie: the front never sees a token. Applications keep calling the same API with bearer
tokens, as with the core alone.

To try it without building anything: the demo stack of
[orgkeep-examples](https://github.com/orgkeep/orgkeep-examples) (`docker compose up`, then http://localhost:8284).

> **Status: early preview (0.x).** Orgkeep is ready to be tried, not yet to run in production. Feedback is welcome
> in the [issues](https://github.com/orgkeep/orgkeep/issues). No upgrade path between 0.x versions, and known
> limitations (no service accounts yet, among others): see the [status](../README.md).

## Requirements

- Java 25, Maven 3.9, and Docker (Keycloak is started by Quarkus Dev Services in dev mode and in the tests);
- Node.js is downloaded by the build (Quinoa), nothing to install.

## Run it in dev mode

The dev mode starts from this directory, and Maven takes the core from the local Maven repository: install it first,
from the root of the repository (again after a change of its dependencies or of the parent pom):

```
mvn install -DskipTests -pl orgkeep-core -am
```

Then, from this directory:

```
mvn quarkus:dev
```

Open http://localhost:8284 and log in as `realm-owner` / `password` (or `org-owner`, `org-user`: see the demo
accounts in the [README of the core](../orgkeep-core/README.md)). The front has hot reload (the Vite dev server runs behind Quarkus), the Java
code live reload. Keycloak runs on http://localhost:8280 and Mailpit on http://localhost:8025, as with the core.

Tests: `mvn test`.

## Run it with Docker

From the root of the repository, which builds the core first:

```
mvn package -Pdocker -pl orgkeep-dashboard -am        # ghcr.io/orgkeep/orgkeep:<version>
```

The application listens on port **8284** (`QUARKUS_HTTP_PORT` to change it): the dashboard on `/`, the API on
`/api`, Swagger UI on `/q/swagger-ui` when enabled.

## Configuration

Everything of the core (see [its configuration](../orgkeep-core/README.md#configuration): Keycloak,
realm, owner, emails...), plus the Keycloak client the browser logs in with:

| Variable | Default | What |
|---|---|---|
| `ORGKEEP_DASHBOARD_CLIENT_SECRET` | **required** | The secret of the dashboard client |
| `QUARKUS_OIDC_DASHBOARD_CLIENT_ID` | `orgkeep-dashboard` | Its clientId. When changed, set `ORGKEEP_DASHBOARD_CLIENT_ID` (read by the core) to the same value |
| `ORGKEEP_DASHBOARD_LOGIN_THEME` | none | `orgkeep` for the login page of the dashboard in the Orgkeep look (the theme must be in Keycloak, see [orgkeep-theme](../orgkeep-theme)) |

### The dashboard client in Keycloak

Create it in the realm managed by Orgkeep (the demo, `ORGKEEP_DEMO_ENABLED=true`, creates it for `localhost:8284`):

- Client ID `orgkeep-dashboard`, **Client authentication on** (a confidential client: the secret stays on the
  server), **Standard flow** only;
- Valid redirect URIs: `https://dashboard.example.com/*`, the public URL of the dashboard;
- Valid post logout redirect URIs: `+` (the same as the redirect URIs);
- then copy its secret (tab **Credentials**) into `ORGKEEP_DASHBOARD_CLIENT_SECRET`.

Example:

```
docker run -p 8284:8284 \
  -e KEYCLOAK_URL=https://keycloak.example.com \
  -e KEYCLOAK_ADMIN_USER=admin -e KEYCLOAK_ADMIN_PASSWORD=... \
  -e ORGKEEP_REALM_NAME=acme \
  -e ORGKEEP_OWNER_EMAIL=owner@acme.com -e ORGKEEP_OWNER_PASSWORD=... \
  -e ORGKEEP_OWNER_FIRST_NAME=Alice -e ORGKEEP_OWNER_LAST_NAME=Martin \
  -e ORGKEEP_DASHBOARD_CLIENT_SECRET=... \
  ghcr.io/orgkeep/orgkeep:0.1.0
```

On the first startup the realm does not exist yet, so neither does the client: start Orgkeep once with any value in
`ORGKEEP_DASHBOARD_CLIENT_SECRET` (it is only used at login), create the client, then restart Orgkeep with its real
secret.

## Contributing

Contributions are welcome: see [CONTRIBUTING.md](../CONTRIBUTING.md). Commits are signed off (DCO), no contributor
license agreement.

## License

[Apache License 2.0](../LICENSE).
