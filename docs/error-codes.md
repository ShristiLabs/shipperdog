# Error Code Registry

Stable public error codes of the problem+json envelope (issue #8). Rules:

- A code is public API contract: never renamed, never reused, never
  removed while a released `/api/v1` may return it.
- Codes are UPPER_SNAKE (payload enum casing convention).
- Source of truth in code: `ApiError` (cross-cutting) and each module's
  domain exception enum (`AuthException.Code`, `OrganizationException.Code`,
  `TenantException.Code`, `ArtifactException.Code`, `TesterException.Code`,
  `ReleaseException.Code`). This file is the human registry; update it in
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
| `RATE_LIMITED` | 429 | Too many requests. (emitted since S8-05/issue #39 by the in-app rate limiter; always with a `Retry-After` header — see [api-conventions.md](api-conventions.md)) |
| `INTERNAL_ERROR` | 500 | An unexpected error occurred. (detail never leaks internals) |

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
| `INVALID_SSO_SESSION_CODE` | 401 | The single sign-on session code is invalid or expired. (uniform across unknown, expired, and already-consumed codes — S14-01) |

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
| `APPLICATION_RELEASES_PRESENT` | 409 | The application has releases; ownership cannot be converted while any exist. (since S9-01, issue #55 — issue #14's reserved zero-distributions precondition, binding in BOTH transition directions: a release anchors the application to its owning dimension, and any lifecycle state blocks because releases are distribution history, never deleted) |

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

## Release (`ReleaseException.Code`)

The release creation and read surface (S9-01, issue #55, FR-015). The
routes are body/query-addressed (`POST/GET /api/v1/releases`), so the
permission target is the application and its denials conceal as
`APPLICATION_NOT_FOUND`; a genuine release miss renders
`RELEASE_NOT_FOUND` here.

| Code | HTTP | Detail |
|---|---|---|
| `RELEASE_NOT_FOUND` | 404 | Resource not found. (also the concealed-denial shape for releases — a release of another application is indistinguishable from a missing one on the detail route, byte-identical to the genuine miss) |
| `APPLICATION_NOT_FOUND` | 404 | Resource not found. (the service-layer application-miss backstop, aligned to the registry's one vocabulary — through HTTP the permission check already renders this shape before the service runs) |
| `VERSION_NOT_FOUND` | 404 | Resource not found. (creation's version miss: unknown, foreign, or a version of another application — indistinguishable) |
| `ARTIFACT_NOT_FOUND` | 404 | Resource not found. (a selected artifact that is unknown, foreign, or of another version — the artifact read's own public code, this surface's own value of it, so a caller learns nothing about artifacts it cannot see) |
| `INVALID_REQUEST` | 400 | The request is invalid. (missing/blank fields, an unparseable platform, an empty artifact or target list, duplicate platforms/artifacts/tenants/groups, a platform-artifact mismatch — answered with the `errors` member naming every offending wire field) |
| `APPLICATION_ARCHIVED` | 409 | The application is archived; new releases cannot be created. (this surface's own value of the code issue #13 reserved — existing releases stay readable) |
| `RELEASE_ALREADY_EXISTS` | 409 | A release already exists for this application and version. (V1's `uq_releases_application_version` is the authority; the pre-check and the lost-race translation answer identically) |
| `RELEASE_RING_UNKNOWN` | 400 | The ring is not defined for the organization. (issue #55 AC4: the ring reference must resolve in the organization's vocabulary — system defaults seeded by V24, organization rings with E10; creation is blocked until definitions exist) |
| `RELEASE_TARGETS_INVALID` | 422 | One or more release targets are invalid. (issue #55 AC2/AC3 — the platform's first 422 family: target validation is a semantic failure of a well-formed request; the `errors` member carries one entry per offending tenant ("does not hold the application") or tester group ("does not belong to target tenant"), so the whole set is fixed in one pass) |
| `RELEASE_ARTIFACT_NOT_READY` | 422 | One or more selected artifacts are not READY. (issue #55 AC1 — S5-05's vocabulary; the `errors` member is keyed by platform naming the offending artifact and its current status) |
| `RELEASE_TRANSITION_INVALID` | 409 | The release cannot transition to the requested state. (issue #56 AC1/AC6 — PRD 17's graph is the single authority; the `errors` member carries `current` and `attempted`, including the self-crossing (re-sending the state the release holds) and every exit from a terminal: FAILED, ROLLED_BACK, and COMPLETED are final) |
| `RELEASE_TRANSITION_REASON_REQUIRED` | 422 | A reason is required for failure and rollback transitions. (issue #56 AC3 — the S4-03 revoke analog: VALIDATING/DEPLOYING to FAILED and ACTIVE to ROLLED_BACK explain themselves in the audit trail and the timeline; the `errors` member names the `reason` field) |
| `RELEASE_TRANSITION_BLOCKED` | 422 | A transition policy refuses this transition. (issue #56's guard seam — a registered `ReleaseTransitionPolicy` contributor denied the crossing; the `errors` member is keyed by the refusing contributor's name. Since S10-01 the `ReleaseApprovalPolicy` contributor is the first live registrant: it refuses APPROVAL_PENDING to APPROVED while the release's latest approval round is PENDING or REJECTED and permits when it is SATISFIED — or when no round exists, the ungated crossing an unconfigured organization keeps) |
| `RELEASE_MANIFEST_NOT_FOUND` | 404 | No manifest is frozen for this release. (S9-03/issue #57 — the release resolved and is readable, but has not crossed into APPROVED yet, or predates the manifest surface; an honest state-dependent miss, never a concealment — the foreign-release shape stays `RELEASE_NOT_FOUND`) |
| `RELEASE_MANIFEST_IMMUTABLE` | 409 | The release manifest is immutable. (S9-03 — V26's one-per-release unique constraint is the authority and the forward-only graph cannot re-enter APPROVED, so a second freeze is doubly impossible; any surface reaching this code is a bug answering loudly, never a silent overwrite) |
| `RELEASE_MANIFEST_CORRUPT` | 500 | The stored release manifest failed integrity verification. (S9-03 — the re-canonicalized content does not hash to the stored digest: storage corruption or tampering; the platform detects drift loudly and never serves, repairs, or rewrites the document — INV-003/004 discipline applied to the manifest itself) |
| `RELEASE_PROMOTION_BLOCKED` | 409 | The promotion is blocked; see the errors member for the complete blocker list. (S10-05/issue #44 AC1 — the target ring's evaluation refused the hop; the `errors` member carries EVERY refusal — `gate:TYPE` with evidence URLs, `approval` with the round's state and waited-on list, `eligibility:TENANT` with the admission reasons, and every registered promotion policy's name — and the refusal is recorded on the append-only promotions timeline in its own transaction, so the evidence survives the 409) |
| `RELEASE_PROMOTION_INVALID_STATE` | 409 | Only an ACTIVE release can be promoted ring-to-ring. (S10-05 — promotion serves live distribution: ACTIVE or already-PROMOTED, whose hops continue widening rings; the `errors` member carries `current` and `required`. Answered before the hop and again INSIDE it: an operator's rollback committing between the promote read and the hop's compare-and-set is this same refusal from the committed status — never a hop, timeline row, or audit landing after a rollback) |
| `RELEASE_PROMOTION_RING_SPLIT` | 409 | The promotion's targets sit on more than one ring; promote them per ring. (S10-05/issue #44 AC3 — per-target rings are the model; a staggered set, the deliberate result of selective promotion, is one hop's refusal naming every tenant's current ring in the `errors` member, so the operator promotes ring group by ring group) |
| `RELEASE_PROMOTION_RING_FINAL` | 409 | The targets already sit on the final ring. (S10-05 — the effective order's last position is the crossing's far end; there is no next ring to hop to. The ACTIVE to PROMOTED crossing fires with the hop that LANDS the final ring, not one past it) |
| `RELEASE_PROMOTION_RING_SKIP` | 409 | Skipping rings is disallowed; promotion moves one ring at a time. (S10-05/issue #44 AC6, widened by S10-06/issue #45 — disallow is the DEFAULT; the organization's skip allowances name the rings a hop may carry beyond the next; the `errors` member carries `next`, the one legal target, and `named`, the request's assertion. The relaxation knob is a promotion-policy registrant (S10-06's seam); none ships) |
| `RELEASE_PROMOTION_CONFLICT` | 409 | A concurrent promotion moved the targets first; re-read and retry. (S10-05 — the ring move is a compare-and-set on every moved row; the count guard answers the precise conflict on any partial overlap, the whole attempt rolls back, never a partial hop) |
| `ENVIRONMENT_NOT_FOUND` | 404 | Resource not found. (S9-04 — creation naming an environment that is unknown, foreign, or of another organization than the application's; this surface's own value of the environment module's public code, so the concealed miss is byte-identical through either module) |
| `ENVIRONMENT_DISABLED` | 409 | The environment is disabled. (S9-04 — a parked environment may not be named by a new release; reactivation is the one crossing out) |

`VERSION_ARCHIVED` and `VERSION_REVOKED` arrive on the creation route
as the version module's own code (a release may not target a read-only
version), re-rendered verbatim — see the version table above.

## Workflow (`WorkflowException.Code`)

The approval engine's codes (S10-01, issue #40; rendered by
`WorkflowExceptionHandler` for the `WorkflowController` surface).

| Code | HTTP | Detail |
|---|---|---|
| `WORKFLOW_NOT_FOUND` | 404 | Resource not found. (the definition detail's concealed miss — a workflow of another organization is indistinguishable from an unknown id through the `organizationId` query parameter's permission target) |
| `ORGANIZATION_NOT_FOUND` | 404 | Resource not found. (the organization-miss backstop of definition creation) |
| `RELEASE_NOT_FOUND` | 404 | Resource not found. (the approval surface's release miss — the release module's own public code, so the concealed miss is byte-identical through either module) |
| `INVALID_REQUEST` | 400 | The request is invalid. (malformed definitions — name shape, unknown role, empty chain, duplicated role, out-of-organization tenant scope — and malformed decisions; the `errors` member keys the offending `steps[i]`/field) |
| `WORKFLOW_RING_UNKNOWN` | 400 | The ring is not defined for the organization. (the creation surface's own rule, this surface's value of it) |
| `WORKFLOW_ALREADY_EXISTS` | 409 | An approval workflow already exists with this name or ring. (V28's one-name and one-ring-per-organization guards; the pre-check and the lost-race translation answer identically) |
| `APPROVAL_STEP_OUT_OF_ORDER` | 409 | The approval decision does not address the current step. (issue AC2 — sequential chains decide in order; the `errors` member carries `current` and `attempted` positions plus the round status, including the concurrent-decision loser's answer) |
| `APPROVAL_NOT_ACTIVE` | 409 | The release is not awaiting approval decisions. (a release outside APPROVAL_PENDING, or a round already SATISFIED/REJECTED/absent; `errors` carries `releaseStatus` and `current`) |
| `APPROVAL_ROUND_NOT_RESTARTABLE` | 409 | Only a rejected approval round can be restarted. (the rework path; `errors.current` carries PENDING/SATISFIED/NONE) |
| `APPROVAL_ROLE_NOT_HELD` | 403 | The caller does not hold the role this step requires. (issue AC2 — the precise per-step refusal after the route's read gate passed; `errors` carries `requiredRole`, `approvalType`, and `permission` so the right approver is findable) |
| `APPROVAL_SELF_APPROVAL` | 403 | The release creator may not approve their own release. (issue AC5 — the delegation rule, whatever roles the creator holds) |
| `APPROVAL_REJECT_REASON_REQUIRED` | 422 | A reason is required to reject an approval step. (issue AC6, the S4-03 revoke analog; `errors` names the `reason` field) |
| `APPROVAL_POLICY_UNRESOLVED` | 422 | The environment's approval policy does not resolve to a defined workflow. (S10-02, issue #41 — the fail-closed resolution: the READY to APPROVAL_PENDING crossing refuses and the release stays one status earlier; `errors.approvalPolicy` carries the dangling name, and defining the named workflow unblocks the same crossing. Rendered by the release module's transition route — the workflow module's refusal composes into its crossing) |

## Gate (`GateException.Code`)

The quality-gate surface (S10-03, issue #42): the organization-grain
definition routes and the release-addressed result/read/override
routes. Definition-route permission denials conceal as
`ORGANIZATION_NOT_FOUND` at the permission target; release-route
denials conceal as `APPLICATION_NOT_FOUND` there, and a genuine
release miss renders `RELEASE_NOT_FOUND` here — the gate module's own
value of the release read's public code (byte-identical concealment).

| Code | HTTP | Detail |
|---|---|---|
| `GATE_DEFINITION_NOT_FOUND` | 404 | Resource not found. (the definition detail's concealed miss — a definition of another organization is indistinguishable from an unknown id through the `organizationId` query parameter's permission target) |
| `ORGANIZATION_NOT_FOUND` | 404 | Resource not found. (the definition surface's organization-miss backstop) |
| `RELEASE_NOT_FOUND` | 404 | Resource not found. (the gate surface's release miss — the release module's own public code, so the concealed miss is byte-identical through either module) |
| `INVALID_REQUEST` | 400 | The request is invalid. (malformed definitions — a non-kebab gate type, config that fails the catalog's expected-result schema, an unknown ring — and malformed submissions: an unknown `status` value; the `errors` member keys the offending field) |
| `GATE_DEFINITION_EXISTS` | 409 | A definition already exists for this gate type. (V31's one-definition-per-org+type guards at each ring level; the pre-check and the lost-race translation answer identically) |
| `GATE_RESULT_INVALID` | 422 | The gate result is invalid. (a threshold/ceiling gate without its `observedValue`, a pass/fail gate carrying one, a missing or non-http(s) `evidenceUrl`; the `errors` member keys the field) |
| `GATE_METRIC_BACKED` | 422 | Metric-backed gates resolve their values from analytics at evaluation time. (`crash-free-threshold`, `open-critical-bugs` — manual submission is refused; the value comes from the `GateMetricSource` seam when S11-04's feeds land, fail-closed NO_DATA until then) |
| `GATE_OVERRIDE_INVALID` | 409 | This gate cannot be overridden. (only a FAILED or NO_DATA gate, exactly once — a passed gate needs no override, a pending one has nothing to override, and a used override is a recorded fact that never moves; `errors` carries the `state` or names the `gate`) |

## Ring (`RingException.Code`)

The release-ring surface (S10-04, issue #43, FR-017): the
organization-grain vocabulary routes (`POST/GET/PATCH/DELETE
/api/v1/rings`) and the release-addressed eligibility read. The
vocabulary routes' permission target is the organization, so denials
conceal as the organization-grain 404; the eligibility route is
release-addressed and carries the release surface's miss codes
byte-identically (rendered through this module's advice, the gate
precedent).

| Code | HTTP | Detail |
|---|---|---|
| `RING_NOT_FOUND` | 404 | Resource not found. (a ring of another organization is indistinguishable from an unknown id) |
| `ORGANIZATION_NOT_FOUND` | 404 | Resource not found. (the organization-miss backstop) |
| `INVALID_REQUEST` | 400 | The request is invalid. (missing/blank fields, a non-kebab code, a bad name or position, an unknown promotion policy; the `errors` member keys the field) |
| `RING_ALREADY_EXISTS` | 409 | A ring with this code already exists. (one code per scope — an organization ring may carry a SYSTEM code, the shadow; V24's `uq_rings_scope_code` is the authority) |
| `RING_SYSTEM_IMMUTABLE` | 409 | System rings cannot be edited; define an organization ring with the same code to customize it. (the shared vocabulary is customized by shadowing, never rewritten) |
| `RING_IN_USE` | 409 | The ring is referenced and cannot be deleted. (releases, targets, gate definitions, approval workflows — the foreign keys are the authority) |
| `RING_ELIGIBILITY_INVALID` | 422 | The ring eligibility rules are invalid. (unknown axis, a non-array or empty axis, an id that is not one of the organization's tenants or tenant-groups — the `errors` member keys every offending entry) |
| `RING_POSITION_INVALID` | 422 | The ring position breaks the progression; Production stays last. (a non-production ring at or past the production ring's position, or a production ring no longer after every other) |
| `RING_PRODUCTION_POLICY_REQUIRED` | 422 | The production ring must reference an approval policy. (the widest audience is governed by default — writing the production ring demands the policy reference) |
| `RING_PRODUCTION_GATES_REQUIRED` | 422 | The production ring must reference at least one required gate definition. (ring-attached or organization-wide — production ships through gates, never past them) |

## Environment (`EnvironmentException.Code`)

The environment surface (S9-04, issue #58): the organization-grain
deployment contexts with their versioned configuration, tenant
assignments, and reference-only credential bindings. Item-route
permission denials conceal as `ENVIRONMENT_NOT_FOUND` through the
authorization module's handler; collection-route denials conceal as
`ORGANIZATION_NOT_FOUND` at the permission target.

| Code | HTTP | Detail |
|---|---|---|
| `ENVIRONMENT_NOT_FOUND` | 404 | Resource not found. (also the concealed-denial shape on every item-addressed route — a foreign organization's environment, config, ledger, assignment set, or binding set is indistinguishable from a missing one) |
| `ORGANIZATION_NOT_FOUND` | 404 | Resource not found. (the collection routes' backstop: unknown or foreign `organizationId`) |
| `INVALID_REQUEST` | 400 | The request is invalid. (missing/blank fields, a non-kebab code or binding name, a negative position, a malformed config key or over-long value, a whitespace-bearing secret reference, an unknown status value) |
| `TENANT_NOT_FOUND` | 404 | Resource not found. (the assignment resolution path's reserved value; the set-write's per-tenant refusal is the 422 below) |
| `ENVIRONMENT_ALREADY_EXISTS` | 409 | An environment already exists for this organization. (`uq_environments_org_code` and V1's `uq_environments_org_name` are the authorities; the pre-check and the lost-race translation answer it identically) |
| `ENVIRONMENT_DISABLED` | 409 | The environment is disabled. (the park refuses every mutation — PATCH, config, assignments, bindings — except reactivation) |
| `ENVIRONMENT_STATUS_INVALID` | 409 | The environment cannot change to the requested status. (the self-crossing; the `errors` member carries `current` and `attempted`, the `RELEASE_TRANSITION_INVALID` shape) |
| `ENVIRONMENT_CONFIG_VERSION_NOT_FOUND` | 404 | No configuration snapshot exists at that version. (an honest state-dependent miss: the environment is readable, the version is absent) |
| `ENVIRONMENT_TENANTS_INVALID` | 422 | One or more tenants are not members of the environment's organization. (one 422 keyed by tenant id; the message is IDENTICAL for an unknown and a foreign tenant — the refusal never confirms existence) |

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
| `POLICY_RULE_NAME_TAKEN` | 409 | A policy rule with this name already exists for the organization. (S10-06/issue #45; V34's `uq_release_policy_rules_org_name` is the authority — the pre-check and the lost-race translation answer it identically, the ring-code precedent) |
| `POLICY_RULE_IN_USE` | 409 | The rule is referenced by recorded policy holds; disable it instead of deleting it. (S10-06; the hold ledger is audit evidence — the evaluation log's loose name snapshots survive deletion by design, the living ledger's foreign keys do not) |
| `POLICY_HOLD_ALREADY_ACKNOWLEDGED` | 409 | The hold is already acknowledged; its decision stands. (S10-06/issue #45 AC2 — the acknowledge is a standing human decision for (release, rule, ring), not a one-shot token; a second actor's intent is a new hop's question) |

## Feedback (`FeedbackException.Code`)

The tester feedback surface (S11-01, issue #46, FR-027, PRD 8.13) —
one self-scoped submission route and three release-addressed console
reads.

| Code | HTTP | Detail |
|---|---|---|
| `FEEDBACK_RELEASE_NOT_FOUND` | 404 | Resource not found. (the submission's single concealment: an unknown application/version/release, a release that does not target the named tenant, a caller without an effective ACTIVE membership there, and a console persona with no tester record all answer this SAME miss — the client learns nothing about which link failed, PRD 12) |
| `FEEDBACK_NOT_FOUND` | 404 | Resource not found. (the console reads' miss: unknown, foreign, or mismatched-application report — and the SECURITY_ISSUE restricted-visibility concealment, which is byte-identical to the genuine miss for every reader below the owning org's admin/security bar) |
| `ARTIFACT_NOT_FOUND` | 404 | Resource not found. (the submission's optional build link: an unknown artifact, or one that is not a READY build of the release's version — this surface's own value of the artifact read's public code, so a caller learns nothing about artifacts it cannot see) |
| `DEVICE_NOT_FOUND` | 404 | Resource not found. (the submission's optional registered-device link since S11-03: an unknown device, one belonging to another tester or tenant, or a DEACTIVATED device — all byte-identical, this surface's own value of the device module's code, the artifact-link pattern) |
| `INVALID_REQUEST` | 400 | The request is invalid. (a blank description, an unknown type token — the message echoes it — or over-long free-text fields; one `errors` entry per offending field, the whole form fixed in one pass) |
| `ATTACHMENT_REJECTED` | 422 | One or more attachments are rejected. (a disallowed extension — png/jpg/mp4/txt/log only — an over-kind size, more files than the configured per-report bound, or a duplicate file name within one submission; keyed by file name in the `errors` member, refused before any write) |
| `STORAGE_UNAVAILABLE` | 503 | Object storage is unavailable. (the attachment store refused the write or cannot serve the object; never a silent 201 — the artifact tier's code, this module's own value of it) |
| `FEEDBACK_TRANSITION_INVALID` | 422 | The triage action is refused. (an illegal lifecycle edge — the enforced vocabulary is OPEN to TRIAGED to RESOLVED or REJECTED, every closed state re-openable to TRIAGED — a severity change on a closed report, or a closing crossing without its mandatory note; the `errors` member names the report's legal edges, the release-transition refusal's shape) |
| `ASSIGNEE_NOT_ELIGIBLE` | 422 | The assignee is not eligible. (the named user holds no membership in the owning organization — the assignment surface resolves candidates there and no other) |

## Device (`DeviceException.Code`)

The tester-device registry (S11-03, issue #48, FR-028, PRD 8.12) —
one tenant's devices behind `tenant:devices:*` (admin-only by
construction; identifiers are semi-sensitive).

| Code | HTTP | Detail |
|---|---|---|
| `DEVICE_NOT_FOUND` | 404 | Resource not found. (also the concealed-denial shape on every item-addressed route — a foreign tenant's device is indistinguishable from a missing one, the tenant seam's filtered answer) |
| `TESTER_NOT_FOUND` | 404 | Resource not found. (a registration naming a tester of another organization — the concealed miss, never a hint which id failed) |
| `INVALID_REQUEST` | 400 | The request is invalid. (an unknown platform token, a blank or over-long identifier/model/os_version, a missing tester; one `errors` entry per offending field, the whole form fixed in one pass — also the answer for an unknown `status`/`platform` filter token, never a concealment) |
| `DEVICE_IDENTIFIER_TAKEN` | 409 | The device identifier is taken. (the tenant-scoped duplicate guard — V1's `uq_devices_tenant_identifier`, the pre-check and the lost insert race answering identically) |
| `DEVICE_REQUEST_REFUSED` | 422 | The device request is refused. (a registration naming a tester with no retained membership row in the tenant, a deactivated tenant taking no new registrations, or the no-op status crossing — the membership surface's 422 shape) |


## Analytics — S11-04/issue #49

The release analytics surface behind `app:analytics:*` and
`tenant:analytics:*`.

| Code | HTTP | Detail |
|---|---|---|
| `RELEASE_NOT_FOUND` | 404 | Resource not found. (the release the metrics address — unknown, foreign, or not of the addressed application; on the events ingest, a release the reporting tenant is not targeted by is byte-identical to this miss) |
| `APPLICATION_NOT_FOUND` | 404 | Resource not found. (the application a series addresses) |
| `INVALID_REQUEST` | 400 | The request is invalid. (an unknown metric token, a malformed instant, an events batch whose shape the type refuses — whole-batch, one `errors` entry per offending field) |
| `ANALYTICS_REQUEST_REFUSED` | 422 | The analytics request is refused. (a window outside the bounds — inverted, wider than the maximum, hourly grain over too long a span, or ending in the future; refused, never clamped) |

## Integration — S12-03/issue #53

The inbound webhook receiver (`POST /webhooks/github`, the generic
framework S12-04/E13 receivers share).

| Code | HTTP | Detail |
|---|---|---|
| `WEBHOOK_SIGNATURE_REJECTED` | 401 | Webhook delivery not accepted. (UNIFORM for every authenticity failure — absent header, malformed signature, wrong key, stale-timestamp replay, and no secret configured answer identically; the caller learns nothing about which leg refused or whether the receiver is on. The shared-secret family: GitHub since S12-03; Jenkins and GitLab since S12-04) |
| `WEBHOOK_DELIVERY_REJECTED` | 400 | Webhook delivery not accepted. (UNIFORM for every authenticity failure on the store receivers, per S12-04's acceptance criteria: an invalid or unverifiable provider signature, wrong audience, expired or stale token, and a stale provider timestamp answer identically; App Store, Google Play) |
| `WEBHOOK_PAYLOAD_MALFORMED` | 400 | The webhook payload is not readable JSON. (a VERIFIED sender's diagnostic; includes a verified delivery without its delivery-id header) |
| `WEBHOOK_PAYLOAD_TOO_LARGE` | 413 | The webhook payload exceeds the configured maximum. (the body ceiling — checked on the declared Content-Length before the bytes are read since S12-04, and on the actual bytes before they are parsed; default 25 MB, GitHub's own payload cap) |

## Distribution — S13-01/issue #61

The adapter framework's configuration surface (channel catalog,
credential-delegation bindings, distribution records) behind
`org:distribution:*`, plus the adapter failure taxonomy. The taxonomy
codes ride distribution records as `failure_code` (a store failure
fails the record, never the release's ACTIVE crossing) and render as
problem codes should a dispatch surface ever expose them over HTTP.

| Code | HTTP | Detail |
|---|---|---|
| `DISTRIBUTION_CREDENTIAL_NOT_FOUND` | 404 | Resource not found. (the addressed credential binding — unknown or of another organization; concealed across organizations; since S13-02 also the refresh route's answer when the record's channel has no resolvable credential on the chain) |
| `DISTRIBUTION_RECORD_NOT_FOUND` | 404 | Resource not found. (the addressed distribution record of the refresh route — unknown or of another organization, concealed; the records read answers the honest empty page for an unknown release inside the caller's organization — no existence signal) |
| `DISTRIBUTION_CREDENTIAL_LEVEL_INVALID` | 400 | The credential level is invalid. (an environment-level binding must name its application — the chain's levels are organization, application, application+environment, PRD 25) |
| `DISTRIBUTION_GROUP_LINK_INVALID` | 400 | The group link request is invalid. (S13-02: an unknown application, tester group, or bundle id; a provider group that does not exist and was not asked to be created; a sync whose scope has no apple credential — the message names the fault's fact, never a foreign row) |
| `DISTRIBUTION_GROUP_LINK_NOT_FOUND` | 404 | Resource not found. (the addressed tester-group link of the TestFlight mapping surface — concealed across organizations) |
| `DISTRIBUTION_METADATA_NOT_FOUND` | 404 | Resource not found. (the addressed store-submission metadata row of the authoring surface — concealed across organizations, S13-03) |
| `DISTRIBUTION_PHASED_RELEASE_NOT_FOUND` | 404 | Resource not found. (the addressed release carries no phased release on the store side — its channel never enabled one; the curve surfaces answer this honestly rather than an invented curve, S13-03) |
| `DISTRIBUTION_PHASED_RELEASE_ACTION_INVALID` | 400 | The phased release action is invalid. (an action outside the PAUSE/RESUME/HALT vocabulary, S13-03) |
| `DISTRIBUTION_ROLLOUT_NOT_FOUND` | 404 | Resource not found. (the addressed release carries no staged rollout on the play channel — a testing-track release, a full-list production release, or a foreign release, concealed; the rollout surfaces answer this honestly rather than an invented curve, S13-05) |
| `DISTRIBUTION_ROLLOUT_ACTION_INVALID` | 400 | The rollout action is invalid. (S13-05: an action outside the ADVANCE/HALT/RESUME/REAPPLY vocabulary, a percentage outside 1..100 or not strictly greater than the curve's current intent, or an action the rollout's current state does not accept) |
| `DISTRIBUTION_ROLLOUT_BLOCKED` | 409 | The rollout increase is blocked by policy. (S13-05, AC4: the production ring's BLOCK/HOLD rules evaluated against the release refuse to widen the curve while its metrics fail their thresholds — the message carries the rule's reference and evidence, the promotion question's own wording) |
| `DISTRIBUTION_ROLLOUT_CONFLICT` | 409 | The rollout changed concurrently; reload and retry. (S13-05: the curve row's optimistic `version` stamp, the #51 subscription precedent — the stale control action answers the precise conflict instead of last-write-winning, so two concurrent ADVANCEs can never lower the curve or double-apply a step) |
| `DISTRIBUTION_STAGED_ROLLOUT_NOT_FOUND` | 404 | Resource not found. (S13-06: the addressed release carries no staged tenant rollout plan, or a foreign release — concealed; the staged surfaces answer this honestly rather than an invented plan) |
| `DISTRIBUTION_STAGED_ROLLOUT_PLAN_INVALID` | 400 | The staged rollout plan is invalid. (S13-06, AC1: an unknown mode, a stage list without a full-coverage final stage, a percentage outside 1..100, an explicit list naming a non-target tenant or shrinking the previous cohort, or authoring against a release already distributing — the plan only widens) |
| `DISTRIBUTION_STAGED_ROLLOUT_ACTION_INVALID` | 400 | The staged rollout action is invalid. (S13-06: an action outside the ADVANCE/ROLLBACK vocabulary, an advance the plan's current state does not accept — not started, complete, rolled back — or a rollback without its mandatory reason) |
| `DISTRIBUTION_STAGED_ROLLOUT_BLOCKED` | 409 | The staged rollout advancement is blocked. (S13-06, AC2: the policy engine's BLOCK/HOLD denial of the widening — the same composed refusal the promotion 409 renders, the rule's reference and evidence in the message — or, the dispatch-failure rollback ownership doctrine since #61, FAILED dispatch records on the release's channels that own the refusal until resolved) |
| `DISTRIBUTION_STAGED_ROLLOUT_CONFLICT` | 409 | The staged rollout changed concurrently or is already planned; reload and retry. (S13-06: the plan row's optimistic `version` stamp or the one-plan-per-release unique grain — the stale or second authoring answers the precise conflict instead of last-write-winning) |
| `ADAPTER_FAILED` | 502 | The distribution channel failed. (the taxonomy's terminal answer for TRANSIENT/PERMANENT categories — the per-adapter retries are exhausted or the fault was final; any adapter throw outside the taxonomy lands here; since S13-03 also the problem a REFUSED production submission answers on the transition route — the store refused, the release cannot go ACTIVE, and the detail carries the provider's own words) |
| `ADAPTER_RATE_LIMITED` | 503 | The distribution channel is rate limited. (the provider throttled every attempt) |
| `ADAPTER_TIMEOUT` | 504 | The distribution channel timed out. (the per-adapter wall cut every attempt — `shipperdog.distribution.adapters.<channel>.timeout`) |
| `ADAPTER_AUTHENTICATION_FAILED` | 502 | The channel credentials were rejected. (never retried — retrying a bad credential is a lockout generator; the detail never carries material) |
| `CHANNEL_UNKNOWN` | 400 | The distribution channel is not registered. (an environment binds a channel id no adapter claims; the dispatch records it loudly as a FAILED record's `failure_code`) |

Record-only failure codes (not HTTP): `CREDENTIAL_UNRESOLVED` (the
adapter's declared provider has no binding anywhere on the chain),
`DIRECT_FORMAT_REFUSED` (the direct channel's format rule — AAB is
Play-only, FR-006), and the TestFlight connector's per-slot codes
(S13-02, on `channel_distributions.failure_code`):
`TESTFLIGHT_GROUPS_UNMAPPED` (the release targets no tester groups, or
none carry a channel link — the mapping surface's gap, recorded
loudly, never a silent skip), `TESTFLIGHT_BUILD_NOT_FOUND` (no ASC
build matches the artifact's own build number, or the artifact carries
no extracted identity — CI or Xcode Cloud must upload the build before
promotion), `TESTFLIGHT_IOS_ONLY` (a non-iOS slot on Apple's channel),
`TESTFLIGHT_BUILD_REJECTED` (Apple's beta review rejected the build),
and `TESTFLIGHT_BUILD_EXPIRED` (the build expired or was removed from
TestFlight); the App Store production connector's per-slot codes
(S13-03, same grain): `APPSTORE_IOS_ONLY` (a non-iOS slot on the App
Store channel), `APPSTORE_VERSION_FACTS_MISSING` (the artifact carries
no extracted bundle id or marketing version string to address its ASC
version by), `APPSTORE_METADATA_MISSING` (no store-submission metadata
was frozen — author per-locale metadata before approval),
`APPSTORE_APP_NOT_FOUND` (no ASC app matches the artifact's bundle
id), `APPSTORE_VERSION_REJECTED` (Apple's App Review rejected the
version), and `APPSTORE_VERSION_REMOVED` (the version was removed
from the App Store); the Google Play testing-track connector's
per-slot codes (S13-04, same grain): `PLAY_ANDROID_ONLY` (a non-
Android slot on the Play channel), `PLAY_AAB_REQUIRED` (the artifact
is not an AAB — FR-006's channel rule enforced at the adapter
boundary, the mirror of the Direct channel's APK-only rule),
`PLAY_PACKAGE_FACTS_MISSING` (the artifact carries no extracted
package name or version name), `PLAY_TRACK_UNMAPPED` (the ring-to-track
mapping resolved no track the vocabulary serves), the
`PLAY_PRODUCTION_OVERRIDDEN` refusal (S13-05: the `play-track`
configuration key names the production track — the override is a
testing-track escape hatch, and the production track is the production
ring's own mapping), `PLAY_GROUPS_UNMAPPED` (the release targets
groups but none are linked to the release's testing track — the
mapping surface's gap, recorded loudly; the production track is exempt,
its audience is the store's own users), and `PLAY_RELEASE_FAILED` (the
track release failed on Google's side).

Since S13-03 the distribution record also carries
`channel_distributions.failure_detail`: the actionable, human-facing
reason beside the taxonomy code — the provider's own words for a
refused submission ("You must provide a value for the key ...") or a
review rejection, truncated to 512 characters. The taxonomy code
stays the closed vocabulary; the detail is the reason a Release
Manager acts on.

## SCIM — S14-03/issue #69

The `/scim/v2/**` provisioning surface deliberately does NOT use this
registry's envelope: SCIM clients parse SCIM errors and nothing else,
so every failure — module refusals and framework faults alike —
answers the RFC 7644 section 3.12 document
(`urn:ietf:params:scim:api:messages:2.0:Error`) over
`application/scim+json`, carrying `status`, the RFC `scimType` detail
code (`invalidSyntax`, `invalidFilter`, `invalidValue`, `invalidPath`,
`uniqueness`), and static `detail` text. The 401s are uniform — no
code, no hint which leg refused; the distinct reason rides the
`SCIM_REQUEST_REJECTED` audit line only. The one platform-envelope
answer on the surface is the rate limiter's 429 `RATE_LIMITED` with
`Retry-After` (deployment machinery, not SCIM protocol). The full
contract: [scim.md](scim.md).

The SCIM configuration surface (`/api/v1/organizations/{id}/
scim-config` and its `/token` routes) DOES use this registry's
envelope — it is a normal `/api/v1` route family answering the
identity module's existing codes (the concealed `404` `NOT_FOUND`
shapes and `INVALID_REQUEST` validations).
