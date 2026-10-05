# API Conventions

Cross-cutting rules every ShipperDog endpoint follows by construction
(issue #8, S1-08; PRD §19 API Design, §12 opaque denial, P7 API-first).
They are fixed once here so the 60+ endpoints of M1/M2 stay consistent;
enforcement is in code (shared components) and tests, not in review
vigilance.

## Base path and versioning

All business endpoints live under `/api/v1` (PRD §19). A future breaking
surface becomes `/api/v2`; additive changes stay in `v1`.

## Error envelope (RFC 7807)

Every error response — module failures, validation, routing, security
denials, server faults — is `application/problem+json` with exactly this
shape:

```json
{
  "type": "https://api.shipperdog.dev/problems/organization-not-found",
  "title": "Not Found",
  "status": 404,
  "code": "ORGANIZATION_NOT_FOUND",
  "detail": "Resource not found.",
  "trace_id": "7f9c24a1-3e55-4b0e-9d1f-2a8b76c5dd90"
}
```

- `type`, `title`, `status` — the RFC 7807 members; one type URI per code.
- `code` — the stable public identifier (registry: [error-codes.md](error-codes.md)).
  Never renamed, never reused; UPPER_SNAKE like all payload enums.
- `detail` — registry text, safe for every caller of that code. Internal
  exception messages are logged, never echoed.
- `trace_id` — the request's correlation id (`X-Correlation-Id`), the join
  key across trace, logs, and the audit trail.
- `errors` — optional member on 400s: field-to-message map from validation.

Security-chain 401/403s carry the same envelope plus `WWW-Authenticate`
on 401s. All 401 bodies are uniform — no hint whether a token was
missing, malformed, expired, or revoked.

## Opaque denial (PRD §12)

A caller who is not entitled to a resource must not learn it exists:

- **Target-scoped denials render as 404.** `hasPermission(#id, ...)`
  checks that fail throw `ConcealedResourceAccessDeniedException` and the
  response is byte-for-byte what a genuine miss for that resource type
  produces (same code, title, detail). Over-concealment is deliberate:
  one rule, no per-endpoint judgment calls.
- **Target-less denials stay opaque 403** (`FORBIDDEN`, no detail): e.g.
  creation rights a caller simply does not hold anywhere.

Example — a member without read permission and an outsider probing an
unknown id are indistinguishable:

```bash
curl -i $API/api/v1/organizations/$ORG_ID    # no org:organizations:read
HTTP/1.1 404
{"type":".../problems/organization-not-found","title":"Not Found",
 "status":404,"code":"ORGANIZATION_NOT_FOUND","detail":"Resource not found.",
 "trace_id":"..."}
```

Who/what was denied lives in the audit trail (`ACCESS_DENIED` events),
never in the HTTP response.

## Cursor pagination

Every list endpoint accepts `limit` and `cursor` and returns one shape:

```json
{ "items": [ ... ], "next_cursor": "eyJvIjo1MH0" }
```

- `limit` — integer 1..100, default 50. Values outside the range are a
  400 `INVALID_REQUEST`; nothing is silently clamped (no unbounded
  queries; enforced by tests).
- `cursor` — opaque token from a previous `next_cursor`. Opaque means
  opaque: the payload is an internal offset today and may become a
  keyset later without a contract change. Garbage cursors are a 400
  `INVALID_CURSOR`.
- `next_cursor` — present exactly when another page follows; omitted at
  the end. Pass it back as `cursor` to continue.
- Bare arrays are forbidden (ArchUnit-enforced): controllers may not
  return raw `List` responses.

Applied today: `GET /api/v1/organizations`, `GET /api/v1/organizations/{id}/members`,
`GET /api/v1/tenants/{id}/members`, `GET /api/v1/tenants/{id}/applications`
(S2-03), `GET /api/v1/audit` (which also
keeps its conjunctive filters; `page`/`size` are gone), and
`GET /api/v1/applications?organizationId=` (S3-04), the organization-wide
registry listing for organization roles, and
`GET /api/v1/applications/{id}/versions` (S4-01), the per-application
version listing ordered newest-first by the normalized semver sort key.
The rest of the application registry's surface (S3-01) is item-addressed
(POST/GET/PATCH under `/api/v1/applications`); a tenant's own view of
applications stays the tenant-scoped listing.

## Idempotency

Designated POSTs honor the `Idempotency-Key` header (issue #8; artifact
upload joins in S5-01 — the registry of designated endpoints is
`POST /api/v1/organizations`, `POST /api/v1/tenants`,
`POST /api/v1/tenants/{id}/members` (S2-03, idempotent even without the
header for same-role duplicates), `POST /api/v1/applications`
(S3-01), and `POST /api/v1/applications/{id}/versions` (S4-01)):

- Send a unique key per logical operation (a UUID is fine; 1..255 chars).
- The first request executes and its outcome is stored for 24 hours,
    scoped to your principal and the request target.
- Retries with the same key replay the stored response verbatim and set
    `Idempotency-Replayed: true`.
- A concurrent duplicate (original still running) gets 409
    `REQUEST_IN_PROGRESS`.
- Outcomes of 500 and above are not stored — retries reach a fresh
    handler.

```bash
curl -X POST $API/api/v1/organizations \
     -H "Idempotency-Key: 4f9d2c1e-77b0-4f2a-9c8d-1e2f3a4b5c6d" \
     -H "Authorization: Bearer $TOKEN" \
     -d '{"name":"Acme Industries"}'
```

## Payload conventions

- Date/time: ISO-8601 UTC (`2026-10-05T12:00:00Z`), enforced by tests.
- Enums: UPPER_SNAKE (`ACTIVE`, `ORG_ADMIN`), enforced by tests.
- Keys: lowerCamelCase, except the pagination keys `next_cursor` (and
  `items`) which are the platform's single snake_case exception, fixed
  for list wiring consistency.

## OpenAPI contract

- The spec is generated from code (springdoc 3.x) and exported at build
  time by `OpenApiExportIntegrationTest` to `backend/target/openapi.yaml`.
- CI publishes it as an artifact and runs oasdiff against the committed
  baseline `docs/openapi/baseline.yaml`; a breaking change fails the
  build. To accept a breaking change, regenerate and re-commit the
  baseline in the same PR — visibly, never by accident.
- The spec is served unauthenticated at `/v3/api-docs` and
  `/v3/api-docs.yaml`; Dwara can restrict the surface in production.

## Rate limiting (contract only)

Rate limiting is not implemented in the API (Dwara fronts production
traffic and owns it there). The reserved contract for when an upstream
enforces limits: standard `RateLimit-Limit`, `RateLimit-Remaining`,
`RateLimit-Reset` response headers and HTTP 429 with code `RATE_LIMITED`
from this envelope's registry.
