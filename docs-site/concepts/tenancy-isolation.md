---
description: How ShipperDog isolates tenants - per-tenant data, scoped applications, and populations that never leak across tenants.
---

# Tenancy and isolation

ShipperDog is built for enterprises that distribute apps to many
internal or customer populations at once. The tenancy model keeps each
population's data separate while letting one organization govern
everything centrally.

## The hierarchy

```text
Organization
├── Users (members with roles)
├── Tenants
│   ├── Members and testers
│   ├── Tester groups
│   └── Applications (tenant-owned)
├── Applications (organization-shared, optional)
├── Policies, rings, approval workflows
└── Audit logs
```

- **Organization** — the top-level enterprise account. Platform
  administrators create organizations; the creator becomes its first
  organization administrator. Members hold organization roles
  (`ORG_ADMIN`, `APP_OWNER`, `DEVELOPER`, `QA_ENGINEER`,
  `RELEASE_MANAGER`, `SECURITY_REVIEWER`, `COMPLIANCE_REVIEWER`,
  `VIEWER`, and plain `ORG_MEMBER`).
- **Tenant** — a customer or business entity inside the organization:
  "Acme Bank", "Contoso". Every tenant belongs to exactly one
  organization. A tenant is never a user — it has no login. Tenant
  identifiers are unique within the organization (name) and globally
  (external id, your billing/CRM mapping).
- **Application** — belongs to a tenant, or is shared at the
  organization level. See below.
- **Testers** — real people who install builds. A tester record belongs
  to the organization and is linked to tenants through memberships;
  testers do not need to be organization members.

## Tenant-owned and organization-shared applications

An application has exactly one of two ownership scopes:

- **Tenant-owned** — one owning tenant manages it. Other tenants see it
  only when explicitly authorized.
- **Organization-shared** — the organization itself owns it (think: one
  corporate app every customer tenant receives). Which tenants can see
  it is managed through an explicit authorization mapping: grant with a
  reason, revoke the same way, everything audited.

Shared visibility is always opt-in and never ambient. Before a grant,
a tenant's application list is exactly its own rows. The scope can be
converted later, but not while releases target the application —
distribution history anchors it.

## Who can do what

Effective permissions are the union of every role a person holds
across organization and tenant scopes:

- Organization roles apply organization-wide. An `ORG_ADMIN` reaches
  into every tenant of their organization.
- Tenant roles (`TENANT_ADMIN`, `TENANT_TESTER`) apply inside one
  tenant. Tenant testers — the tester persona — hold no other role at
  all: their access is membership plus explicit version grants.
- Membership alone grants nothing (`ORG_MEMBER` is deny-by-default). A
  fresh member can do nothing until granted a functional role.

Memberships carry their own status besides the account status: both
switches must be on. Suspending a membership takes effect on the very
next request — roles are evaluated live from the database on every
call, never baked into tokens.

## Isolation: two layers, by construction

Tenant isolation is not a convention the API tries to remember. It is
enforced twice:

1. **At the endpoint.** Every tenant-scoped route checks the caller's
   standing. A caller without a role in the tenant (or its owning
   organization) receives the same `404` as if the tenant did not
   exist — a denied caller cannot distinguish "not yours" from "not
   there".
2. **At the data layer.** Every tenant-scoped database query composes
   a tenant filter by construction. A query that would span tenants
   fails closed instead of running. Architecture tests reject any code
   path that bypasses the isolation layer.

Platform administrators can operate across tenants for break-glass
operations, and every such request records an explicit, audited
cross-tenant access event. Organization roles can never take that path.

## Deactivation is reversible and non-destructive

- A **deactivated tenant** blocks new memberships and affiliations but
  preserves everything: members, applications, and data remain readable
  and administrable so the tenant can be inspected and reactivated.
- A **deactivated organization** keeps its memberships but they grant
  nothing; members whose organizations are all deactivated cannot sign
  in (platform administrators are exempt — that is the break-glass
  path).

## Ring visibility

Tenants also interact with rollout rings: at a given ring, the tenants
a release targets are intersected with the ring's eligibility rules, so
widening a release stage by stage can admit tenant populations
progressively. See [rings and rollout](/concepts/rings-and-rollout.md).

## Where to go deeper

- [Authorization](/concepts/authorization.md) — the seven-layer
  decision chain and the grant shapes that admit testers
- [Security model](/concepts/security-model.md) — the audit trail and
  the fail-closed posture behind isolation
- Developer detail: the
  [tenants](https://github.com/ShristiLabs/shipperdog/blob/main/docs/tenants.md),
  [organizations](https://github.com/ShristiLabs/shipperdog/blob/main/docs/organizations.md),
  and [authorization](https://github.com/ShristiLabs/shipperdog/blob/main/docs/authorization.md)
  module docs.
