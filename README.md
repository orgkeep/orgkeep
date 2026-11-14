# Orgkeep

![Build](https://github.com/orgkeep/orgkeep/actions/workflows/build.yml/badge.svg)
![License](https://img.shields.io/badge/license-Apache%202.0-blue)
![Status](https://img.shields.io/badge/status-early%20preview-yellow)
![Java](https://img.shields.io/badge/Java-25-orange)

Orgkeep makes multi-tenant B2B on Keycloak simple. A *realm owner* (a B2B company) creates one **organisation** per
customer and names its **org admins**, who invite their users and give them access through **teams**. The realm owner
sells **products** from **catalogs**, with features and quotas, and the tokens of the users carry what their
organisation bought, in the standard `resource_access` claim plus a claim per product.

Orgkeep runs on top of Keycloak 26 (its native Organizations included). It owns the integrity of what it writes in
Keycloak and needs no database of its own: everything is stored in Keycloak.

To try it without building anything: the demo stack of
[orgkeep-examples](https://github.com/orgkeep/orgkeep-examples) (`docker compose up`, then http://localhost:8284).

> **Status: early preview (0.x).** Orgkeep is ready to be tried, not yet to run in production. Feedback is welcome
> in the [issues](https://github.com/orgkeep/orgkeep/issues).
> - No upgrade path between 0.x versions: a change of format applies to new objects only, and a realm may have to
>   be created again.
> - Known limitations: no service accounts yet (machine-to-machine access for the organisations); Orgkeep calls
>   Keycloak with an admin account of the `master` realm; outside the demo, the `orgkeep-dashboard` client is
>   created by hand in Keycloak; one realm per Orgkeep instance.

## What is in this repository

Three modules, released together with one version:

| Module | What | Image |
|---|---|---|
| [`orgkeep-core`](orgkeep-core) | The REST API: integrate Orgkeep with your own screens and tools | `ghcr.io/orgkeep/orgkeep-core` |
| [`orgkeep-dashboard`](orgkeep-dashboard) | The same API plus a web UI for the realm owner and the org admins: one application | `ghcr.io/orgkeep/orgkeep` |
| [`orgkeep-theme`](orgkeep-theme) | A Keycloak theme in the look of Orgkeep (login page of the dashboard, emails, account console) | `ghcr.io/orgkeep/orgkeep-keycloak` (Keycloak with the theme) |

How to run and configure each one is in its README.

## Build

Java 25, Maven 3.9, and Docker for the tests (Keycloak is started by Quarkus Dev Services). Node.js is downloaded by
the build of the dashboard, nothing to install.

```
mvn verify                       # every module, with the tests
mvn verify -pl orgkeep-core      # the core alone (no Node.js)
mvn package -Pdocker             # also the images of the core and of the dashboard
```

## Contributing

Contributions are welcome: see [CONTRIBUTING.md](CONTRIBUTING.md). Commits are signed off (DCO), no contributor
license agreement.

## License

[Apache License 2.0](LICENSE).
