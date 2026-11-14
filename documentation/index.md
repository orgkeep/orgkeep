---
title: Orgkeep documentation
description: What Orgkeep is, and where to start.
sidebar:
  order: 1
---

Orgkeep makes multi-tenant B2B on Keycloak simple. A *realm owner* (a B2B company) creates one **organisation** per
customer and names its **org admins**, who invite their users and give them access through **teams**. The realm owner
sells **products** from **catalogs**, with features and quotas, and the tokens of the users carry what their
organisation bought, in the standard `resource_access` claim plus a claim per product.

Orgkeep runs on top of Keycloak 26 (its native Organizations included). It owns the integrity of what it writes in
Keycloak and needs no database of its own: everything is stored in Keycloak.

There are two ways to use it:

- **Core alone** (image `ghcr.io/orgkeep/orgkeep-core`): integrate Orgkeep through its REST API, with your own screens
  and tools.
- **Core + dashboard** (image `ghcr.io/orgkeep/orgkeep`): the same API, plus a web UI for the realm owner and the org
  admins.

:::note[Early preview (0.x)]
Orgkeep is ready to be tried, not yet to run in production. There is no upgrade path between 0.x versions: a change of
format applies to new objects only, and a realm may have to be created again. Feedback is welcome in the
[issues](https://github.com/orgkeep/orgkeep/issues).
:::

## Where to start

- [Getting started](getting-started/): run the demo stack and look around, in a few minutes.
- [Configuration](reference/configuration/): every setting of the core and of the dashboard.

More is coming: guides for the realm owner, the org admins, the integrators of the APIs and the operators, then the
concepts behind the model.

## Links

- Source code and issues: [github.com/orgkeep/orgkeep](https://github.com/orgkeep/orgkeep)
- The roadmap: [Orgkeep roadmap](https://github.com/orgs/orgkeep/projects/3)
