# Tenants (S2-01)

Tenants are the customer/business entities that make ShipperDog
multi-tenant (PRD 8.2): every tenant belongs to exactly one
organization, and the organization's administrators manage the tenant
lifecycle. A tenant is never a user (PRD 8.2/8.8) — it has no login
surface and no identity coupling; testers and users arrive in S2-03.
Implemented by the `tenant` module
(`backend/src/main/java/com/shipperdog/api/tenant`). Permissions
referenced below are enforced by the single RBAC enforcement point —
see [authorization](authorization.md) for the matrix and semantics.

## Endpoints (base `/api/v1/tenants`)

| Method | Path | Permission | Result |
|---|---|---|---|
| POST | `/` | `org:tenants:create` at the body's `organizationId` | `201` tenant view (born `ACTIVE`); `400` invalid name/externalId; `404` unknown organization; `409 TENANT_NAME_TAKEN`/`TENANT_EXTERNAL_ID_TAKEN` on duplicates. Honors `Idempotency-Key` (S1-08) |
| GET | `/?organizationId=...&status=...` | `org:tenants:read` at that organization | `200` the organization's tenants, cursor-paginated (`limit`/`cursor`); optional `status` filter (`ACTIVE`/`DEACTIVATED`); `400` missing/invalid `organizationId` |
| GET | `/{id}` | `tenant:tenants:read` at `{id}` | `200` tenant view |
| PUT | `/{id}` | `tenant:tenants:update` at `{id}` | `200`; partial update — null fields unchanged (`name`, `externalId`, `status`); `400` invalid values; `409` name/externalId taken |

Tenant administrators hold `tenant:tenants:read` on their own tenant
but not `tenant:tenants:update` — the lifecycle is organization-level
administration (the owning organization's `ORG_ADMIN` passes
tenant-scoped checks through the role union rule).

Unknown tenants are `404` for permitted callers; target-scoped
permission denials render as concealed `404`s with the same shape
(PRD 12), and all errors use the problem+json envelope (see
[API conventions](api-conventions.md)).

## Identifier semantics

Two uniqueness guards, both database-backed and race-safe:

- **`name`** — the within-organization identifier
  (`uq_tenants_org_name` on `(organization_id, name)`): two
  organizations may each have an "Acme Bank", one organization may not
  have two. Duplicate within the org is `409 TENANT_NAME_TAKEN`
  (issue #9, acceptance criterion 1).
- **`externalId`** — the customer-system mapping (billing/CRM), required
  and globally unique (`uq_tenants_external_id`): one customer record
  maps to exactly one tenant across the platform. Duplicate is `409
  TENANT_EXTERNAL_ID_TAKEN`.

The service pre-checks both for precise codes and re-derives the
conflicting guard after a lost race (`DataIntegrityViolationException`
on the constraint is the final authority — no driver-message parsing).

## Lifecycle semantics

- Tenants are born `ACTIVE` (V1 default).
- `DEACTIVATED` (V7 adds the enum value; V1's `DISABLED` label stays
  reserved-unused, mirroring `org_status`):
  - **reversible** — `PUT /{id}` with `"status": "ACTIVE"` restores it;
  - **blocks new affiliations** — adding a membership to a deactivated
    tenant is `409 TENANT_DEACTIVATED` (the membership analog of "no
    new invitations"); tester invitations (S2-03) and distributions
    (S7) check the same state when they arrive;
  - **preserves everything** — no cascade, no deletion; existing
    members, applications, and data remain intact and readable
    (acceptance criterion 3).
- Every mutation writes an audit event — `TENANT_CREATED` with the
  created attributes, `TENANT_UPDATED` with `field.before`/`field.after`
  pairs for each changed field — carrying actor, organization, and
  tenant ids (see [audit logging](audit.md)).

## Isolation groundwork (for S2-02)

Every tenant query in this story is organization-scoped by construction
(`findByOrganizationId...`); cross-organization leakage is structurally
impossible from the lifecycle surface. Cross-tenant read enforcement
(INV-001: tenant A cannot access tenant B's data) is S2-02's scope; the
authorization evaluator already resolves tenant ownership for its
union rule, which S2-02 builds on.
