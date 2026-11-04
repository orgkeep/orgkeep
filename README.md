# Orgkeep theme

A Keycloak 26 theme in the look of [Orgkeep](https://github.com/orgkeep/orgkeep-core), named `orgkeep`, with three
types:

| Type | Where Keycloak takes it from | What Orgkeep does with it |
|---|---|---|
| `login` | the theme of the client of the request, else the one of the realm | set on the dashboard client only, with `ORGKEEP_DASHBOARD_LOGIN_THEME=orgkeep` |
| `email` | the email theme of the realm | nothing: an example for a realm owner |
| `account` | the account theme of the realm | nothing: an example for a realm owner |

Orgkeep never sets a theme on the realm: the users of the organisations see the brand of the realm owner (Keycloak's
by default), only the dashboard shows Orgkeep. A realm owner who wants the email and account types picks them in the
Keycloak console (**Realm settings → Themes**).

Everything inherits from the default themes of Keycloak 26 and only changes the look (CSS, logo, email layout): no
template of the login flows is copied.

## Install

Three forms, the same content:

- **Docker image** `ghcr.io/orgkeep/orgkeep-keycloak`: the official Keycloak image with the theme added, configured
  like the official one (used by the demo stack of [orgkeep-examples](https://github.com/orgkeep/orgkeep-examples)):

  ```
  docker build -t ghcr.io/orgkeep/orgkeep-keycloak:0.1.0-SNAPSHOT .
  docker build --build-arg KEYCLOAK_VERSION=26.6.1 -t ... .     # another Keycloak version
  ```

- **Folder**: copy or mount [`src/main/resources/theme/orgkeep`](src/main/resources/theme/orgkeep) in
  `/opt/keycloak/themes/orgkeep`. No `kc.sh build` needed;
- **Jar**: `mvn package` gives `target/orgkeep-theme.jar`, to put in `/opt/keycloak/providers/`. Keycloak needs a
  build afterwards (`kc.sh build`, or `start` without `--optimized`, which builds by itself).

Then, in Orgkeep: `ORGKEEP_DASHBOARD_LOGIN_THEME=orgkeep`.

## Work on it

`mvn quarkus:dev` in orgkeep-core or orgkeep-dashboard, cloned next to this repository, mounts these sources in the
Keycloak of the dev mode, without any cache: a change of CSS or template shows at the next page load
(http://localhost:8284 for the login page of the dashboard). To see the emails and the account console, choose the
theme `orgkeep` on the realm `demo` in the Keycloak console; the emails arrive in Mailpit (http://localhost:8025).

## License

[Apache License 2.0](LICENSE).
