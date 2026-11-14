---
title: Configuration
description: Every setting of the Orgkeep core and dashboard, with its environment variable and default.
sidebar:
  order: 1
---

Orgkeep is configured with environment variables (or the matching properties in `application.properties`:
`orgkeep.realm-name` for `ORGKEEP_REALM_NAME`...). The core settings apply to both images, `ghcr.io/orgkeep/orgkeep-core`
(the API alone) and `ghcr.io/orgkeep/orgkeep` (the core and the dashboard); the dashboard settings only to the second.

The application listens on port **8282** (core) or **8284** (core + dashboard): `QUARKUS_HTTP_PORT` changes it.

At the first startup, Orgkeep creates the realm, its owner (who changes the password at the first login), the login
client of the realm and the default catalog `products`. At every startup, it adds what is missing, so an existing realm
is completed, never reset.

## Keycloak

| Variable | Default | What |
|---|---|---|
| `KEYCLOAK_URL` | **required** | Base URL of Keycloak as Orgkeep reaches it, without `/realms/...` (`http://keycloak:8080` inside Docker). Used for the admin API and to verify the tokens |
| `KEYCLOAK_ADMIN_USER` | `admin` | Admin of the `master` realm, through the client `admin-cli` |
| `KEYCLOAK_ADMIN_PASSWORD` | `admin` | Its password |
| `ORGKEEP_KEYCLOAK_PUBLIC_URL` | the base of `KEYCLOAK_URL` | Keycloak as the browsers see it (`https://login.example.com`): the issuer of the tokens and the console link shown to the realm owner. Set it when `KEYCLOAK_URL` is an internal URL |

## Realm and its owner

| Variable | Default | What |
|---|---|---|
| `ORGKEEP_REALM_NAME` | **required** | The realm managed by Orgkeep, created at the first startup. The API only accepts tokens of this realm |
| `ORGKEEP_OWNER_EMAIL` | **required** | The initial realm owner |
| `ORGKEEP_OWNER_PASSWORD` | **required** | Its initial password |
| `ORGKEEP_OWNER_USERNAME` | the email | Its username |
| `ORGKEEP_OWNER_FIRST_NAME` / `ORGKEEP_OWNER_LAST_NAME` | **required** when the realm is created | Keycloak asks for them at the first login otherwise |
| `ORGKEEP_OWNER_TEMPORARY_PASSWORD` | `true` | The owner must change the password at the first login |
| `ORGKEEP_MODE` | `on-prem` | `on-prem`: the realm owner also gets the Keycloak admin console of the realm. `saas` is reserved for the hosted offer |

## Emails

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
| `ORGKEEP_LOCKS_MAX_WAIT` | `2s` | How long a short write (adding or removing a member, creating an organisation) waits for a change in progress on the same organisation before answering `409`. Enabling, disabling and deleting an organisation never wait |

## Dashboard client

Read by the core even without the dashboard, since the core manages the realm.

| Variable | Default | What |
|---|---|---|
| `ORGKEEP_DASHBOARD_CLIENT_ID` | `orgkeep-dashboard` | The Keycloak client of the dashboard |
| `ORGKEEP_DASHBOARD_LOGIN_THEME` | none | Login theme set on the dashboard client only (`orgkeep` for the Orgkeep look: the theme must be in Keycloak, see the `orgkeep-theme` module). The users of the organisations keep the theme of the realm |

## API documentation and demo

| Variable | Default | What |
|---|---|---|
| `QUARKUS_SWAGGER_UI_ENABLE` | `false` | Swagger UI on `/q/swagger-ui` (always in the image, off by default) |
| `ORGKEEP_SWAGGER_UI_LOGIN` | `false` | Login with a username and a password in Swagger UI, instead of pasting a token |
| `ORGKEEP_SWAGGER_UI_AUTH_SERVER_URL` | the realm under `ORGKEEP_KEYCLOAK_PUBLIC_URL` | The realm as the browser sees it, for this login |
| `ORGKEEP_DEMO_ENABLED` | `false` | Creates the demo organisation, accounts and catalogs (fixed passwords: never in production) |

Any other [Quarkus setting](https://quarkus.io/guides/all-config) works the same way (`QUARKUS_LOG_LEVEL`...).

## Dashboard (image `ghcr.io/orgkeep/orgkeep`)

On top of everything above, the dashboard needs the Keycloak client the browser logs in with:

| Variable | Default | What |
|---|---|---|
| `ORGKEEP_DASHBOARD_CLIENT_SECRET` | **required** | The secret of the dashboard client |
| `QUARKUS_OIDC_DASHBOARD_CLIENT_ID` | `orgkeep-dashboard` | Its clientId. When changed, set `ORGKEEP_DASHBOARD_CLIENT_ID` (read by the core) to the same value |
| `ORGKEEP_DASHBOARD_LOGIN_THEME` | none | `orgkeep` for the login page of the dashboard in the Orgkeep look (the theme must be in Keycloak) |

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
