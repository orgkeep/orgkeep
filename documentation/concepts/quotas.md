---
title: Quotas and pools
description: How quotas work in Orgkeep, what a pool is, and how an API of the realm owner should count against the pools of a token.
sidebar:
  order: 8
---

**Orgkeep does not count usage.** It does not know how many times a user called an API. What it does is put in the
token the **limits** that apply to the user, and the **names of the counters** those limits belong to. The API of the
realm owner keeps its own counters, and enforces the limits.

This page explains what is in a token and how an API should use it. The exact JSON is in [The token](../token/).

## Pools

A quota is not "200 per user": it is the ceiling of a **shared counter**, called a **pool**. There are two kinds:

- the **pool of the organisation**: the quota negotiated by the realm owner (see
  [Catalogs, products and features](../catalogs/#assigning-a-product-to-an-organisation)). Everyone of the
  organisation consumes it;
- the **pool of a team**: the optional quota set by an org admin on a team (see [Teams](../teams/#the-quota-of-a-team)).
  The members of the team consume it, as well as the pool of the organisation. A team quota of 50 is one member using
  50, or the members together using 50.

Each pool in a token has three fields:

```json
{ "pool": "/sales", "limit": 50, "cycle": "daily" }
```

- `pool`: the name of the pool, **relative to the organisation**. `/` is the root of the organisation, that is its own
  pool, and `/sales` is the pool of the team `sales`. The name of the organisation is not repeated;
- `limit`: the ceiling, a positive integer;
- `cycle`: `daily`, `monthly` or `yearly`. Each pool has its own cycle. The one of a team is chosen by the org admin,
  by default the cycle of the organisation for this product.

The pool name of a team is stable: a team deleted then created again with the same name gives the same pool `/sales`,
hence the same counter on the side of the API. This is intended.

## Where the pools are in the token

What is counted depends on the kind of product (see [Catalogs, products and features](../catalogs/#simple-and-metered-products)):

| Product | Claim with the pools | Pools |
|---|---|---|
| Simple | `<catalog>.<product>`, e.g. `prod.billing` | the pool of the organisation, plus the pool of each team of the user that gives the product **with a quota** |
| Metered | `<catalog>.<product>.<feature>`, e.g. `prod.billing.search` | for each metered feature of the user: the pool of the organisation for this feature, plus the pool of each team of the user that gives this feature with a quota |

A team that gives a product without a quota adds no pool. A feature that is not metered (`billing:stats`) has no claim:
its role is enough. The claim is an object with a `quotas` array, which is **always an array**, even with a single pool.

## The key of a counter

The name of a pool is only unique within an organisation and a product. So an API should key its counters by:

- for a simple product: `(organisation, catalog, product, pool)`;
- for a metered product: `(organisation, catalog, product, feature, pool)`.

`(acme, prod, billing, search, /)` and `(acme, prod, shop, search, /)` are different counters, and so are those of
`prod` and of `sandbox`. Each counter follows the `cycle` of its entry in the token, and is reset accordingly.

The organisation is part of the key because two organisations both have a pool `/`. The token tells which one:

- for a **person**, it is the key `org-<org>` of `resource_access` (`org-acme`, with the roles `org-user`, `org-admin` or
  `org-owner`). It is in every full token of a user and in the introspection answer. No catalog can be named with the
  prefix `org-`, so such a key is always an organisation;
- for a **service account**, it is the claim `organization`, an array with the organisation (`["acme"]`): its own
  client holds no role, so there is no `org-acme` key (see [The token](../token/#a-service-account)).

## How an API counts

For every call that has to be counted, the API reads the entries of the claim of the product (or of the feature) and
applies the strategy **all**:

1. check that **every** pool of the entry has room left;
2. if one is full, refuse the call (a `429` for example), and **count nothing**;
3. otherwise increment **all** the pools.

Check everything before incrementing anything, so that a refused call consumes nothing. The pool of the organisation
`/` is always checked and incremented: it is the real ceiling.

For a member of `sales`, a call to `search` on `billing` in `prod` has these two entries:

```json
{
  "prod.billing.search": {
    "quotas": [
      { "pool": "/",      "limit": 200, "cycle": "daily" },
      { "pool": "/sales", "limit": 50,  "cycle": "daily" }
    ]
  }
}
```

The API increments the counters `(acme, prod, billing, search, /)` and `(acme, prod, billing, search, /sales)`, after
checking that the first is below 200 and the second below 50 for the day.

Orgkeep does not enforce this strategy: it is the API that decides (the demo APIs only show the quotas they read, they
do not count). "All" is the strictest and does not depend on the order of the pools, so it is the one to start with. Others are possible (only the first pool of team, or an
overflow from one pool of team to the next), but you then have to give the pools a deterministic order: Keycloak does
not guarantee the order of the values, so sort the pools of the teams by name so that all the instances of your API make
the same choice.

## Several teams

A user can be in several teams. If two of them give the same product (or the same metered feature) **with a quota**,
the token lists **both pools of team**, next to the pool of the organisation:

```json
{
  "prod.billing.search": {
    "quotas": [
      { "pool": "/",           "limit": 200, "cycle": "daily" },
      { "pool": "/accounting", "limit": 80,  "cycle": "daily" },
      { "pool": "/sales",      "limit": 50,  "cycle": "daily" }
    ]
  }
}
```

Orgkeep does not choose between them and does not compute a maximum. With the strategy "all", a call must find room in
the three pools and consumes the three. A user in a team without quota and in `sales` with a quota of 50 consumes the
pool `/sales`: a team quota is a budget that its members always consume.

The pool of the organisation appears **once**, even if several teams of the user carry it.

Nothing prevents the total of the quotas of the teams from exceeding the quota of the organisation: the pool `/` is
always checked.

## Parameters are not quotas

The other values negotiated per organisation, such as a region, are **parameters**. They have no cycle and no counter,
and exist at the level of the organisation only. They are in the same claim as the quotas of a simple product (or alone
in the claim of a metered product), see [The token](../token/#parameters).
