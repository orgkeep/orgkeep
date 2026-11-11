Orgkeep makes multi-tenant B2B on Keycloak simple: one organisation per customer, org admins who manage their own
users and teams, and a catalog of products with features and quotas, in the tokens your APIs already read.

> **Early preview (0.x)**: ready to be tried, not yet to run in production. No upgrade path between 0.x versions:
> a realm may have to be created again. Known limitations in the [README](https://github.com/orgkeep/orgkeep#readme).

### Try it

The demo stack runs Keycloak, PostgreSQL, Orgkeep, Mailpit and example APIs, with demo data:

```
git clone https://github.com/orgkeep/orgkeep-examples.git
cd orgkeep-examples/docker-compose
ORGKEEP_VERSION={{version}} docker compose up -d --build
```

Then open http://localhost:8284 and log in as `realm-owner` / `password`. The
[demo guide](https://github.com/orgkeep/orgkeep-examples/tree/main/docker-compose) lists every account.

### Images

For `linux/amd64` and `linux/arm64`, on the GitHub Container Registry:

| Image | What |
|---|---|
| `ghcr.io/orgkeep/orgkeep:{{version}}` | The REST API and the dashboard, one application ([configuration](https://github.com/orgkeep/orgkeep/tree/v{{version}}/orgkeep-dashboard#configuration)) |
| `ghcr.io/orgkeep/orgkeep-core:{{version}}` | The REST API alone, to build your own screens ([configuration](https://github.com/orgkeep/orgkeep/tree/v{{version}}/orgkeep-core#configuration)) |
| `ghcr.io/orgkeep/orgkeep-keycloak:{{version}}` | Keycloak with the Orgkeep theme |

### Keycloak theme

`orgkeep-theme.jar` below, for `/opt/keycloak/providers/`: login page, emails and account console in the Orgkeep
look. See the [README of the theme](https://github.com/orgkeep/orgkeep/tree/v{{version}}/orgkeep-theme).
