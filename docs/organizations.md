# Organizations (S1-04, extended by S1-05)

Organizations are the top-level tenancy boundary: users are members with
roles, tenants and applications belong to them, and platform
administrators create them. Implemented by the `organization` module
(`backend/src/main/java/com/shipperdog/api/organization`). Permissions
referenced below are enforced by the single RBAC enforcement point —
see [authorization](authorization.md) for the matrix and semantics.

## Endpoints (base `/api/v1/organizations`)

| Method | Path | Permission | Result |
|---|---|---|---|
| POST | `/` | `platform:organizations:create` | `201` organization; the creator becomes its first `ORG_ADMIN`. `409 external_id_taken` on a duplicate external id |
| GET | `/` | authenticated | `200` — platform administrators see every organization, everyone sees only their own memberships |
| GET | `/{id}` | `org:organizations:read` at `{id}` | `200` organization view |
| PUT | `/{id}` | `org:organizations:update` at `{id}` | `200`; partial update — null fields unchanged. `name` and `status` (`ACTIVE`/`DEACTIVATED`) for permission holders; `externalId` additionally requires a platform administrator (billing/CRM mapping) |
| GET | `/{id}/idp-config` | `org:idp-configs:read` at `{id}` | `200` stored record or `404 not_configured` |
| PUT | `/{id}/idp-config` | `org:idp-configs:write` at `{id}` | `200` upserted record (S14 placeholder, not yet functional) |
| GET | `/{id}/members` | `org:members:read` at `{id}` | `200` members with roles and display fields |
| PUT | `/{id}/members/{userId}/role` | `org:members:manage` at `{id}` | role assignment — guards and revocation in [authorization](authorization.md) |
| DELETE | `/{id}/members/{userId}` | `org:members:manage` at `{id}` | membership removal — same |

Unknown organizations are `404` for permitted callers; permission
denials are opaque `403`s (PRD 12).

## Lifecycle semantics

- Creation is platform-only (`platform:organizations:create`); the
  creator's `ORG_ADMIN` membership is recorded at creation time.
- Deactivation (`status=DEACTIVATED`) is reversible and non-destructive:
  memberships stay, but roles held in a DEACTIVATED organization grant
  nothing, and members whose organizations are all deactivated cannot
  log in or refresh (platform administrators exempt — see
  [authentication](authentication.md)). Member management on a
  deactivated organization is refused (`409 organization_deactivated`).
- Membership changes, role assignment, and the last-admin guard are
  documented with the RBAC surface in [authorization](authorization.md).
- Every mutation emits an audit event (`ORGANIZATION_CREATED`,
  `ORGANIZATION_UPDATED`, `ORG_MEMBER_ADDED`, `ORG_IDP_CONFIGURED`,
  and since S1-05 `ORG_MEMBER_ROLE_CHANGED`, `ORG_MEMBER_REMOVED`).
