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
| `APPLICATION_TENANT_DEACTIVATED` | 409 | The owning tenant is deactivated. (registration only; reads and edits of existing applications continue) |
| `APPLICATION_NAME_TAKEN` | 409 | The application name is already in use in the organization. |
| `APPLICATION_BUNDLE_TAKEN` | 409 | The bundle id is already in use in the organization. |
| `APPLICATION_PACKAGE_TAKEN` | 409 | The package name is already in use in the organization. |

Reserved, not yet emitted: `APPLICATION_ARCHIVED` (409 — version and
artifact creation under an archived application, binding with E4/E5 per
issue #13's acceptance criterion 4).
