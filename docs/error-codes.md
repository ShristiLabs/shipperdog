# Error Code Registry

Stable public error codes of the problem+json envelope (issue #8). Rules:

- A code is public API contract: never renamed, never reused, never
  removed while a released `/api/v1` may return it.
- Codes are UPPER_SNAKE (payload enum casing convention).
- Source of truth in code: `ApiError` (cross-cutting) and each module's
  domain exception enum (`AuthException.Code`, `OrganizationException.Code`,
  `TenantException.Code`, `ArtifactException.Code`, `TesterException.Code`). This file is the human registry; update it in
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

Reserved, not yet emitted: none. `APPLICATION_ARCHIVED` was reserved in
issue #13 for the artifact-creation surface and is live there since
S5-01 — the artifact module emits its own `ArtifactException.Code`
value of the same public code string (see the artifact table below);
version creation under an archived application has emitted it since
S4-01 (see the version table below).

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
| `VERSION_IMMUTABLE` | 409 | The version's artifact set is frozen (terminal lifecycle state or existing distribution); no artifacts can be added. (INV-004, since S4-03: the `VersionDistributionGuard` rejection E5's upload path surfaces — terminal status, or any distribution record reported through the contributor seam E7 grants and M2 releases will register on; emitted on the artifact routes since S5-01, where the artifact module's advice re-renders the version module's envelope verbatim — same code, same shape) |

## Artifact (`ArtifactException.Code`)

