# Error Code Registry

Stable public error codes of the problem+json envelope (issue #8). Rules:

- A code is public API contract: never renamed, never reused, never
  removed while a released `/api/v1` may return it.
- Codes are UPPER_SNAKE (payload enum casing convention).
- Source of truth in code: `ApiError` (cross-cutting) and each module's
  domain exception enum (`AuthException.Code`, `OrganizationException.Code`,
  `TenantException.Code`). This file is the human registry; update it in
  the same PR that adds a code.
- All 404 codes share the uniform detail `Resource not found.` — the
  concealed-denial rule (PRD §12) requires genuine misses and concealed
  denials of the same resource type to be indistinguishable.

## Cross-cutting (`config.ApiError`)

| Code | HTTP | Detail |
|---|---|---|
| `INVALID_REQUEST` | 400 | The request is invalid. (validation failures add an `errors` member) |
| `IDEMPOTENCY_KEY_INVALID` | 400 | The Idempotency-Key header is malformed. |
| `INVALID_CURSOR` | 400 | The cursor parameter is invalid. |
| `UNAUTHENTICATED` | 401 | Authentication is required. |
| `INVALID_TOKEN` | 401 | The access token is invalid, expired, or revoked. |
| `FORBIDDEN` | 403 | Access denied. (target-less permission denials) |
| `REQUEST_IN_PROGRESS` | 409 | A request with this Idempotency-Key is in progress. |
| `NOT_FOUND` | 404 | Resource not found. (generic; unknown resource types) |
| `METHOD_NOT_ALLOWED` | 405 | The HTTP method is not allowed. |
| `UNSUPPORTED_MEDIA_TYPE` | 415 | The media type is not supported. |
| `INTERNAL_ERROR` | 500 | An unexpected error occurred. (detail never leaks internals) |

Reserved, not yet emitted: `RATE_LIMITED` (429 — Dwara fronts production,
see [api-conventions.md](api-conventions.md)).

## Identity (`AuthException.Code`)

| Code | HTTP | Detail |
|---|---|---|
| `INVALID_CREDENTIALS` | 401 | email or password is incorrect, or the account is locked or disabled (uniform across every failure cause) |
| `INVALID_REFRESH_TOKEN` | 401 | The refresh token is invalid or expired. |
| `INVALID_INVITATION` | 400 | The invitation is invalid or expired. |
| `WEAK_PASSWORD` | 400 | The password does not meet the policy. |
| `EMAIL_TAKEN` | 409 | The email address is already registered. |
| `INVALID_RESET_TOKEN` | 400 | The password reset token is invalid or expired. |
| `INVALID_REQUEST` | 400 | The request is invalid. |
| `NOT_FOUND` | 404 | Resource not found. |
| `FORBIDDEN` | 403 | Access denied. |

## Organization (`OrganizationException.Code`)

| Code | HTTP | Detail |
|---|---|---|
| `ORGANIZATION_NOT_FOUND` | 404 | Resource not found. (also the concealed-denial shape for organizations) |
| `FORBIDDEN` | 403 | Access denied. |
| `INVALID_REQUEST` | 400 | The request is invalid. |
| `EXTERNAL_ID_TAKEN` | 409 | The external id is already in use. |
| `MEMBER_EXISTS` | 409 | The user is already a member of the organization. |
| `ORGANIZATION_DEACTIVATED` | 409 | The organization is deactivated. |
| `LAST_ORG_ADMIN` | 409 | The last organization administrator cannot be removed. |

## Tenant (`TenantException.Code`)

| Code | HTTP | Detail |
|---|---|---|
| `TENANT_NOT_FOUND` | 404 | Resource not found. (also the concealed-denial shape for tenants) |
| `INVALID_REQUEST` | 400 | The request is invalid. |
| `TENANT_MEMBER_EXISTS` | 409 | The user is already a member of the tenant. |
| `TENANT_NAME_TAKEN` | 409 | The tenant name is already in use in the organization. |
| `TENANT_EXTERNAL_ID_TAKEN` | 409 | The external id is already in use. |
| `TENANT_DEACTIVATED` | 409 | The tenant is deactivated. |

## Application (`ApplicationException.Code`)

| Code | HTTP | Detail |
|---|---|---|
| `APPLICATION_NOT_FOUND` | 404 | Resource not found. (also the concealed-denial shape for applications) |
| `INVALID_REQUEST` | 400 | The request is invalid. |
| `APPLICATION_TENANT_DEACTIVATED` | 409 | The owning tenant is deactivated. (registration and ORGANIZATION-to-TENANT scope transitions naming it; reads and edits of existing applications continue) |
| `APPLICATION_NAME_TAKEN` | 409 | The application name is already in use in the organization (both ownership scopes; organization-wide since V10). |
| `APPLICATION_BUNDLE_TAKEN` | 409 | The bundle id is already in use in the organization. |
| `APPLICATION_PACKAGE_TAKEN` | 409 | The package name is already in use in the organization. |
| `APPLICATION_AUTHORIZED_TENANTS_PRESENT` | 409 | The application has authorized tenants; de-authorize them before converting ownership. (ORGANIZATION-to-TENANT scope transition while any tenant holds an ACTIVE authorization; the same concealed `APPLICATION_NOT_FOUND` answers an absent or already-revoked authorization on the mapping surface, S3-03) |

Reserved, not yet emitted: `APPLICATION_ARCHIVED` for artifact creation
(E5/S5-01). The code itself is live — version creation under an
archived application emits it since S4-01 (see the version table
below); issue #13 reserved it for exactly that surface.

## Version (`VersionException.Code`)

| Code | HTTP | Detail |
|---|---|---|
| `VERSION_NOT_FOUND` | 404 | Resource not found. (also the concealed-denial shape for unknown version ids; a denied or unknown application conceals as `APPLICATION_NOT_FOUND` through the permission check) |
| `APPLICATION_NOT_FOUND` | 404 | Resource not found. (the service-layer application-miss backstop, aligned to the registry's one vocabulary since S4-03 — through HTTP the permission check already renders this shape before the service runs) |
| `INVALID_REQUEST` | 400 | The request is invalid. |
| `VERSION_NAME_TAKEN` | 409 | The version name is already in use for the application. (per application, `uq_versions_application_version` since V1; race-safe) |
| `APPLICATION_ARCHIVED` | 409 | The application is archived; new versions cannot be added. (reserved in issue #13, emitted since S4-01; existing versions stay readable, and unarchiving resumes creation) |
| `VERSION_ARCHIVED` | 409 | The version is archived; it is read-only and cannot transition further. (since S4-03: repeat archives and mutations of an archived version — its tags today, artifact uploads with E5) |
| `VERSION_REVOKED` | 409 | The version's distribution is revoked; it is read-only and blocked from new downloads. (since S4-03: repeat revokes, archives of a revoked version, and mutations of a revoked version; the version and its history remain fully readable — INV-006) |
| `VERSION_IMMUTABLE` | 409 | The version's artifact set is frozen (terminal lifecycle state or existing distribution); no artifacts can be added. (INV-004, since S4-03: the `VersionDistributionGuard` rejection E5's upload path surfaces — terminal status, or any distribution record reported through the contributor seam E7 grants and M2 releases will register on) |
