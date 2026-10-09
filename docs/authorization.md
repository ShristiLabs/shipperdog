# Authorization (S1-05)

Role-based access control for the whole API: a permission catalog, a
role-to-permission matrix, and a single enforcement point. Implemented by
the `authorization` module
(`backend/src/main/java/com/shipperdog/api/authorization`); role data
lives in the `organization` and `tenant` modules' membership tables.
Roles are evaluated live from the database on every request — tokens
carry no role claims, so role changes take effect immediately.

## Roles

Three vocabularies feed one matrix:

- **Platform Administrator** (PRD 7.1) is not a membership role: it is
  the `users.platform_admin` flag, modeled as the PLATFORM_ADMIN matrix
  role. Platform administrators hold every permission (wildcard).
- **Organization roles** (`org_role`, V3 vocabulary extended by V4):
  `ORG_ADMIN`, `ORG_MEMBER`, `APP_OWNER`, `DEVELOPER`, `QA_ENGINEER`,
  `RELEASE_MANAGER`, `SECURITY_REVIEWER`, `COMPLIANCE_REVIEWER`,
  `VIEWER`. Memberships live in `organization_members`.
- **Tenant roles** (`tenant_role`, V4): `TENANT_ADMIN`, `TENANT_TESTER`.
  Memberships live in `tenant_members` and are independent of
  organization membership — tenant testers (PRD 7.8) need not belong to
  the owning organization. Since S2-03 each membership also carries a
  status (`ACTIVE`/`SUSPENDED`, V8): only ACTIVE memberships grant the
  role (see [Membership suspension](#guards-and-side-effects)).

`ORG_MEMBER` is plain membership and deliberately holds no permissions:
a fresh member can do nothing until granted a functional role
(deny-by-default).

## Permission catalog and matrix

Permissions are `scope:resource:action` codes
(`backend/src/main/java/com/shipperdog/api/authorization/Permission.java`);
codes are never removed once an endpoint requires them. The matrix is
the documented contract (`RolePermissions`); `*` means every catalog
permission:

| Role | Permissions |
|---|---|
| PLATFORM_ADMIN | `*` (wildcard; the `users.platform_admin` flag, PRD 7.1) |
| ORG_ADMIN | `org:organizations:read` `org:organizations:update` `org:idp-configs:read` `org:idp-configs:write` `org:members:read` `org:members:invite` `org:members:manage` `org:testers:read` `org:testers:invite` `org:testers:manage` `org:tenants:create` `org:tenants:read` `org:environments:read` `org:environments:manage` (S9-04) `org:approval-workflows:read` `org:approval-workflows:manage` (S10-01) `org:gate-definitions:read` `org:gate-definitions:manage` (S10-03) `org:rings:read` `org:rings:manage` (S10-04) `org:release-policies:read` `org:release-policies:manage` `app:releases:policy-acknowledge` (S10-06) `app:releases:gate-override` (S10-03) `tenant:tenants:read` `tenant:tenants:update` `tenant:members:read` `tenant:members:manage` `tenant:testers:read` `tenant:testers:manage` `tenant:applications:read` `app:applications:read` `app:applications:write` `app:versions:read` `app:releases:read` `app:tester-groups:manage` `app:feedback:read` `app:feedback:triage` (S11-02) `tenant:authorization-grants:read` `tenant:authorization-grants:manage` (S7-02) `tenant:devices:read` `tenant:devices:manage` `tenant:devices:export` (S11-03, through the owning-organization overlay) `app:analytics:read` `app:analytics:manage` `tenant:analytics:read` `tenant:analytics:ingest` (S11-04) `org:dashboard:read` `tenant:dashboard:read` (S11-05, the tenant grain through the owning-organization overlay) |
| APP_OWNER | `app:applications:read` `app:applications:write` `app:versions:read` `app:versions:write` `app:artifacts:read` `app:releases:read` `app:releases:manage` `app:releases:approve-product` (S10-01 — the Product step of FR-016's chain) `app:feedback:read` `app:feedback:triage` (S11-02) `org:environments:read` `org:environments:manage` (S9-04) `org:approval-workflows:read` `org:approval-workflows:manage` (S10-01) `org:gate-definitions:read` `org:gate-definitions:manage` (S10-03) `org:rings:read` `org:rings:manage` (S10-04) `org:release-policies:read` `org:release-policies:manage` (S10-06) `tenant:authorization-grants:read` `tenant:authorization-grants:manage` (S7-02, via the owning-organization overlay inside their organization's tenants) `app:analytics:read` `app:analytics:manage` (S11-04) `org:dashboard:read` (S11-05) |
| DEVELOPER | `app:artifacts:read` `app:artifacts:upload` `app:versions:read` `app:releases:read` `org:environments:read` (S9-04) `org:approval-workflows:read` (S10-01) `org:gate-definitions:read` (S10-03) `org:rings:read` (S10-04) `org:release-policies:read` (S10-06) |
| QA_ENGINEER | `app:applications:read` `app:versions:read` `app:releases:read` `app:releases:approve-qa` `app:tester-groups:manage` `app:feedback:read` `app:feedback:triage` (S11-02 — the story's persona: the QA working surface) `org:environments:read` (S9-04) `org:approval-workflows:read` (S10-01) `org:gate-definitions:read` (S10-03) `org:rings:read` (S10-04) `org:release-policies:read` (S10-06) `app:analytics:read` (S11-04 — the release health numbers their evidence feeds) `org:dashboard:read` (S11-05) |
| RELEASE_MANAGER | `app:applications:read` `app:versions:read` `app:artifacts:read` `app:releases:read` `app:releases:manage` `app:releases:approve-release` (S10-01 — the final step of FR-016's chain) `app:releases:gate-override` (S10-03 — the governed gate exception) `app:releases:policy-acknowledge` (S10-06 — the governed hold exception) `app:feedback:triage` (S11-02 — the working surface of the metric their promotions answer to) `app:analytics:read` `app:analytics:manage` (S11-04 — the story's own persona: reads the FR-029 numbers, repairs their history) `org:environments:read` `org:environments:manage` (S9-04) `org:approval-workflows:read` `org:approval-workflows:manage` (S10-01) `org:gate-definitions:read` `org:gate-definitions:manage` (S10-03) `org:rings:read` `org:rings:manage` (S10-04) `org:release-policies:read` `org:release-policies:manage` (S10-06) `org:dashboard:read` (S11-05 — their approvals and the failed gates they may override) |
| SECURITY_REVIEWER | `app:applications:read` `app:versions:read` `app:releases:read` `app:releases:approve-security` `app:feedback:read` `app:feedback:triage` (S11-01/S11-02 — with org admins, the only readers — and triagers — of SECURITY_ISSUE reports) `org:environments:read` (S9-04) `org:approval-workflows:read` (S10-01) `org:gate-definitions:read` (S10-03) `org:rings:read` (S10-04) `org:release-policies:read` (S10-06 — the story's own persona: reads every standing rule, authors none) `org:dashboard:read` (S11-05 — the security-review queue) |
| COMPLIANCE_REVIEWER | `app:applications:read` `app:versions:read` `app:releases:read` `app:releases:approve-compliance` `org:environments:read` (S9-04) `org:approval-workflows:read` (S10-01) `org:gate-definitions:read` (S10-03) `org:rings:read` (S10-04) `org:release-policies:read` (S10-06) `org:dashboard:read` (S11-05 — the compliance steps waiting on them) |
| VIEWER | `app:applications:read` `app:versions:read` `app:releases:read` `org:environments:read` (S9-04) `org:approval-workflows:read` (S10-01) `org:gate-definitions:read` (S10-03) `org:rings:read` (S10-04) `org:release-policies:read` (S10-06) `app:analytics:read` (S11-04 — the dashboards' numbers are read-only surface) `org:dashboard:read` (S11-05 — counts and health only; the server role-filters every pending-action queue away) |
| TENANT_ADMIN | `tenant:tenants:read` `tenant:members:read` `tenant:members:manage` `tenant:testers:read` `tenant:testers:manage` `tenant:applications:read` `tenant:authorization-grants:read` `tenant:authorization-grants:manage` (S7-02) `tenant:devices:read` `tenant:devices:manage` `tenant:devices:export` (S11-03) `tenant:analytics:read` `tenant:analytics:ingest` (S11-04) `tenant:dashboard:read` (S11-05) |
| TENANT_TESTER | `tenant:applications:read` `tenant:applications:download` `tenant:feedback:create` |
| ORG_MEMBER | (none — membership only; deny-by-default) |

A user's effective permissions are the **union** of the sets of every
role they hold across organization and tenant scopes.

## Enforcement model

Every `/api/v1` handler declares its security posture with
`@PreAuthorize` — a `hasPermission(#id, '<type>', '<code>')` expression
for permission-checked endpoints, `isAuthenticated()` for self-scoped
ones. An ArchUnit test fails the build if any endpoint ships without a
posture, and a second check fails if an annotation references a code
that is not in the catalog. The only unannotated endpoints are the six
anonymous auth endpoints (login, register, refresh, logout,
password-reset pair) matched by the security chain's `permitAll`.

`RbacPermissionEvaluator` is the single enforcement point behind every
`hasPermission` call:

- **Deny-by-default.** Unauthenticated or unrecognized principals,
  unknown permission codes, and malformed target ids all evaluate to
  deny. A permission no role holds is simply a permission nobody has;
  the `app:` scope was fail-closed for everyone but platform
  administrators until application records became manageable — it is
  enforced since the application registry (S3-01, issue #13), with
  grants resolving through the owning tenant/organization.
- **Live roles, not token claims.** Roles resolve from database
  memberships on the request's first check. Role changes therefore
  apply to existing access tokens immediately — stricter than the
  issue's "next token refresh at the latest" — and demotions
  additionally revoke refresh families (see below).
- **Per-request memoization.** The resolved `UserRolesSnapshot`
  (platform flag, organization roles in ACTIVE organizations, tenant
  roles) loads at most once per user per request, so endpoints
  evaluating several permissions cost three indexed membership queries
  per request, not one lookup per check. Tenant-owner resolutions are
  memoized inside the snapshot.
- **Scope resolution.** `platform:` — platform administrators only
  (wildcard). `org:` — the caller's role in the target organization
  grants the permission per the matrix; roles never apply outside their
  organization. `tenant:` — the caller's tenant role, with the owning
  organization's role overlaid: an organization administrator holds
  tenant permissions inside their organization's tenants (union across
  scopes). `app:` — since the application registry (S3-01), resolved
  through the application's owning tenant with the organization overlay;
  organization-shared rows (S3-02, PRD 14 — null owning tenant) resolve
  at the owning organization. `version` targets (S5-01, issue #20)
  resolve the same way through the version's application owner: the
  artifact routes are body-addressed by version id, so the check hops
  version -> application -> owner exactly once at the enforcement
  point. `tester` targets (S6-01, issue #26) resolve the record's
  owning organization through the public `TesterAccess` hop for
  `org:` permissions — the tester person record is
  organization-scoped (ADR-0002 tenth amendment), so item-addressed
  tester routes check `org:testers:*` at the record's organization
  and a foreign organization's tester is a concealed miss.
  `tester-group` targets (S6-02, issue #27) resolve the group's
  owning tenant with the organization overlay through the public
  `TesterGroupAccess` hop — groups are tenant-scoped seam rows — and
  an app-scope check at a `tester` target (the groups-per-tester
  listing) resolves the person record's owning organization the same
  way the org-scope hop does. The tenant-tester membership routes
  (S6-03, issue #28) introduce no new target type: the tenant-path
  routes check `tenant:testers:*` at the plain `tenant` target, and
  the org-grain tenants-of-tester listing checks `org:testers:read`
  at the `tester` target through the existing hop.
  Creation-time checks evaluate at the
  future owner tenant for tenant-owned registrations and at the owning
  organization for organization-shared ones, so
  `app:applications:write` authority is organization-level by
  construction. The shared-application tenant authorization mapping
  (S3-03, PRD 15) rides the same authority: `POST`/`DELETE`/`GET
  /api/v1/applications/{id}/tenants` checks
  `app:applications:write`/`read` at the application — organization
  roles only, so an authorized tenant can never widen its own
  visibility, and the mapping is one input layer of the E7
  authorization chain (PRD 11/12) rather than a second permission
  engine. Roles held in DEACTIVATED organizations are excluded
  from the snapshot, so deactivation strips effective permissions
  without touching memberships.

## The layered authorization engine (S7-01)

Since S7-01 a second engine lives beside RBAC, deliberately:
`AuthorizationService.decide(context)` walks PRD 11's chain —
User → Organization → Tenant → Application → Version → Artifact →
Distribution — as seven ordered `AuthorizationLayer` beans, so
entitlement logic for resource access exists exactly once and cannot
be bypassed. The two engines answer different questions: RBAC (above)
decides who may call the console's management surface; the layered
engine decides which resources a subject may reach — the catalog a
tenant sees, the artifacts a version yields. Console-role authority
feeds the layered engine as one entitlement contributor, so a single
evaluation authority decides resource access while RBAC keeps
guarding management actions.

**The decision.** `AuthorizationContext` carries the structured
inputs (subject, the addressed tenant/application/version/artifact
ids, an optional platform pin, the action: `LIST_APPLICATIONS`,
`READ_ARTIFACT`, or `DOWNLOAD`) — the seam the S14-04 ABAC engine
extends later. Layers resolve their inputs through the modules'
public ports (`Ownership` tuples, version lifecycle state, artifact
state, the two-switches membership) with per-decision memoization —
the `UserRolesSnapshot` discipline, one indexed point lookup per
fact. The first failing layer short-circuits with a
`DenialReason` (internal: audit trail and inspection only); the
result carries the evaluated chain — every layer PASSED, FAILED, or
SKIPPED (not applicable, or short-circuited). The engine fails
closed: a layer fault is a denial with reason `ENGINE_FAULT`, never
an allow and never a 500.

**Layer semantics** (PRD 12's worked example in parentheses):

- **USER** — the subject exists (authentication itself is the
  security chain's 401, never a decision; "Is John authenticated?").
- **ORGANIZATION** — the chain's owning organization (the addressed
  tenant's, or the addressed application's) exists and is ACTIVE;
  organization deactivation strips resource access the same request
  it strips RBAC roles. Platform administrators pass by wildcard.
- **TENANT** — resolves HOW the subject reaches the chain: the
  PLATFORM wildcard, the ORGANIZATION dimension (an organization
  role reaching an organization-shared application, S3-02), or the
  TENANT dimension — either the tester persona (a linked tester
  person record whose access is EFFECTIVE: person record ACTIVE AND
  membership ACTIVE, both switches live-queried every decision) or
  the console persona (a tenant role or the owning organization's
  overlay role). A pure tester without a tenant-addressed context
  denies here; so does anyone with neither membership nor role.
- **APPLICATION** ("Does the tenant have Banking?") — the S3-03
  visibility question per addressed application: tenant-owned by the
  resolved tenant, or organization-shared with an ACTIVE
  authorization mapping (`visibleToTenant`, the single-item twin of
  the catalog union). The ORGANIZATION dimension checks the
  application belongs to the subject's organization; the PLATFORM
  wildcard checks existence. Lifecycle is not this layer's question
  (an ARCHIVED application stays readable; writes are RBAC 409s).
  For `LIST_APPLICATIONS` the layer is skipped by design: the set
  form IS the sanctioned union the catalog listing runs.
- **VERSION** ("Is the version authorized?") — existence under the
  addressed application (a mismatch is the concealed miss), the
  lifecycle fact (a REVOKED version blocks `DOWNLOAD` per INV-007 /
  S4-03 while reads stay available per INV-006), then the FR-011
  entitlement seam: every registered `AuthorizationEntitlement`
  contributor is asked (union semantics). S7-01 registers exactly
  contributors — the role overlay (`RoleOverlayEntitlement`: the
  subject holding `app:artifacts:read` or
  `tenant:applications:download` at the owning organization/tenant,
  or the platform wildcard) and, since S7-02, the
  `version_access` grant contributor (`VersionAccessGrantEntitlement`:
  explicit / app-wide / tag / latest-N — see
  [Authorization grants](#authorization-grants-s7-02)). A pure
  tester with no applicable grant still denies
  `ENTITLEMENT_MISSING` at this layer: INV-005 deny-by-default is
  the engine's resting state, flipped by a grant, never by default.
- **ARTIFACT** ("Is the Android artifact available?") — existence
  under the addressed version, the platform pin when the request
  carries one, and availability: downloads require READY or
  DISTRIBUTED (PRD 18); reads carry no availability gate (a
  VALIDATING artifact is readable — the read-back surface is where
  extraction settles).
- **DISTRIBUTION** ("Is distribution active?") — consults every
  registered `AuthorizationDistributionPolicy` contributor and
  denies when any blocks. The M1 kernel registers none (no writer
  of the `distribution` table exists yet), so every download passes
  this layer today; M2's release model registers the real policy
  (window, channel status, ring targeting) against this seam — the
  same dependency inversion as `VersionDistributionContributor`.

**Opaque denials at the API layer.** `require(context,
concealTarget)` decides and throws the route's concealed not-found
shape on denial — the conceal target is the ROUTE's addressing,
never the failing layer, so a denial cannot leak which check failed:
the catalog route conceals everything as `TENANT_NOT_FOUND`; the
artifact read conceals version-grain denials as `VERSION_NOT_FOUND`
and artifact-grain ones (unknown or foreign artifact, platform
mismatch, not distributable) as `ARTIFACT_NOT_FOUND` — exactly the
two miss shapes the route has always produced (byte-for-byte the
genuine miss; see [Opaque denials](#opaque-denials-prd-12)).

**Decision-guarded surfaces (issue #30 AC1).** The tenant catalog
listing (`GET /api/v1/tenants/{id}/applications`) and the artifact
fetch (`GET /api/v1/artifacts/{artifactId}?version_id=...`) run the
engine before serving data. The contract is structural: a
`@DecisionGuarded` class must depend on `AuthorizationService` and
must not shortcut eligibility through other modules' internals or
the `@CrossTenantByDesign` carriers (an architecture test enforces
both); its own module's repositories stay usable — serving the page
the decision authorized is data access, and the catalog's visibility
union IS the application layer's set form. New tenant-scoped
read/download surfaces join by marking and calling the engine.

**Audited, timed decisions.** Every decision — allow and deny alike —
emits an `AUTHORIZATION_DECISION` audit event carrying the action,
the outcome, the failing layer and reason when denied, a compact
chain summary, the addressed resource ids, and (attached by the
audit binding) the request's correlation id (PRD 27). The event's
`surface` distinguishes who asked: `serve` (a guarded console route),
`inspect` (the admin inspection API), or `decision-api` (S7-03's
self-scoped portal surface). Decisions are timed on the shared
`shipperdog.authorization.decision.duration` meter tagged
`engine=layered` beside the RBAC evaluator's leg (`engine` absent =
rbac), so the PRD 42 under-100 ms SLO observes both engines — and
since S7-03 the meter carries a percentile histogram so the p95 is
computable in Prometheus; only the layer walk is timed (the audit
write happens after the clock stops).

**The inspection API** (issue #30 AC5):

| Method | Path | Permission | Result |
|---|---|---|---|
| POST | `/api/v1/authorization/decisions` | `platform:authorization:inspect` | `200` the decision for the supplied context: `allowed`, `reason` and `failingLayer` when denied, and the full evaluated `chain` (layer/status/reason/detail); `400 INVALID_REQUEST` on a missing subject or unknown action; runs live, serves no resource data, and each inspection is itself an audited decision (the audited user is the inspected subject) |

Reason codes and the evaluated chain reach exactly two places — the
audit trail and this platform-administrator surface — never an HTTP
error response.

## Authorization grants (S7-02)

FR-011's four grant shapes — the entitlement data the layered engine
consumes — implemented by the authorization module's `internal.grant`
packages over the `version_access` table (PRD 13, expanded by V21 from
the V1 junction sketch: a surrogate id, the granting tenant, the
application, the shape's target, `expires_at`, and the granting actor;
the V1 `DIRECT`/`GROUP` labels stay reserved-unused). A grant is
tenant-scoped user data: administration rides the tenant path on the
isolation seam, while the engine's decision probe reads through a
`@CrossTenantByDesign` catalog (the role-resolution precedent — an
authorization input, never an endpoint path).

**Grant shapes and resolution** (union semantics — overlapping grants
compose; no negative grants in V1; grant evaluation resolves through
the S7-03 decision-input cache below — the grant SET per tester x
tenant is cached and invalidated write-through, while the per-version
coverage probes stay live):

| Shape | Carries | Authorizes | Resolution path |
|---|---|---|---|
| `EXPLICIT_VERSION` | `versionId` | exactly that version (any lifecycle state — the engine's own layers still apply the REVOKED download block and artifact availability) | the subject probe's identity match |
| `APPLICATION` | nothing | every version of the application the granting tenant can see (tenant-owned by it, or organization-shared with an ACTIVE S3-03 mapping — the chain still applies) | any live grant matches |
| `TAG` | `tag` (UPPER, the V13 vocabulary) | every version carrying the tag at evaluation time — newly tagged versions join with no grant change | `version_tags` composite-key probe per decision; one set query per distinct tag for the effective view |
| `LATEST_N` | `latestN` (1..100) | the N newest of the application's WORKING SET (`DRAFT` + the M2-reserved `ACTIVE` — the default listing window): ARCHIVED and REVOKED versions never occupy a window slot, so archiving a build cannot evict a live one from a tester's N; FR-011's "latest 3" drops the oldest when a 4th arrives | one indexed count of newer working-set versions over `idx_versions_application_sort` (V12's zero-padded `sort_key`, never `created_at`) |

**Both owning dimensions.** For a tenant-owned application the
engine's query carries the owning tenant — which the TENANT layer has
already verified against the subject's tester persona (two switches
on) — so the grant through that tenant matches directly. For an
organization-shared application (owning tenant null) the subject's
grants resolve by (tester, application) and each grant's own tenant
is matched LIVE: the subject's access to that tenant must be
effective (the same two-switches rule, re-checked here because the
engine's tenant layer verified the organization dimension for shared
applications) and the granting tenant must still hold the application
(the ACTIVE S3-03 mapping). A de-authorization or a suspension denies
on the very next evaluation; nothing is precomputed or swept.

**Expiry** is evaluation-time only: `expiresAt` null means never
expires; an expired grant never authorizes and stays in place
(INV-006 — history is the audit trail, not a sweeper's concern). The
effective-access view carries each entry's TTL (the nearest
contributing expiry; null when any contributor never expires) and the
count of already-expired grants.

**Two switches at decision time, not grant time.** Grant creation
checks the TARGET is real (the tester belongs to the tenant's
organization, holds a retained membership row — any status — and the
application is visible to the tenant) but deliberately not
effectiveness: a suspended tester keeps their grants and loses
access; re-enabling restores both with no re-grant.

**Endpoints** (new permissions `tenant:authorization-grants:read` and
`tenant:authorization-grants:manage`, held by TENANT_ADMIN and —
through the owning-organization overlay — ORG_ADMIN and APP_OWNER;
PRD 26's tenant-admin / app-owner grant authority):

| Method | Path | Permission | Result |
|---|---|---|---|
| POST | `/api/v1/tenants/{id}/authorization-grants` | `tenant:authorization-grants:manage` | `201 {grant, created:true}`; the SAME grant (tester, application, shape, target) replays `200 {created:false}` idempotent (no write, no audit); the same identity with a different `expiresAt` is `409 AUTHORIZATION_GRANT_CONFLICT` (renewal is the expiry route's job); `400` shape-pairing/target/bounds/past-or-malformed-expiry/reason (1..1000, mandatory); `400 AUTHORIZATION_GRANT_TAG_UNKNOWN` for a tag the application does not use; `404` concealed unknown/foreign tester (`TESTER_NOT_FOUND`), unlinked tester (`TENANT_TESTER_MEMBERSHIP_NOT_FOUND`), invisible application (`APPLICATION_NOT_FOUND`), wrong-application version (`VERSION_NOT_FOUND`); `409 TENANT_DEACTIVATED` for new grants on a deactivated tenant (existing grants stay administrable); honors `Idempotency-Key` |
| GET | `/api/v1/tenants/{id}/authorization-grants?testerId=&applicationId=` | `tenant:authorization-grants:read` | `200` the tenant's grants, cursor-paginated, creation-ordered, optional filters |
| GET | `/api/v1/tenants/{id}/authorization-grants/{grantId}` | `tenant:authorization-grants:read` | `200` grant view; `404 AUTHORIZATION_GRANT_NOT_FOUND` (the concealed shape — a foreign tenant's grant is indistinguishable) |
| GET | `/api/v1/tenants/{id}/authorization-grants/effective?testerId=&applicationId=` | `tenant:authorization-grants:read` | `200` the tester's effective access: `effectiveAccess` (the two-switches flag, live), newest-first `entries` (version name/status, contributing `sourceGrantIds`, entry `expiresAt` TTL), and `expiredGrantCount` — the single resolved method the portal catalog (S8-02) builds on and S7-03 caches |
| PUT | `/api/v1/tenants/{id}/authorization-grants/{grantId}/expiry` | `tenant:authorization-grants:manage` | `200` grant view with the new `expiresAt` (`null` clears to never-expires); idempotent for the same value; `400` past expiry/malformed/missing reason |
| DELETE | `/api/v1/tenants/{id}/authorization-grants/{grantId}?reason=...` | `tenant:authorization-grants:manage` | `204`; the grant is REVOKED — a state flip, the row retained forever (INV-007), the flip audited with actor + reason + `status.before`/`after`; the reason is mandatory (1..1000, trimmed; `400` otherwise). A repeated revoke on the already-revoked row is the same `204`, no write, no audit (idempotent — the S6-03 retained-row precedent); the row is queryable history with `status=REVOKED` and its revocation facts |
| POST | `/api/v1/tenants/{id}/authorization-grants/bulk-revoke` | `tenant:authorization-grants:manage` | `200 {revokedCount}`; every ACTIVE grant the tester holds through the tenant — optionally narrowed by `applicationId` — revoked in ONE transaction with ONE mandatory `reason` (each flip audited individually with `bulk=true`, one grant-set cache drop covering the whole pass); repeatable — a second pass answers `revokedCount: 0`; `400` missing/bound-violating reason; same concealed `404`s as create for a foreign/unlinked tester |
| GET | `/api/v1/tenants/{id}/authorization-grants/revocation-report?testerId=&applicationId=&limit=&cursor=` | `tenant:authorization-grants:read` | `200 {items, next_cursor}` — the grants the tester has LOST through the tenant: revoked rows (`state=REVOKED`, with `revokedAt`/`revokedBy`/`revocationReason`) and active-but-expired ones (`state=EXPIRED`, evaluation-time, never swept), each with both directions of provenance (`grantedAt`/`grantedBy`, `expiresAt`); creation-ordered, cursor-paginated; concealment mirrors the effective view — a foreign or unlinked tester is the genuine miss's shape |

**Every mutation is audited** with actor + reason (mandatory, 1..1000
trimmed): `AUTHORIZATION_GRANT_CREATED`,
`AUTHORIZATION_GRANT_EXPIRY_CHANGED` (with `expiry.before`/`after`),
`AUTHORIZATION_GRANT_REVOKED` (with `status.before`/`after`; the bulk
pass marks each flip `bulk=true`) — each carrying the grant's shape,
target, and expiry state. Idempotent replays write and audit nothing.

**Revocation semantics (S7-04, INV-007).** Removal is a state flip,
never a row death: no code path deletes `version_access` rows — a
revoked row is retained forever with its revocation facts
(`revokedAt`, `revokedBy`, `revocationReason`) on the row itself, so
the revocation report answers from the data ("what did tester X have
on date Y", the S6-03 retained-row precedent). A `REVOKED` grant
never authorizes whatever its `expiresAt` says: evaluation, the
effective-access view, and the decision-input cache's loader all
consider ACTIVE rows only. The duplicate authority scopes to ACTIVE
rows too (V22 recreates `uq_version_access_grant` as a partial unique
index over non-revoked rows), so a revoked twin neither replays nor
conflicts — re-granting a revoked identity mints a NEW row whose
effect is the only access, and renewal-on-terminal answers `409
AUTHORIZATION_GRANT_REVOKED` (the S4-03 terminal-state precedent:
the expiry route refuses to write history; the revoke route's repeat
is idempotent because it asserts the desired end state).

**Immediacy model (AC2).** Revocation takes effect on the very NEXT
decision: the mutation drops the decision-input cache's grant-set key
write-through (before-commit inside the revoking transaction), and
the loader's ACTIVE-only filter keeps any refill correct — there is
no TTL window to escape on the decision path, sequentially. The
documented bound, aligned with [testers](testers.md)'s revocation
SLA: the decision API mints no URLs — the artifact module's
issuance route (S8-04) does the minting, and its URL TTL is bounded
at startup (fail-fast) at or under the decision-input cache TTL, so
a previously issued URL can never outlive the revocation backstop:
the same one-token-lifetime shape S6-04 documents for tester
deactivation, enforced structurally rather than by operator
discipline.

**M1 surface note.** The artifact routes' RBAC gate stays
console-grade (`app:artifacts:read`) — a pure tester does not pass
it, by design. Since S7-03 the tester persona's own surfaces are the
decision API below (the download decision and the eligibility
listing — self-scoped, engine-decided); since S8-02 the portal
renders the catalog (the decision API's own set form), since S8-03
the version-history views ride the eligibility listing's entries
(platform slots, badges, and the version-grain deep-link probe), and
S8-04 relaxes the artifact routes' console-grade gate where the
download story demands it. The grant's authority
is the layered engine: the tenant catalog listing, the decision API,
and the effective-access view administrators watch.

## The decision API (S7-03)

The stable FIRST-PARTY contract the tester portal (E8) and the
download path consume — the caller asks about their own reach, and
the layered engine answers. It is deliberately NOT the deferred
public third-party "authorize" endpoint (out of the story's scope)
and it mints no URLs: the decision is the API; since S8-04 the
artifact module's `GET /api/v1/artifacts/{artifactId}/download`
consumes the ALLOW server-side and mints the short-lived signed URL
(see [artifacts](artifacts.md)) — this route's answer carries the
artifact id, never the storage key. All three routes are self-scoped
(`isAuthenticated()`, the platform's caller-addressed convention)
with the engine as their ENTIRE authority — a pure tester holds no
RBAC role by S6-03 design (the membership IS the access), so a
permission gate here would exclude exactly the persona the portal
serves; an unrelated caller is denied by the engine's tenant layer
with the same concealed miss a genuine unknown produces.

| Method | Path | Result |
|---|---|---|
| GET | `/api/v1/authorization/decisions/download?tenantId=&versionId=&platform=` | `200` the ALLOWED download decision: `applicationId`, `versionId`, `artifactId` (the tester-visible half of the issuance inputs — the artifact a download would serve), `platform`. The `storageKey` reference left this payload with S8-04 (never expose `storage_key` to clients; signed URLs are the binary's only egress) — the issuance route consumes it server-side and mints the URL; `404` concealed on ANY denial — the version-grain miss (`VERSION_NOT_FOUND`: unknown version, no entitlement, invisible application, revoked version, or an unreachable/unknown tenant) or the artifact-grain miss (`ARTIFACT_NOT_FOUND`: no distributable artifact — READY or DISTRIBUTED, newest first, and since S8-04 never an AAB on the direct channel — for the pinned platform), byte-identical to the genuine miss, never a layer name; `400` an unknown platform vocabulary |
| GET | `/api/v1/authorization/decisions/eligibility?tenantId=&applicationId=&limit=&cursor=&versionId=` | `200` the caller's authorized version set (FR-013's history page, rendered by the portal since S8-03): `application` (id/name/status — the header facts a deep link cannot know from a catalog card), `effectiveAccess` (the two-switches flag), `resolvedAt`, and newest-first `entries` (version id/name/status, each entry's TTL — null when a contributing grant never expires — and `platforms[]`: one slot per platform carrying a distributable artifact, the NEWEST one, with `buildNumber` from extraction metadata, `sizeBytes`, and `uploadedAt`; the artifact a download would serve); cursor-paginated at the story's default `limit` of 20 (`next_cursor` follows the platform envelope); the optional `versionId` narrows the answer to that one version — the deep-link probe, whose miss (unknown, a foreign application's, or unauthorized) conceals as `404 VERSION_NOT_FOUND`, byte-identical to the genuine miss (§12); `404` concealed as `TENANT_NOT_FOUND` (unknown tenant, or the caller reaches it not at all) or `APPLICATION_NOT_FOUND` (an application the tenant cannot see); a linked tester with no grants sees a legitimate EMPTY set, a console caller answers `effectiveAccess=false` and empty, and a SUSPENDED tester conceals entirely — the engine's list decision denies before the set resolves, so they learn nothing (stricter than the administrator's effective view, which still lists retained grants to admins) |
| GET | `/api/v1/authorization/decisions/applications?limit=&cursor=` | `200` the caller's authorized application catalog (S8-02, FR-012/PRD 31, the portal home): the platform list envelope (`items` + `next_cursor`) of cards, each `{tenantId, application {id, name, status}, latestAuthorizedVersion {id, name, status}, platforms[]}` — per platform the newest authorized version carrying a distributable artifact, with the tenant dimension the S8-04 download decision is addressed through; `effectiveAccess` is true when at least one linked tenant passes the engine's LIST decision. The set is server-computed (PRD 26: never a client-side filter of a bigger list): candidates derive from the tester's own grants across LIST-sanctioned linked tenants (deny-by-default — a non-empty effective set requires a live grant), a tenant that can no longer see an application contributes nothing, and an application whose authorized versions yield no installable platform has NO card (no dead cards). The route addresses no foreign resource, so concealment is by emptiness: a grantless-but-linked tester answers `effectiveAccess=true` and an empty page, a console caller `false` and empty, and a suspended tester empty for the same reason the eligibility route conceals them. Cursor-paginated (name-ordered, byte-stable so the isolation matrix's concealment-parity proof holds); a member of `IsolationEndpointRegistry` |

All decisions are audited with `surface=decision-api` (the engine's
own `AUTHORIZATION_DECISION` events — the catalog's per-tenant LIST
decisions included), and all resolve their inputs through the
decision-input cache below. The admin inspection API
(`POST /api/v1/authorization/decisions`, above) is untouched: it
decides for a SUPPLIED subject with reason codes and the evaluated
chain — the self-scoped routes above never leak either.

## Feedback authorization (S11-01)

The feedback surface (issue #46, FR-027, PRD 8.13) splits along the
same seam every portal surface does:

- **Submission** (`POST /api/v1/feedback`, multipart) is self-scoped
  (`isAuthenticated()`, the caller-addressed convention above): the
  tester persona holds no RBAC role by S6-03 design, so a permission
  gate would exclude exactly the persona the surface serves. The
  route's ENTIRE authority is the release link, resolved server-side
  through `ReleaseAccess.submissionFactsOf` — the one release of the
  application and version, answered only when the named submitter
  tenant is one of its targets — beside the caller's own validated
  ACTIVE membership in that tenant (the two-switches rule, live).
  Every identity field is server-attached (tester from the session,
  tenant from the membership, context from the release): the client
  contract carries none of them, so none can be spoofed. An unknown
  application/version pair, a release that does not serve the tenant,
  a caller without standing there, and a console persona with no
  tester record all conceal as the same `404
  FEEDBACK_RELEASE_NOT_FOUND` — byte-identical to the genuine miss.
  The S15-01 in-app SDK POSTs the same contract: one API, two
  clients.
- **Console reads** (`GET /api/v1/releases/{releaseId}/feedback...`,
  release-addressed like every gate surface) check
  `app:feedback:read` through the application-to-owner hop; the
  service resolves and scopes the parent release first (the V25 child
  doctrine — isolation at the parent, never re-derived over the
  children). SECURITY_ISSUE reports carry restricted visibility
  beyond the permission: they are invisible — listing AND detail,
  including attachment downloads — to every reader below the owning
  org's `ORG_ADMIN`/`SECURITY_REVIEWER` bar (plus the platform
  administrator's audited override), concealing as the genuine miss.
- **Triage actions** (S11-02, issue #47 —
  `POST .../feedback/{feedbackId}/severity|assignee|transition`) check
  `app:feedback:triage` at the same application target: a deliberately
  smaller circle than the read, because working the queue (severity,
  lifecycle, assignment) is the QA persona's authority, not everyone
  who may see the reports. Holders: `QA_ENGINEER` (the story's
  persona), `ORG_ADMIN`, `APP_OWNER`, `RELEASE_MANAGER` (the metric
  their promotions answer to), and `SECURITY_REVIEWER` (the restricted
  reports are theirs to triage — nobody else can even see them). The
  SECURITY_ISSUE concealment governs the ACTIONS exactly as the reads:
  a caller below the bar triaging a restricted report conceals as the
  genuine 404 miss, never a hint the row exists. The rollup the
  dashboard block renders applies the same visibility to its COUNTS —
  a reader below the bar sees totals of exactly the rows the listing
  would show them.

## The decision-input cache (S7-03)

The performance half of S7-03: a Redis-backed cache above the grant
contributor and the effective-set resolution, built for the PRD 42
under-100 ms decision SLO. It caches INPUTS only, never decisions
(the story's technical note) — the engine still walks every layer
and computes every evaluation:

| Cached family | Key | Loader (on miss) | Invalidated by |
|---|---|---|---|
| Grant set | per tester x tenant — the tester's ACTIVE grants through that tenant (any application, any expiry; versioned JSON payload, epoch-micros expiry; the loader filters to ACTIVE rows since S7-04 — a revoked grant is history, never a decision input) | one `idx_version_access_grant_subject` prefix descent | grant create, expiry change, revocation (single and bulk — one drop covers the whole pass) |
| Membership effectiveness | per tester x tenant — the two-switches flag | the membership catalog's live probe | membership link, re-link, status switch, removal; tester person-status changes (every linked tenant) |
| Tenant-application visibility | per application x tenant — the S3-03/ownership flag | the application registry's live probe | mapping authorize/deauthorize; S3-02 ownership-scope transitions (both owner tenants) |
| Linked-tenant set | per tester — the retained membership rows' tenants | the membership catalog's tenants-of-tester probe | a fresh membership row creation (removal retains the row, so the set cannot shrink) |

Deliberately NOT cached: the TAG-grant probe (`versionHasTag`) and
the LATEST_N working-set count — they track version lifecycle, which
changes continuously and stays live per evaluation; the engine's
ownership/organization/version/artifact state resolution (the layers
stay live, which is what bounds every cached input below them);
RBAC roles (`UserRolesSnapshot` stays per-request).

**Write-through invalidation (AC2).** Every mutation above drops its
affected keys at BEFORE-COMMIT inside the mutating transaction, so no
reader sees committed state through stale cache; the deliberately
non-transactional mutators (grant create and membership link — the
concurrency disciplines of #31/#28) drop immediately after their
inner write attempt commits. An invalidation that cannot run (Redis
down mid-mutation) never fails the mutation; the TTL is the
backstop. Entries expire after 15 minutes by default
(`shipperdog.authorization-cache.ttl`), at or under the signed-URL
TTL so worst-case staleness is bounded by the same window the story
names; `shipperdog.authorization-cache.enabled=false` is the
operational kill switch (every lookup answers the live loader).

**Fail-closed degradation (AC3).** Any Redis failure — connection
refused, command timeout, or a corrupt/future-versioned payload
(every key and body carries the payload schema version, so a format
change orphans old entries to TTL instead of poisoning a reader) —
answers the LIVE loader's result. The cache can only make decisions
slower, never different: no code path can synthesize an allow the
engine would deny. Consecutive failures open a fail-fast circuit
breaker (3 failures, 5-second cooldown), so a dead Redis costs each
decision the loader's own latency rather than a per-lookup timeout
stack — measured in `backend/loadtest/`: p95 ~6 ms warm-cache and
~7 ms degraded under 50 rps mixed load, both far inside the 100 ms
SLO (the pre-breaker degraded run measured 3.2 s p95, the finding
that motivated it). The Redis health indicator is deliberately OFF:
degraded mode is survivable by design, so Redis availability is not
API health — watch the cache counter's `degraded` leg instead (see
[observability](observability.md)).

## Opaque denials (PRD 12)

Since S1-08 denials render through the platform's single error envelope
(RFC 7807 problem+json with a stable `code` and the request's
`trace_id` — see [API conventions](api-conventions.md)) under one rule:

- **Resource-scoped denials are concealed as `404`.** When a
  `hasPermission(#id, ...)` check fails for an authenticated caller, the
  response is byte-for-byte what a genuine not-found for that resource
  type produces (`ORGANIZATION_NOT_FOUND` / `TENANT_NOT_FOUND` /
  `APPLICATION_NOT_FOUND` / `VERSION_NOT_FOUND` — the version target's
  mapping since S5-02, when the artifact read began permission-checking
  the version it carries; title
  `Not Found`, detail `Resource not found.`). A denied caller cannot
  distinguish an existing organization from an unknown id; a permitted
  caller still learns existence through ordinary `404`s.
- **Target-less denials stay opaque `403`s** (`FORBIDDEN`, no detail
  about which permission was missing) — e.g. creation rights the caller
  does not hold anywhere.

Denial detail (who/what/which permission) belongs to the audit trail,
never the HTTP response. Since S1-06 every authenticated denial is
recorded there automatically as an `ACCESS_DENIED` event (see
[audit](audit.md)).

## Tenant isolation at the data layer (S2-02)

Endpoint checks decide **who may enter a tenant-scoped route**; since
S2-02 a second, structural layer guarantees that **everything read or
written inside it belongs to that tenant** (INV-001, PRD 26):

- The web layer binds a request-scoped `TenantContext` (thread-local)
  for `/api/v1/tenants/{id}` routes: target tenant, actor, and the
  platform-admin override flag. It is cleared when the request
  completes.
- Repositories over tenant-scoped entities extend
  `TenantScopedRepository`, whose base class composes an index-backed
  `tenantId = context` predicate into **every** query — reads,
  existence checks, and writes — so no caller can span tenants. A query
  without a bound context throws instead of running (fail-closed), and
  a write targeting another tenant is refused. Cross-tenant fetches by
  id surface as empty results, which the services render as the same
  opaque `404` a genuine miss produces (PRD 12).
- The seam is mandatory: architecture tests reject a repository over a
  `TenantScope` entity that bypasses it (the only exemption,
  `@CrossTenantByDesign`, marks the authorization engine's role
  resolution — membership queries that are cross-tenant by design and
  never serve endpoint data), reject derived query methods on seam
  repositories, ban raw `EntityManager`/`Session` usage outside
  repository packages, and reject unscoped Spring Data query interfaces
  (`JpaSpecificationExecutor`, `RevisionRepository`) on seam
  repositories.
- The inherited `JpaRepository` surface is policy, not a bypass: the
  base class overrides all of it — id-based reads behave scoped (a
  foreign id reads as a genuine miss), writes and single-entity deletes
  are tenant-verified, and every operation without a tenant-safe
  meaning (bulk deletes, unparameterized listings, lazy proxies,
  `Example` queries) refuses — with a reflective completeness guard
  that fails the build when a Spring Data upgrade adds operations the
  seam does not account for (ADR-0002 amendment).
- The **platform-admin override** is explicit, audited, and closed to
  organization roles: it is set only from `users.platform_admin`, and
  every platform-admin request on a tenant-scoped route records a
  `CROSS_TENANT_ACCESS` audit event (actor, tenant, method/URI). Reads
  stay scoped to the route's tenant even for platform administrators;
  the override authorizes administrative cross-tenant writes.

Applications have ridden the seam since their tenant-scoped listing
(S2-03), and the registry (S3-01) adds two sanctioned cross-tenant
surfaces recorded in ADR-0002's second amendment: the
`@CrossTenantByDesign` registry guard (ownership resolution and
organization-wide uniqueness booleans — never endpoint data paths) and
the `TenantScopeExecutor` that establishes the tenant context for
creation routes and background jobs (establish-only; the route
interceptors always win). When versions, artifacts, testers, and tester
groups get JPA entities (E4+), their entities implement `TenantScope`
and their repositories extend the seam — isolation is inherited by
construction, not re-implemented per module. The mechanism decision
(predicate injection over a Hibernate session filter) and its
trade-offs are recorded in
[ADR-0002](adr/ADR-0002-tenant-isolation-data-seam.md).

## Tenant lifecycle endpoints

Tenant lifecycle (issue #9) is organization-level administration. An
organization administrator manages every tenant their organization owns
(creation and listing are checked against the owning organization; reads
and updates against the tenant itself, where the owning organization's
role overlays the caller's tenant role per the union rule). Tenant
administrators can read their tenant but not change it.

| Method | Path | Permission | Result |
|---|---|---|---|
| POST | `/api/v1/tenants` | `org:tenants:create` at the target `organizationId` (body) | `201` tenant view; `400` invalid name/externalId; `404` unknown organization; `409` `TENANT_NAME_TAKEN` or `TENANT_EXTERNAL_ID_TAKEN`; honors `Idempotency-Key` |
| GET | `/api/v1/tenants?organizationId=...&status=...` | `org:tenants:read` at that organization | `200` tenants, cursor-paginated; `status` filter (`ACTIVE`/`DEACTIVATED`); `400` missing/invalid `organizationId` |
| GET | `/api/v1/tenants/{id}` | `tenant:tenants:read` | `200` tenant view |
| PUT | `/api/v1/tenants/{id}` | `tenant:tenants:update` | `200` tenant view; partial update (null fields unchanged); `400` invalid fields or status; `409` name/externalId taken |

Deactivation is reversible (`ACTIVE` <-> `DEACTIVATED`) and blocks new
memberships (`409 TENANT_DEACTIVATED`) while preserving all data; see
[Tenants](tenants.md). Creation requests missing an `organizationId`
are denied by the permission check before the body is read
(authorization precedes validation platform-wide).

Tester onboarding (S6-01, issue #26) is organization-level
administration of organization-scoped person records (ADR-0002 tenth
amendment); tenant attachment arrives with S6-03.

| Method | Path | Permission | Result |
|---|---|---|---|
| POST | `/api/v1/testers` | `org:testers:invite` at the target `organizationId` (body) | `201` INVITED tester with the once-shown invitation block; duplicate address `200` existing tester (idempotent); `400` invalid email/missing `fullName`; `404` unknown organization; honors `Idempotency-Key` |
| GET | `/api/v1/testers?organizationId=...&status=...` | `org:testers:read` at that organization | `200` testers, cursor-paginated, email-ordered; `status` filter (`INVITED`/`ACTIVE`/`SUSPENDED`) |
| GET | `/api/v1/testers/{id}` | `org:testers:read` (`tester` target) | `200` tester view |
| POST | `/api/v1/testers/{id}/invitations` | `org:testers:invite` (`tester` target) | `201` fresh invitation (revokes the live one); `409 TESTER_ALREADY_ACTIVE`/`TESTER_INVALID_TRANSITION`/`TESTER_INVITATION_IN_FLIGHT` (concurrent re-invite lost — retry) |
| PUT | `/api/v1/testers/{id}/status` | `org:testers:manage` (`tester` target) | `200`; `ACTIVE <-> SUSPENDED` only (`INVITED -> ACTIVE` is the accept flow's); `409 TESTER_INVALID_TRANSITION` |

Acceptance runs through the platform registration endpoint with a
tester-flavored invitation (no permission — the token is the
credential) and grants no organization membership; the invitation
lifecycle and the suspended-tester credential blocks are documented in
[Testers](testers.md).

Tester groups (S6-02, issue #27) are tenant-scoped seam data checked
with the catalog's `app:tester-groups:manage` (ORG_ADMIN and
QA_ENGINEER since S1-05; no separate read grant exists yet):

| Method | Path | Permission | Result |
|---|---|---|---|
| POST | `/api/v1/tester-groups` | `app:tester-groups:manage` at the target `tenantId` (body) | `201` group; `409 TESTER_GROUP_NAME_TAKEN`/`TESTER_GROUP_TENANT_DEACTIVATED`; `404` unknown tenant; honors `Idempotency-Key` |
| GET | `/api/v1/tester-groups?tenantId=...` | `app:tester-groups:manage` at that tenant | `200` groups, cursor-paginated, creation-ordered |
| GET | `/api/v1/tester-groups?testerId=...` | `app:tester-groups:manage` (`tester` target) | `200` the tester's groups across their organization's tenants, name-ordered |
| GET | `/api/v1/tester-groups/{id}` | `app:tester-groups:manage` (`tester-group` target) | `200` group view |
| PUT | `/api/v1/tester-groups/{id}` | `app:tester-groups:manage` (`tester-group` target) | `200` renamed; `409 TESTER_GROUP_NAME_TAKEN` |
| DELETE | `/api/v1/tester-groups/{id}` | `app:tester-groups:manage` (`tester-group` target) | `204`; memberships removed with the group |
| GET | `/api/v1/tester-groups/{id}/members` | `app:tester-groups:manage` (`tester-group` target) | `200` testers, cursor-paginated, join-ordered |
| POST | `/api/v1/tester-groups/{id}/members` | `app:tester-groups:manage` (`tester-group` target) | `201 {added:true}`; re-add `200 {added:false}` (idempotent); `404 TESTER_NOT_FOUND` (unknown/foreign tester); honors `Idempotency-Key` |
| DELETE | `/api/v1/tester-groups/{id}/members/{testerId}` | `app:tester-groups:manage` (`tester-group` target) | `204`, idempotent (absent membership is a no-op) |

Default-group seeding on tenant creation runs inside the tenant
module's creation transaction; details and the race discipline are
documented in [Tester groups](tester-groups.md).

## Role-assignment endpoints

| Method | Path | Permission | Result |
|---|---|---|---|
| GET | `/api/v1/organizations/{id}/members` | `org:members:read` | `200` members with roles and display fields, cursor-paginated |
| PUT | `/api/v1/organizations/{id}/members/{userId}/role` | `org:members:manage` | `200` member view; `400` unknown role; `404` unknown organization/member; `409` `LAST_ORG_ADMIN` or `ORGANIZATION_DEACTIVATED` |
| DELETE | `/api/v1/organizations/{id}/members/{userId}` | `org:members:manage` | `204`; same error contract; always revokes the member's sessions |
| GET | `/api/v1/tenants/{id}/members` | `tenant:members:read` | `200` members with roles and membership status, cursor-paginated |
| POST | `/api/v1/tenants/{id}/members` | `tenant:members:manage` | `201` member view (born `ACTIVE`); same-role duplicate is idempotent `200` (no write, no audit); `409 TENANT_MEMBER_EXISTS` on a different-role duplicate; `409 TENANT_DEACTIVATED` while deactivated; `404` unknown user/tenant; `400` missing/unknown role; honors `Idempotency-Key` |
| PUT | `/api/v1/tenants/{id}/members/{userId}/role` | `tenant:members:manage` | `200` member view; creates the membership when absent (`404` unknown user/tenant; `409` `TENANT_DEACTIVATED` while deactivated); `400` unknown role |
| PUT | `/api/v1/tenants/{id}/members/{userId}/status` | `tenant:members:manage` | `200` member view; `ACTIVE`/`SUSPENDED` transition (suspending revokes sessions); `404` unknown tenant/member; `400` invalid status |
| DELETE | `/api/v1/tenants/{id}/members/{userId}` | `tenant:members:manage` | `204`; `404` unknown tenant/member |
| GET | `/api/v1/tenants/{id}/applications` | `tenant:applications:read` | `200` the tenant's own applications, cursor-paginated (S2-03; registry management is S3-01) |

### Tenant-tester membership endpoints (S6-03)

The portal-access join — separate vocabulary from the console-member
routes above on purpose (`tenant:testers:*` vs `tenant:members:*`):
a tester holds no tenant role, and a console member is not a tester
(see [Tenant-tester memberships](tenant-tester-memberships.md)).

| Method | Path | Permission | Result |
|---|---|---|---|
| POST | `/api/v1/tenants/{id}/testers` | `tenant:testers:manage` | `201 {linked:true}` (born `ACTIVE`); duplicate link idempotent `200 {linked:false}`; re-link of a retained REVOKED row `200 {reactivated:true}`; `409 TESTER_NOT_ACTIVE` (INVITED/SUSPENDED person record) / `TENANT_DEACTIVATED` (new affiliations only); `404` unknown/foreign tester or tenant; honors `Idempotency-Key` |
| GET | `/api/v1/tenants/{id}/testers` | `tenant:testers:read` | `200` the EFFECTIVE view by default (person record ACTIVE and membership ACTIVE — tenant-scoped visibility); `status=ACTIVE|SUSPENDED|REVOKED` the administrative view; `status=ALL` every retained row (history); cursor-paginated, rows carry both statuses and `effectiveAccess` |
| GET | `/api/v1/tenants/{id}/testers/{testerId}` | `tenant:testers:read` | `200` membership view with `testerStatus`, `membershipStatus`, `effectiveAccess`; `404 TENANT_TESTER_MEMBERSHIP_NOT_FOUND` |
| PUT | `/api/v1/tenants/{id}/testers/{testerId}/status` | `tenant:testers:manage` | `200` membership view; `ACTIVE`/`SUSPENDED` transition (live-query effectiveness — excluded on the next request); `409 TESTER_INVALID_TRANSITION` on a REVOKED row (re-link instead) — the switch continues on a deactivated tenant |
| DELETE | `/api/v1/tenants/{id}/testers/{testerId}` | `tenant:testers:manage` | `204`, idempotent; removal RETAINS the row REVOKED (history preserved), never deletes |
| GET | `/api/v1/testers/{id}/tenants` | `org:testers:read` (`tester` target) | `200` the tester's tenants across their own organization, link-ordered, history included — one working set (the org-grain form, the groups-per-tester precedent) |

### Device registry (S11-03, issue #48)

The tester-device registry of one tenant — `tenant:devices:*`, a new
vocabulary in the `tenant:testers` shape (held by TENANT_ADMIN and,
through the owning-organization overlay, ORG_ADMIN; nobody else —
identifiers are semi-sensitive device fingerprints, so every device
surface is admin-only by construction). See
[Devices](devices.md) for the full surface.

| Method | Path | Permission | Result |
|---|---|---|---|
| POST | `/api/v1/tenants/{id}/devices` | `tenant:devices:manage` | `201` the registered view (born `ACTIVE`); `409 DEVICE_IDENTIFIER_TAKEN` (the tenant-scoped uniqueness); `422 DEVICE_REQUEST_REFUSED` (tester not linked to the tenant / tenant deactivated); `404 TESTER_NOT_FOUND` (a foreign-organization tester — the concealed miss); `400 INVALID_REQUEST` keyed by field |
| GET | `/api/v1/tenants/{id}/devices` | `tenant:devices:read` | `200` the tenant's devices, conjunctive `testerId`/`platform`/`status` filters, cursor-paginated, each row joined with its owner's contact record |
| GET | `/api/v1/tenants/{id}/devices/{deviceId}` | `tenant:devices:read` | `200` the device view; `404 DEVICE_NOT_FOUND` (unknown, foreign — the tenant seam — or invisible) |
| PUT | `/api/v1/tenants/{id}/devices/{deviceId}` | `tenant:devices:manage` | `200` the updated view — model/OS only (null clears; the identifier and platform never move) |
| PUT | `/api/v1/tenants/{id}/devices/{deviceId}/status` | `tenant:devices:manage` | `200` the reversible `ACTIVE`/`DEACTIVATED` switch (rows are never deleted — the INV-007 history posture); `422` the no-op crossing |
| GET | `/api/v1/tenants/{id}/devices/export` | `tenant:devices:export` | `200 text/csv` the provisioning list (identifier, platform, model, os_version, owner_email); default slice `ACTIVE` — the fleet a store portal provisions; `status=DEACTIVATED`/`ALL` the other slices; the disclosure is audited (`DEVICES_EXPORTED`, count and slice, never a value) |

### Release analytics (S11-04, issue #49)

The FR-029 metric surface — `app:analytics:*` (APP_OWNER, RELEASE_MANAGER,
QA_ENGINEER, VIEWER read; the recompute trigger manages) and
`tenant:analytics:*` (TENANT_ADMIN, and ORG_ADMIN through the
owning-organization overlay). See
[Release analytics](analytics.md) for the dictionary, the pipeline, and
the frozen SDK events contract.

| Method | Path | Permission | Result |
|---|---|---|---|
| GET | `/api/v1/applications/{applicationId}/releases/{releaseId}/metrics` | `app:analytics:read` | `200` the whole dictionary for one release (current values, the explicit NO_DATA as `value: null`); `404 RELEASE_NOT_FOUND` (unknown/foreign release — the concealed miss) |
| GET | `/api/v1/applications/{applicationId}/metrics?metric=&from=&to=` | `app:analytics:read` | `200` one metric's series from the rollups (`grain`, `ring`, `releaseId`, limit/cursor optional); `400 INVALID_REQUEST` (unknown metric/instant); `422 ANALYTICS_REQUEST_REFUSED` (window bounds — refused, never clamped) |
| GET | `/api/v1/tenants/{tenantId}/metrics?...` | `tenant:analytics:read` | the same series over the tenant's owning dimension |
| POST | `/api/v1/tenants/{tenantId}/analytics/events` | `tenant:analytics:ingest` | `201` the batch receipt (the frozen S15-01 SDK contract); `400 INVALID_REQUEST` (type/shape/future-bound, whole batch); `404 RELEASE_NOT_FOUND` (a release the reporting tenant is not targeted by — the concealed miss) |
| POST | `/api/v1/applications/{applicationId}/metrics/rollups` | `app:analytics:manage` | `200` the recompute receipt `{rows, from, to}` (backfill and late-history repair; idempotent); `422 ANALYTICS_REQUEST_REFUSED` (window beyond the bound) |

### Dashboards (S11-05, issue #50)

The console's landing read models — `org:dashboard:read` (every
functional console role except DEVELOPER and ORG_MEMBER; the
pending-actions panel personalizes by role server-side) and
`tenant:dashboard:read` (TENANT_ADMIN with the owning-organization
overlay). See [Dashboards](dashboards.md) for the views, the health
derivation, and the series-folding rule.

| Method | Path | Permission | Result |
|---|---|---|---|
| GET | `/api/v1/organizations/{id}/dashboard` | `org:dashboard:read` | `200` the org view (counts reconciling with the list endpoints, per-application release health, role-filtered pending actions); `404 ORGANIZATION_NOT_FOUND` concealed |
| GET | `/api/v1/tenants/{id}/dashboard` | `tenant:dashboard:read` | `200` the tenant view (catalog-union applications, working-set versions, effective testers, in-flight targeting releases); `404 TENANT_NOT_FOUND` concealed — both routes are isolation-matrix members |

Error responses use the platform's problem+json envelope with stable
codes (see the [error code registry](error-codes.md)).

Organization-scoped invitations (`POST /api/v1/auth/invitations` with
`organizationId`) require `org:members:invite` at that organization and
grant the recorded role on acceptance.

Guards and side effects:

- **Last-admin guard.** An organization always keeps at least one
  `ORG_ADMIN`: the last one cannot be demoted or removed (`409
  last_org_admin`), by anyone including platform administrators.
  Admin-affecting mutations serialize per organization through a row
  lock on the organization row, so concurrent demotions/removals cannot
  race past the count. There is deliberately no last-`TENANT_ADMIN`
  guard: an administrator of the owning organization (or a platform
  administrator) can always reassign, so a tenant cannot be orphaned.
- **Demotion revocation.** A permission-losing role change demotes: the
  member's refresh tokens are revoked immediately (`TOKEN_REVOKED`
  audit event, reason `role_demoted`) so the change takes effect on
  their next session at the latest. Pure upgrades revoke nothing.
  Membership removal always revokes (reason `membership_removed`), and
  suspending a tenant membership revokes too (reason
  `membership_suspended`); reactivation revokes nothing.
- **Membership suspension (S2-03).** A tenant membership carries its
  own status (`tenant_members.status`, V8) independent of the user
  account status — access needs both switches on. Only `ACTIVE`
  memberships resolve to tenant roles in the live snapshot, so a
  suspended member loses tenant permissions on the next request, and
  the organization overlay (the owning organization's roles) is
  untouched by suspension.
- **Tester deactivation (S6-04, issue #29).** Deactivating a tester
  (`DISABLED`) is revocation, not parking: the tester's login, refresh,
  and PAT use are refused (audit reason `tester_deactivated`), the
  linked account's refresh families are revoked in the deactivating
  transaction (reason `tester_deactivated`), and every ACTIVE
  tenant-tester membership cascades to SUSPENDED — so the two-switches
  rule (person record ACTIVE AND membership ACTIVE) denies every
  effective-access decision on the next evaluation. Reactivation
  restores nothing silently: each membership waits for its per-tenant
  re-enable. See [testers](testers.md) for the full semantics and the
  revocation latency SLA.
- **Disabled accounts.** A `DISABLED` user cannot refresh (the refresh
  family is revoked on the attempt) and cannot use PATs — existing
  sessions do not outlive the disable.

## Application registry endpoints (S3-01)

Application registry management (issue #13) is organization-level
authority: `app:applications:write` is granted only to organization
roles (`ORG_ADMIN`, `APP_OWNER`), and the creation check evaluates at
the future owner tenant, so tenant roles reach applications through
the tenant-scoped listing instead.

| Method | Path | Permission | Result |
|---|---|---|---|
| POST | `/api/v1/applications` | `app:applications:write` at the body's `tenantId` | `201` application view (born `ACTIVE`); `400` invalid platforms/identifiers; `409` `APPLICATION_NAME_TAKEN` / `APPLICATION_BUNDLE_TAKEN` / `APPLICATION_PACKAGE_TAKEN` / `APPLICATION_TENANT_DEACTIVATED`; `404` unknown tenant (concealed); honors `Idempotency-Key` |
| GET | `/api/v1/applications/{id}` | `app:applications:read` at the application | `200` application view; `404` unknown or denied (concealed, identical envelope) |
| PATCH | `/api/v1/applications/{id}` | `app:applications:write` at the application | `200` partial update (null fields unchanged); `400` invalid edits; `409` identifier/name conflicts; the reversible `ACTIVE`/`ARCHIVED` transition |
| POST | `/api/v1/applications/{id}/versions` | `app:versions:write` at the application | `201` version view (born `DRAFT`, semver-style name, per-platform artifact slots empty until E5); `400` invalid name; `409` `VERSION_NAME_TAKEN` / `APPLICATION_ARCHIVED`; `404` unknown or denied application (concealed); honors `Idempotency-Key` (S4-01) |
| GET | `/api/v1/applications/{id}/versions` | `app:versions:read` at the application | `200` limit/cursor page, newest first by the normalized semver sort key, optional conjunctive `tag` filter (S4-02); the default window is the working set (`DRAFT` and the M2-reserved `ACTIVE`) — the terminal states return with the comma-separated `include` filter, `archived` and/or `revoked` (S4-03, unknown values `400`); `404` unknown or denied application (concealed) (S4-01) |
| GET | `/api/v1/applications/{id}/versions/{versionId}` | `app:versions:read` at the application | `200` version detail with the empty release-history section (M2 fills it); `404` unknown version or application (concealed) (S4-01) |
| PUT | `/api/v1/applications/{id}/versions/{versionId}/tags` | `app:versions:write` at the application | `200` tags view (replace-set: trimmed, UPPER-normalized, deduplicated, at most 20 distinct values; empty clears; inherently idempotent, no `Idempotency-Key`); `400` `INVALID_REQUEST` on malformed tags or over-cap sets; `409` `VERSION_ARCHIVED` / `VERSION_REVOKED` on a read-only terminal version (S4-03); `404` unknown version or application (concealed); a real change is audited `VERSION_TAGS_CHANGED` (S4-02) |
| POST | `/api/v1/applications/{id}/versions/{versionId}/archive` | `app:versions:write` at the application | `200` version detail (now `ARCHIVED`: read-only, out of the default listing — `include=archived` returns it — fully readable forever); `409` `VERSION_ARCHIVED` on a repeat, `VERSION_REVOKED` past the stronger terminal; `404` unknown version or application (concealed); audited `VERSION_ARCHIVED`; no `Idempotency-Key` — the state is the replay answer (S4-03) |
| POST | `/api/v1/applications/{id}/versions/{versionId}/revoke-distribution` | `app:versions:write` at the application | `200` version detail (now `REVOKED`: new downloads blocked, nothing deleted, still readable per authorization — INV-006); `400` missing/blank/oversized `reason` (1..1000 characters, trimmed, mandatory); `409` `VERSION_REVOKED` on a repeat; `404` unknown version or application (concealed); audited `VERSION_DISTRIBUTION_REVOKED` with the reason (FR-024); reachable from `DRAFT`, `ACTIVE`, and `ARCHIVED` (S4-03) |
| GET | `/api/v1/applications/{id}/versions/{versionId}/history` | `app:versions:read` at the application | `200` audit history: version summary, chronological status timeline (creation's `DRAFT` row, every archive, every revoke with its reason), and the chronological artifact section (empty until E5); `404` unknown version or application (concealed); read-only forever (S4-03) |

Identifier and lifecycle edits are audited (`APPLICATION_CREATED`,
`APPLICATION_UPDATED`) with before/after pairs; see
[error codes](error-codes.md) for the full application table and
[API conventions](api-conventions.md) for the idempotency registry.

## Artifact upload endpoints (S5-01)

Artifact upload (issue #20) is the `DEVELOPER` story — the role's
`app:artifacts:upload` grant — checked at the **version** every route
carries: the surface is body-addressed (the version id rides the
multipart form or the JSON body, never the path), so the evaluator
resolves the target version -> application -> owning tenant with the
organization overlay, and a caller without the grant on that
application receives the concealed 404 — indistinguishable from an
unknown version id. The same union rule as everywhere applies: an
organization-level `DEVELOPER` reaches the artifact surface of every
tenant of their organization.

| Method | Path | Permission | Result |
|---|---|---|---|
| POST | `/api/v1/artifacts` | `app:artifacts:upload` at the form's `version_id` | `201` registered artifact view (born `VALIDATING`, in-stream SHA-256, `duplicateOf` informational); `400` invalid fields/platform not registered; `415` `ARTIFACT_FILE_TYPE_REJECTED`; `413` `ARTIFACT_TOO_LARGE` (ceiling or over the resumable threshold); `409` `ARTIFACT_BUILD_TAKEN` / `APPLICATION_ARCHIVED` / `VERSION_IMMUTABLE`; `404` unknown or denied version (concealed); honors `Idempotency-Key` |
| POST | `/api/v1/artifacts/upload-sessions` | `app:artifacts:upload` at the body's `versionId` | `201` session view (`artifactId`, `uploadUrl`, `expiresAt`); same guard codes as the multipart path, plus `400` on a non-positive `sizeHint` and `413` on an over-ceiling hint; `404` unknown or denied version (concealed); honors `Idempotency-Key` |
| GET | `/api/v1/artifacts/{artifactId}?version_id={versionId}` | `app:artifacts:read` at the query's `version_id` | `200` artifact view with the extraction tier (`bundleId`, `versionName`, `minimumOs`, `buildNumber`, `extraction.status` — always `PENDING` in an upload response, settled here: `SUCCESS`/`FAILED`/`BLOCKED` with `errorCode`/`errorMessage`); `404` unknown artifact under the caller's own version (`ARTIFACT_NOT_FOUND`) or unknown/denied version (concealed `VERSION_NOT_FOUND`) (S5-02, issue #21) |
| POST | `/api/v1/artifacts/{artifactId}/finalize` | `app:artifacts:upload` at the body's `versionId` | `201 created=true` first registration, `200 created=false` replaying the registered artifact on retry; `400` no bytes/empty; `410` `UPLOAD_SESSION_EXPIRED`; `413` over-ceiling stored bytes; `409` `VERSION_IMMUTABLE` (freeze re-checked at finalize) / `APPLICATION_ARCHIVED`; `404` unknown artifact, session of another version, or denied version (all concealed, identical envelope); no `Idempotency-Key` — the state is the replay answer |

Uploads of a frozen version are refused before any byte moves, and
finalize re-checks the freeze — a version revoked mid-upload fails
closed. See [API conventions](api-conventions.md) for the upload
shapes and [error codes](error-codes.md) for the artifact table.

## Audit events

`ORG_MEMBER_ROLE_CHANGED` and `TENANT_MEMBER_ROLE_CHANGED` carry
`role.before`/`role.after` and `sessionsRevoked`; `TENANT_MEMBER_ADDED`
carries the assigned role and birth status; `TENANT_MEMBER_STATUS_CHANGED`
carries `status.before`/`status.after` and `sessionsRevoked`;
`ORG_MEMBER_REMOVED` and `TENANT_MEMBER_REMOVED` carry the removed
role; `TOKEN_REVOKED` carries the machine-readable reason. See
[authentication](authentication.md) for the identity-side events.

## The isolation & authorization suite (S7-05)

The standing proof that the invariants hold CONTINUOUSLY — the M1
capstone harness (`backend/src/test/java/com/shipperdog/api/isolation/`)
that fails the build on any tenant-isolation or authorization
regression. It lives in the standard test tree, so `mvn verify` —
backend CI's single build-and-test step — runs it on every PR as a
blocking stage, and the whole suite carries the JUnit tag
`isolation-suite`, so it also runs as one named stage locally:

    mvn test -Dgroups=isolation-suite

### Structure

| Class | Proves |
|---|---|
| `IsolationScenarioHarness` | The seeded world: two disjoint org/tenant/application/version/artifact sides, each with platform-admin, ORG_ADMIN, TENANT_ADMIN, and linked-tester personas plus a named tester group. One shared Spring context and one singleton container set (PostgreSQL, Redis, GCS emulator) for the whole family — the suite's five-minute CI budget goes to scenarios, not booting the stack per class. |
| `IsolationEndpointRegistry` | The endpoint catalog: every M1 read surface as a declarative entry (URI builders, per-persona own-side expectations, the genuine-miss call). |
| `IsolationEndpointCatalogTest` | The OpenAPI completeness guard: every GET route the served spec declares must be in the catalog or on a justified allowlist — a new read endpoint fails the suite until it joins the matrix, and a removed route flags its stale entry. |
| `CrossTenantAccessMatrixTest` | INV-001 + the M1 exit criterion: for every (persona x tenant x endpoint) — foreign reads are the concealed miss byte-identical to the genuine one (no data, no existence leak), own reads answer correctly, the platform wildcard reaches across (sanctioned), the tester persona conceals through the decision API, and no denial body carries a foreign identifier. |
| `ExplicitAuthorizationBypassTest` | INV-005: the decision engine is the only download authority — since S8-04 the issuance route mints the URL only on ALLOW (the decision's ALLOW carries the artifact id, never the storage key, never a URL to a persona without engine authority); every persona without engine authority conceals; the API's storage egress is the signed URL alone (proxy token guesses fail closed; buckets private). |
| `HistoricalAvailabilityTest` | INV-006: explicit old-version grants survive every newer arrival; LATEST_N windows slide without truncating explicit grants; TAG grants follow a moved tag; ARCHIVED versions stay readable AND downloadable (the soft terminal — revoke-distribution, pinned by the S7-04 suite, is the state that stops downloads). |
| `RevocationAndTtlTimingTest` | INV-007: revocation denies the very NEXT decision through a warm cache; a short-TTL grant flips to denied at its expiry (evaluation-time) and lands in the revocation report as EXPIRED; bulk revocation denies every formerly granted version on the next decision. Since S8-04 the signed-URL TTL bound is enforced structurally: the issuance service fails fast at startup unless the URL TTL sits at or under the decision-input cache TTL (and FR-014's 15-minute ceiling), so a minted URL can never outlive the revocation backstop. |
| `DegradedModeMatrixTest` | The cache-degraded half: with Redis unreachable, allow/deny/concealment/revocation-immediacy hold unchanged — the cache can only make decisions slower, never different. |
| `DecisionLatencyGuardTest` | The §42 SLO guard: warm decision p99 under 100 ms over a 200-probe burst (the milestone exit criterion's automated half; `authorization_decision_duration` remains the production observation point). |
| `OpaqueSemanticsHardeningTest` | The carried hardening proofs, folded here from #26-#28 and the #33 review: the platform wildcard receives the concealed miss on unknown applications/versions (never a 500); tester-grain denials render `TESTER_NOT_FOUND`; the assignRole insert race collapses to the winner (never a dead-catch 500); the grant-create/revocation triple race answers stable codes (never a 500). The deterministic unit counterpart of the last mapping is `VersionAccessGrantServiceCreateRaceTest`. |

### How to add a scenario

1. Extend `IsolationScenarioHarness` (you get the shared context, the
   container set, and the HTTP plumbing). Provision a fresh world —
   `World world = world()` for the two-tenant stage, or
   `tenant("slug")` for more sides; every call mints unique rows, so
   tests stay order-independent.
2. Shape state through the public API only — `grantExplicitVersion`,
   `grantLatestN`, `revokeGrant`, `createVersion`, `readyArtifact`,
   or any route via `get`/`post`/`put`/`exchange`. Fixtures that
   write rows directly prove nothing.
3. Assert the invariant over EVERY surface it governs. If a new
   read/download route is involved, register it in
   `IsolationEndpointRegistry.readSurfaces()` (one entry: URI
   builders, per-persona own statuses, the genuine-miss call) — the
   completeness guard will remind you by failing otherwise.
4. Mind the permission grains when stating own-side expectations:
   `app:` permissions resolve through the owning organization
   (ORG_ADMIN/APP_OWNER), `tenant:` through the tenant role — the
   catalog entries state what each persona legitimately receives.
5. Tag your class by extending the harness (the `isolation-suite`
   tag is inherited); run `mvn test -Dgroups=isolation-suite`.

### Documented bounds

- Since S8-04 signed URLs exist: the artifact module's issuance route
  mints them on ALLOW only, and the URL TTL is bounded at startup at
  or under the decision-input cache TTL (the grant-expiry bound the
  suite pins structurally). The decision API's ALLOW carries the
  artifact id only — the storage key is server-internal, and no
  persona without engine authority is ever handed a URL.
- M1 versions are born DRAFT (distribution rings are M2): console
  artifact reads of undistributed versions deny for non-platform
  personas by the engine's distributability rule — the suite pins
  this shape rather than blessing an upload-era read.
- The platform administrator persona passes the ENGINE's own layers
  (the wildcard is an authorization there too); RBAC roles never
  reach the self-scoped decision API.
- The cross-tenant matrix probes one direction — side A's personas
  addressing side B's data — because the harness's two sides are
  provisioned symmetrically: the concealment path exercised is the
  same code, the same personas, and the same route shape with the
  identifiers swapped. The reverse direction is the same proof with
  relabeled inputs, not a distinct code path.
- The degraded-mode matrix replays the core allow/deny/concealment
  probes against its own broken-cache context — not the full endpoint
  catalog. It cannot share the harness context (its Redis
  configuration is deliberately unreachable), so it carries its own
  container set and pins the decisions the degraded contract governs:
  the cache may only make decisions slower, never different.

## Extension path

`UserRolesSnapshot` is the seam the ABAC engine (S14-04) extends with
resource attributes; `app:` permissions bind to endpoints as their
owning modules land (the registry since S3-01; `app:versions:read` and
`app:versions:write` since S4-01; `app:artifacts:upload` since S5-01;
the rest of E5 onward). The authorization engine series
(S7-01..S7-05) builds policy administration on this catalog without
changing the enforcement point. Since S7-01 the layered engine
([above](#the-layered-authorization-engine-s7-01)) consumes the
catalog through the role-overlay contributor; S7-02's grants
(`version_access`, FR-011's four shapes) are registered as the second
entitlement contributor (see
[Authorization grants](#authorization-grants-s7-02)), S7-03 exposes
the first-party decision API with the input cache against the
latency SLO ([above](#the-decision-api-s7-03)), and S14-04's
ABAC attributes widen `AuthorizationContext` — the structured-inputs
seam S7-01 left open.
