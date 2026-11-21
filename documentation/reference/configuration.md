---
title: Configuration
description: Every setting of the Orgkeep core and dashboard, with its environment variable and default.
sidebar:
  order: 10
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
| `ORGKEEP_KEYCLOAK_CLIENT_SECRET` | **required** | Secret of the client `orgkeep-admin` of the realm, whose service account Orgkeep calls the Keycloak admin API with. Given to the client when Orgkeep creates the realm |
| `ORGKEEP_KEYCLOAK_CLIENT_ID` | `orgkeep-admin` | The id of that client |
| `KEYCLOAK_ADMIN_USER` / `KEYCLOAK_ADMIN_PASSWORD` | none | An admin of the `master` realm, **for the first startup only**: Orgkeep uses it to create the realm and its client, nothing else. Remove them afterwards |
| `ORGKEEP_KEYCLOAK_PUBLIC_URL` | the base of `KEYCLOAK_URL` | Keycloak as the browsers see it (`https://login.example.com`): the issuer of the tokens and the console link shown to the realm owner. Set it when `KEYCLOAK_URL` is an internal URL |

### How Orgkeep calls Keycloak

At runtime, Orgkeep calls the Keycloak admin API as the service account of the client `orgkeep-admin` of **its own
realm**, with the role `realm-management/realm-admin` of that realm only: a leak of its secret exposes that realm, not
the whole Keycloak. The credentials of the `master` realm are never kept.

- **First startup**: the realm does not exist yet. With `KEYCLOAK_ADMIN_USER` and `KEYCLOAK_ADMIN_PASSWORD`, Orgkeep
  creates, in one call to Keycloak, the realm, the client `orgkeep-admin` (with the secret `ORGKEEP_KEYCLOAK_CLIENT_SECRET`)
  and its service account, then does everything else as that service account. Remove the two variables afterwards.
- **Every other startup**: Orgkeep asks Keycloak for a token of its own client and never contacts `master`. If
  `KEYCLOAK_ADMIN_USER` / `KEYCLOAK_ADMIN_PASSWORD` are still set, it logs a warning (they are neither used nor tested).
- At the first startup, the log may show the error "OIDC Server is not available" once: the OIDC extension looks for
  the realm before Orgkeep has created it, and recovers on its own.

Do not change `orgkeep-admin` in the Keycloak console: a realm owner can read or regenerate its secret, disable or delete
the client, or turn its *Full scope allowed* off, and Orgkeep then fails at the next call or startup. The startup errors
say what to check:

| Message | Cause |
|---|---|
| `Realm '...' not found at ...` | The realm does not exist and `KEYCLOAK_ADMIN_USER` / `KEYCLOAK_ADMIN_PASSWORD` are not set. Set them for the first startup, and check `KEYCLOAK_URL` and `ORGKEEP_REALM_NAME` |
| `Keycloak refused the client '...'` | Wrong `ORGKEEP_KEYCLOAK_CLIENT_SECRET`, or the client was deleted or disabled (see Clients > `orgkeep-admin` > Credentials; the events of the realm tell which). A deleted client is created again: confidential, service account enabled, *Full scope allowed* on, service account role `realm-management/realm-admin` |
| `The client '...' lacks rights on the admin API` | The service account lost `realm-management/realm-admin`, or *Full scope allowed* is off (Clients > `orgkeep-admin` > Client scopes > Dedicated scope) |
| `Keycloak refused KEYCLOAK_ADMIN_USER / KEYCLOAK_ADMIN_PASSWORD` | The admin of `master` was refused at the first startup |

A realm created by a version before this one has no `orgkeep-admin` client (there is no upgrade path in 0.x): create
the realm again, or create the client by hand as above, with the secret you set in `ORGKEEP_KEYCLOAK_CLIENT_SECRET`.
If Orgkeep stops between the creation of the realm and the end of the startup, the realm may lack its default
catalog: delete the realm and start again.

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
writes these settings on the realm at every startup; otherwise Orgkeep starts normally and the realm keeps its own (set in the Keycloak console). With a host, the sender
address is required: Orgkeep refuses to start without `ORGKEEP_SMTP_FROM`.

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

On top of everything above, the dashboard needs its public URL. Orgkeep creates the Keycloak client the browser logs in
with (`orgkeep-dashboard`) and repairs it at each startup, like the login client of the realm:

| Variable | Default | What |
|---|---|---|
| `ORGKEEP_DASHBOARD_URL` | **required** | The public URL of the dashboard, an origin: `https://dashboard.example.com` (`http` on `localhost` only), without path. The client's redirect URI is this URL followed by `/*`. The image does not start without it |
| `ORGKEEP_DASHBOARD_CLIENT_ID` | `orgkeep-dashboard` | The client id. A client left behind by a changed id is neither deleted nor touched |
| `ORGKEEP_DASHBOARD_LOGIN_THEME` | none | `orgkeep` for the login page of the dashboard in the Orgkeep look (the theme must be in Keycloak) |

### The dashboard client in Keycloak

Nothing to create, and no secret to configure: Keycloak generates the secret when Orgkeep creates the client, and the
dashboard reads it through Orgkeep. At each startup Orgkeep rewrites what it owns on the client (confidential, standard
flow only, redirect URI, post logout redirect URIs `+`, PKCE `S256`, login theme): the configuration wins over a change
made in the Keycloak console. The secret is never changed by a startup, as the session cookies of the users depend on it.

To rotate the secret, click **Regenerate** in the Keycloak console (Clients > `orgkeep-dashboard` > Credentials), then
restart Orgkeep: until then, logins to the dashboard fail. A client deleted in the console is created again, with a new
secret, at the next startup (the users have to log in again).

Example:

```
docker run -p 8284:8284 \
  -e KEYCLOAK_URL=https://keycloak.example.com \
  -e KEYCLOAK_ADMIN_USER=admin -e KEYCLOAK_ADMIN_PASSWORD=... \
  -e ORGKEEP_KEYCLOAK_CLIENT_SECRET=... \
  -e ORGKEEP_REALM_NAME=acme \
  -e ORGKEEP_OWNER_EMAIL=owner@acme.com -e ORGKEEP_OWNER_PASSWORD=... \
  -e ORGKEEP_OWNER_FIRST_NAME=Alice -e ORGKEEP_OWNER_LAST_NAME=Martin \
  -e ORGKEEP_DASHBOARD_URL=https://dashboard.example.com \
  ghcr.io/orgkeep/orgkeep:0.1.0
```

`KEYCLOAK_ADMIN_USER` and `KEYCLOAK_ADMIN_PASSWORD` are only for the first startup: remove them afterwards.