The artifact upload surface (S5-01, issue #20). Both upload paths —
the server-streamed multipart route and the resumable direct-to-storage
session (create, finalize) — share these codes; `VERSION_IMMUTABLE`
arrives through the version module's code as noted above.

| Code | HTTP | Detail |
|---|---|---|
| `ARTIFACT_NOT_FOUND` | 404 | Resource not found. (the concealed-denial shape for unknown or foreign artifacts and upload sessions — finalize answers it for a session of another version, a session invisible in the caller's scope, or an already-registered artifact addressed through the wrong version) |
| `UPLOAD_SESSION_NOT_FOUND` | 404 | Resource not found. (reserved, not yet emitted — finalize deliberately conceals as `ARTIFACT_NOT_FOUND` so a session and an artifact miss are indistinguishable; held for the follow-up session sweeper) |
| `DOWNLOAD_NOT_FOUND` | 404 | Resource not found. (since S8-04, issue #38: the emulator-mode download proxy's single answer for EVERY failed fetch of `/api/v1/downloads/{token}` — an unknown token, a tampered payload, an expired grant, a missing object — deliberately one uniform shape, byte-identical across legs, so an anonymous caller learns neither which check failed nor that an artifact exists; INV-005 applied to the one surface whose callers are anonymous by design) |
| `INVALID_REQUEST` | 400 | The request is invalid. (missing/blank fields, an unparseable platform, an incoherent file name — a bare name of at most 255 characters — a negative or non-numeric build number, a non-positive size hint, a finalize with no bytes uploaded, an empty artifact; since S5-04 also malformed build-provenance claims — non-hex commit, non-URL run link, unparseable timestamp, overlong or control-bearing label — answered with the `errors` member naming every offending wire field) |
| `ARTIFACT_FILE_TYPE_REJECTED` | 415 | The artifact file type is not supported; expected .ipa, .apk, or .aab matching the platform. (the extension allowlist, judged on the name's final extension, case-insensitive: `.ipa` is IOS, `.apk`/`.aab` are ANDROID, and the extension must be coherent with the `platform` field — rejected before any byte is stored) |
| `ARTIFACT_TOO_LARGE` | 413 | The artifact exceeds the maximum upload size. (both size rules: the platform-wide `ARTIFACT_MAX_UPLOAD_BYTES` ceiling on either path — including bytes streamed direct to storage, re-checked at finalize — and the multipart `ARTIFACT_RESUMABLE_THRESHOLD_BYTES` rule that answers 413 for anything larger, pointing the client at the upload-session path) |
| `ARTIFACT_BUILD_TAKEN` | 409 | The build number is already in use for this version and platform. (the pinned `(version, platform, build)` triple of V1's `uq_artifacts_version_platform_build` lands once; race-safe — the database constraint is the authority) |
| `UPLOAD_SESSION_EXPIRED` | 410 | The upload session has expired; create a new one. (the `ARTIFACT_UPLOAD_SESSION_TTL` lifetime, enforced at finalize) |
| `ARTIFACT_NOT_VALIDATABLE` | 409 | Only a FAILED or still-VALIDATING artifact can be revalidated. (the revalidation surface of S5-05, issue #24: PRD 18 has no path from UPLOADING, READY, DISTRIBUTED, or ARCHIVED back to VALIDATING, and the `ArtifactStateMachine` transition table is the authority — a FAILED row re-enters through `FAILED -> VALIDATING`, a blocked row re-drives where it stands) |
| `APPLICATION_ARCHIVED` | 409 | The application is archived; new artifacts cannot be added. (the artifact module's own value of the code issue #13 reserved for this surface — existing artifacts stay readable, and unarchiving resumes uploads) |
| `STORAGE_UNAVAILABLE` | 503 | Artifact storage is unavailable; retry the upload. (the object store refused the write or cannot be read; a retry re-allocates a fresh artifact id and key, never overwriting anything — INV-003) |

## Tester (`TesterException.Code`)

The tester onboarding surface (S6-01, issue #26), tester groups
(S6-02, issue #27), and tenant-tester memberships (S6-03, issue #28).

| Code | HTTP | Detail |
|---|---|---|
| `TESTER_NOT_FOUND` | 404 | Resource not found. (also the concealed-denial shape for testers — a foreign organization's tester is indistinguishable from a missing one, on every item-addressed route) |
| `INVALID_REQUEST` | 400 | The request is invalid. (missing/blank fields, an invalid email, an overlong name, an unknown status value, a missing or over-500-character lifecycle `reason` (S6-04; the reason is mandatory for `DISABLED`); a missing `organizationId` on creation is denied earlier — the target-less permission check answers the opaque 403, authorization preceding validation platform-wide) |
| `TESTER_EMAIL_TAKEN` | 409 | A tester with this email already exists in the organization. (the race-fallback answer when `uq_testers_organization_email` is violated and the concurrent winner is somehow not visible; the sequential duplicate never sees it — creation is idempotent and returns the existing tester) |
| `TESTER_ALREADY_ACTIVE` | 409 | The tester has already accepted onboarding. (re-inviting an ACTIVE tester; the account exists, a fresh invitation is meaningless) |
| `TESTER_INVALID_TRANSITION` | 409 | The status transition is not allowed. (the manual lifecycle allows `ACTIVE <-> SUSPENDED`, deactivation `ACTIVE/SUSPENDED/INVITED -> DISABLED` with a mandatory reason, and the explicit `DISABLED -> ACTIVE` re-enable of an account-linked record — `INVITED -> ACTIVE` belongs to the accept flow alone, a never-onboarded DISABLED record is terminal, and nothing else leaves `DISABLED`; also the answer for re-inviting a SUSPENDED or DISABLED tester) |
| `TESTER_INVITATION_IN_FLIGHT` | 409 | A concurrent invitation for this tester is in flight; retry. (a lost concurrent re-invite on `uq_user_invitations_pending_tester`; the retry revokes the winner's invitation and mints fresh — the one-live-token invariant holds) |
| `TESTER_GROUP_NOT_FOUND` | 404 | Tester group not found. (S6-02; also the concealed-denial shape for tester groups — a foreign tenant's or foreign organization's group is indistinguishable from a missing one, on every item-addressed route) |
| `TESTER_GROUP_NAME_TAKEN` | 409 | A group with this name already exists in the tenant. (the `uq_tester_groups_tenant_name` guard; names are unique per tenant and creation is NOT idempotent — a lost race means another group owns the name now) |
| `TESTER_GROUP_TENANT_DEACTIVATED` | 409 | The tenant is deactivated and takes no new tester groups or members. (the issue #9 affiliation rule extended to groups: reads, renames, deletions, and removals continue) |
| `TENANT_DEACTIVATED` | 409 | The tenant is deactivated and takes no new tester affiliations. (S6-03; the shared public code of `TenantException.TENANT_DEACTIVATED`, the membership-route rendering: a deactivated tenant refuses NEW links and reactivations while the SUSPENDED/ACTIVE switch and removal of existing memberships continue) |
| `TENANT_TESTER_MEMBERSHIP_NOT_FOUND` | 404 | Tenant tester membership not found. (S6-03; also the concealed-denial shape on every membership item route — a foreign tenant's membership row is indistinguishable from a missing one) |
| `TESTER_NOT_ACTIVE` | 409 | Only an ACTIVE tester can be linked to a tenant. (S6-03; an `INVITED` or `SUSPENDED` person record cannot be linked — complete the onboarding or reactivate the tester first; the membership switch then varies independently) |
| `AUTHORIZATION_GRANT_NOT_FOUND` | 404 | Resource not found. (S7-02; also the concealed-denial shape — a foreign tenant's grant is indistinguishable from a missing one on every grant item route) |
| `AUTHORIZATION_GRANT_CONFLICT` | 409 | A different grant for this tester, application, and shape already exists. (S7-02; the same grant identity with a DIFFERENT `expiresAt` — renewal is the expiry route's job, not a second row; the identical grant replays 200 idempotent instead; since S7-04 the duplicate authority scopes to ACTIVE rows — a revoked twin neither replays nor conflicts) |
| `AUTHORIZATION_GRANT_REVOKED` | 409 | The grant is revoked; grant access again with a new grant. (S7-04; a revoked grant is terminal history — the expiry route refuses to write it; revocation itself is the DELETE route's idempotent 204, and re-granting the identity mints a NEW row) |
| `AUTHORIZATION_GRANT_TAG_UNKNOWN` | 400 | The application has no version carrying this tag. (S7-02; the TAG-grant target check — a typo cannot mint a silently-dead grant; tags normalize UPPER like the tag surface) |
| `TESTER_NOT_TENANT_MEMBER` | 409 | The tester is not an active member of the tenant. (S6-03; the group-membership precondition — joining a tenant's groups requires an ACTIVE `tenant_tester_memberships` row under an ACTIVE person record; a foreign organization's tester stays the concealed `TESTER_NOT_FOUND` instead) |
