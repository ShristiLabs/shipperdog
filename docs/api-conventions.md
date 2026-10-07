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
version listing ordered newest-first by the normalized semver sort key —
its optional conjunctive `tag` filter (S4-02) narrows the page to
versions carrying that tag; the value is validated and case-normalized
to the stored UPPER form server-side, and malformed values are a `400`
`INVALID_REQUEST` like any other bad parameter. The listing's default
window is the working set (`DRAFT` and the M2-reserved `ACTIVE`);
the terminal lifecycle states are history, not the working surface;
they return with the optional `include` filter (S4-03) — the
comma-separated values `archived` and `revoked` (case-insensitive,
whitespace-tolerant, composable with `tag` and each other; a typo like
`include=archvied` is a `400` rather than a silently narrowed
nothing). Action-style lifecycle verbs are POST sub-resources
(`.../{versionId}/archive`, `.../{versionId}/revoke-distribution`)
rather than PATCH-with-status, because revoke carries a required
reason body and FR-008 names both operations; they take no
`Idempotency-Key` — the terminal state itself is the replay answer.
The rest of the application registry's surface (S3-01) is item-addressed
(POST/GET/PATCH under `/api/v1/applications`); a tenant's own view of
applications stays the tenant-scoped listing.

## Idempotency

Designated POSTs honor the `Idempotency-Key` header (issue #8 — the
registry of designated endpoints is
`POST /api/v1/organizations`, `POST /api/v1/tenants`,
`POST /api/v1/tenants/{id}/members` (S2-03, idempotent even without the
header for same-role duplicates), `POST /api/v1/applications`
(S3-01), `POST /api/v1/applications/{id}/versions` (S4-01),
`POST /api/v1/artifacts` and `POST /api/v1/artifacts/upload-sessions`
(S5-01, issue #20), `POST /api/v1/testers` (S6-01, idempotent even
without the header: a duplicate pending invite returns the existing
tester `200` instead of re-minting), `POST /api/v1/tester-groups`
plus `POST /api/v1/tester-groups/{id}/members` (S6-02; the membership
route is idempotent even without the header — a duplicate pair answers
the existing membership `200 {added:false}`), and
`POST /api/v1/tenants/{id}/testers` (S6-03; idempotent even without
the header — a duplicate link of an ACTIVE/SUSPENDED membership
answers the existing row `200 {linked:false}`, and a re-link of a
retained REVOKED row reactivates it `200 {reactivated:true}`), and
`POST /api/v1/tenants/{id}/authorization-grants` (S7-02; idempotent
even without the header — the SAME grant identity replays the stored
row `200 {created:false}` with no write and no audit, while the same
identity with a different `expiresAt` is a `409
AUTHORIZATION_GRANT_CONFLICT`). The artifact finalize route is
deliberately NOT in
the registry: its terminal state is the replay answer — a retry
answers the registered artifact `200 created=false`, exactly like the
lifecycle verbs above):

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

## Artifact uploads (S5-01) and the artifact read (S5-02)

Artifact binaries (FR-004/FR-007, PRD 24) upload through two routes,
both permission-checked at the **version** they carry — the artifact
surface is body-addressed, so `POST /api/v1/artifacts` and
`POST /api/v1/artifacts/upload-sessions` take the version id in the
form/body (not the path), the evaluator resolves it version ->
application -> owning tenant/organization, and denials conceal as the
uniform 404 (`app:artifacts:upload`, held by `DEVELOPER`).

Since S5-02 (issue #21) the same surface has its read:
`GET /api/v1/artifacts/{artifactId}?version_id={versionId}` is
permission-checked at the carried version
(`app:artifacts:read`), answers the artifact view with its extraction
tier, and conceals the same way — a foreign version answers the
version target's `VERSION_NOT_FOUND`, an unknown artifact under the
caller's own version answers `ARTIFACT_NOT_FOUND`. Extracted metadata
parses asynchronously post-upload (FR-005): an upload response always
shows `extraction.status = PENDING`; poll this route for the settled
outcome. Details, including the stored extraction failure codes, live
in [artifacts.md](artifacts.md).

Since S5-05 (issue #24) the read also settles the validation tier:
the same route answers the artifact's `validation` block (per-stage
status/code/reason in FR-006 order, the `attempts` counter, and the
structural `format` verdict), and the retry entry
`POST /api/v1/artifacts/{artifactId}/revalidate?version_id={versionId}`
re-drives it — permission-checked at the carried version
(`app:artifacts:upload`), 202 with the artifact view (the attempt is
scheduled, not awaited — poll the read), and 409
`ARTIFACT_NOT_VALIDATABLE` for anything not FAILED or still
VALIDATING, the PRD 18 transition table's own refusal. Details,
including the stored validation failure codes, live in
[artifacts.md](artifacts.md).

**Multipart (at or below the resumable threshold).**
`POST /api/v1/artifacts` with `multipart/form-data`:

- `version_id` (UUID), `platform` (`IOS`/`ANDROID`), optional
  `build_number` (a CI uploader's pinned build), and `file`.
- The file name is a bare name of at most 255 characters whose final
  extension is on the allowlist and coherent with the platform:
  `.ipa` is IOS, `.apk`/`.aab` are ANDROID; anything else is a 415
  `ARTIFACT_FILE_TYPE_REJECTED` — before any byte is stored.
- The platform must be one the application registered (400 otherwise).
- Sizes: anything over `ARTIFACT_MAX_UPLOAD_BYTES` is a 413
  `ARTIFACT_TOO_LARGE` on either path; anything over
  `ARTIFACT_RESUMABLE_THRESHOLD_BYTES` is a 413 on the multipart path
  specifically — the enforced pointer at the session path below (the
  story's >100 MB rule; both default to kilobyte-scale values only in
  tests).
- Bytes stream through the server to object storage under the PRD 24
  key `/{orgId}/{tenantId}/{appId}/{versionId}/{artifactId}/artifact`
  (`_shared` in the tenant slot for organization-owned versions); the
  SHA-256 is computed during the stream and only the metadata row
  lands in PostgreSQL. The response is `201` with the registered view,
  born `VALIDATING`; identical content elsewhere in the application is
  surfaced informationally as `duplicateOf`, never rejected.

**Resumable (larger than the threshold).** Three steps:

1. `POST /api/v1/artifacts/upload-sessions` (JSON: `versionId`,
   `platform`, `fileName`, optional `buildNumber`, optional positive
   `sizeHint`) runs the same guards and answers `201` with the
   pre-allocated `artifactId`, the storage-granted `uploadUrl`, and
   `expiresAt` (`ARTIFACT_UPLOAD_SESSION_TTL`, default 10 minutes).
2. The client streams bytes direct to `uploadUrl` (PUT; one request or
   resumable chunks) — they never transit the API.
3. `POST /api/v1/artifacts/{artifactId}/finalize` (JSON:
   `versionId`) hashes the stored object server-side, registers the
   row, and deletes the session: `201 created=true` on first
   registration, `200 created=false` replaying the registered artifact
   on any retry. Finalize re-checks the version freeze (a version
   revoked mid-upload answers 409 `VERSION_IMMUTABLE`), enforces the
   TTL (410 `UPLOAD_SESSION_EXPIRED`), and refuses no-byte or
   zero-byte uploads (400). Over-ceiling bytes streamed direct are
   caught here with the same 413.

Re-uploads never overwrite: a repeat of the same file is a NEW
artifact with a NEW id and key (INV-003); only a pinned
`(version, platform, build)` triple conflicts (409
`ARTIFACT_BUILD_TAKEN`). See [error codes](error-codes.md) for the
full artifact table and [ADR-0003](adr/ADR-0003-artifact-object-storage.md)
for the storage decision.

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

## Rate limiting

The in-app limiter (S8-05, issue #39) enforces per-client-IP token
buckets on the two abuse surfaces — `POST /auth/login` (the
credential-stuffing door) and the download tier (`GET /downloads/*`
plus the `GET /artifacts/{id}/download` issuance). A refused request
answers HTTP 429 with code `RATE_LIMITED` from this envelope's
registry plus a `Retry-After` header (seconds until the next token).
Every knob is environment-configurable (`RATE_LIMIT_*`; see
[deployment](deployment.md)); Dwara still fronts production and owns
edge-level limiting — this tier is the always-present baseline
beneath it, and it deliberately fails open.
