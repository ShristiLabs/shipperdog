# Releases

The release surface (S9-01, issue #55; PRD 8.6, FR-015): a release is
the controlled distribution event everything in M2 orbits — created
from a version's artifacts, targeting tenants + tester groups + a ring,
so distribution is an explicit governed act rather than "upload and
hope". This page covers the model, the creation API and its validation,
the reads, the lifecycle (S9-02, issue #56: PRD 17's state machine,
its transitions API, history, and the rolled-back download cutoff),
the frozen manifest (S9-03, issue #57: PRD 16's reproducibility
document and its diff), and the two distribution probes the S9-01
landing bound.

## Model

A release distributes exactly one version of one application:

```text
Release R-1001
Application: Banking
Version: 7.12.0
Artifacts:   IOS <artifact>, ANDROID <artifact>    (one per platform)
Ring:        tenant-pilot
Targets:     Acme (groups: Pilot Group), Contoso
Status:      DRAFT
```

- **DRAFT is the state creation writes**; the full state machine (PRD
  17) has run since S9-02 — see [the lifecycle](#lifecycle--the-state-machine-s9-02).
  The manifest (PRD 16) has run since S9-03 — see
  [the frozen manifest](#the-frozen-manifest-s9-03); approval
  workflows (FR-016) have run since S10-01 — see
  [approval workflows](#approval-workflows-s10-01); rings' promotion
  behavior (PRD 8.10) is the rest of E10.
- **One release per application and version** (V1's
  `uq_releases_application_version` is the authority; a duplicate is the
  precise `409 RELEASE_ALREADY_EXISTS`). A version can exist without a
  release; releases reference versions, never the reverse.
- **The quotable id** (`releaseRef`, `R-1001` style) is a single global
  sequence (`release_number_seq`, V24) rendered as `R-<number>` — the
  human vocabulary for chat, tickets, and audit trails. Routes address
  releases by uuid like every platform resource; every view carries the
  `releaseRef` beside it.
- **Rings** are reference data (`rings`, V24): the S10-04 ring *data
  model* pulled forward per the story's sequencing note, seeded with
  FR-017's five system defaults — `engineering` (0), `qa` (1),
  `tenant-pilot` (2), `uat` (3), `production` (4), NULL-organization
  rows visible to every organization. Ring *behavior* (eligibility
  rules, approval policies, gates) stays in E10; an organization's
  custom rings will carry its `organization_id` then. Creation resolves
  a ring by code (case/whitespace normalized) within the organization's
  vocabulary — the organization's own ring wins over the system default
  — and an unknown code is the `400 RELEASE_RING_UNKNOWN`.

### Tables (V24)

| Table | Shape | Isolation standing (ADR-0002) |
|---|---|---|
| `rings` | uuid PK, nullable `organization_id` (NULL = system), `code`, `name`, `position` | Organization-grain reference data — plain repository, no seam |
| `releases` (V1 + V24 + V27) | + ownership snapshot (`organization_id` NOT NULL, `tenant_id` nullable), `release_number` (sequence, unique), `starting_ring_id` FK, optional `environment_id` FK (S9-04) | Addressed by its own routes — ninth amendment: full snapshot, seam-served (`ReleaseEntity` implements `OrganizationScope`) |
| `release_targets` (V1 + V24) | PK `(release_id, tenant_id)`; the V1 `ring_id` varchar renamed to legacy nullable `ring_code`; real `ring_id` FK NOT NULL | Plain child of the release (eighth amendment) — no snapshot, no seam repository, parent-first paths |
| `release_target_groups` | PK `(release_id, tenant_id, group_id)`, composite FK to its target | Via-parent junction (eleventh amendment) |
| `release_artifacts` | PK `(release_id, artifact_id)`, `platform`, `uq (release_id, platform)` | Plain child of the release |

## Creation — `POST /api/v1/releases`

Body-addressed (the artifact-collection precedent): the application id
rides the JSON body, so the permission check targets the application
(`app:releases:manage`, held by APP_OWNER and RELEASE_MANAGER), denials
conceal as the uniform `APPLICATION_NOT_FOUND`, and the service
establishes the owning scope through the sanctioned executor once the
application resolves. Honors `Idempotency-Key`.

```json
POST /api/v1/releases
{
  "applicationId": "…",
  "versionId": "…",
  "ring": "tenant-pilot",
  "environmentId": null,
  "artifacts": [
    { "platform": "IOS", "artifactId": "…" },
    { "platform": "ANDROID", "artifactId": "…" }
  ],
  "targets": [
    { "tenantId": "…", "testerGroupIds": ["…"] },
    { "tenantId": "…", "testerGroupIds": [] }
  ]
}
```

Validation order (identity first, nothing written until everything
passes; the whole write is one transaction):

1. **Version** — must belong to the application, and must be releasable:
   `DRAFT` or `ACTIVE`. An ARCHIVED version answers the version
   module's `409 VERSION_ARCHIVED`, a REVOKED one `409 VERSION_REVOKED`
   (distribution revoked is the whole point of the wall); an unknown,
   foreign, or other-application version is the concealed
   `404 VERSION_NOT_FOUND`. An ARCHIVED *application* answers
   `409 APPLICATION_ARCHIVED` (the registry's reserved code, this
   surface's own value).
2. **Ring** — resolves in the organization's vocabulary or is the
   `400 RELEASE_RING_UNKNOWN` (creation is blocked until ring
   definitions exist — the sequencing note; the V24 seed provides the
   defaults).
3. **Environment** (optional, S9-04/issue #58) — when the body names
   an `environmentId` it must belong to the application's organization
   (a foreign environment is the concealed `404
   ENVIRONMENT_NOT_FOUND`, byte-identical to an unknown id) and be
   ACTIVE (a DISABLED environment is the `409 ENVIRONMENT_DISABLED`);
   when present, every target tenant must also sit inside the
   environment's assignments — the per-tenant refusal joins step 5's
   single `422 RELEASE_TARGETS_INVALID` ("not assigned to the release
   environment"; deny-by-default — see
   [environments](environments.md)).
4. **Artifacts** — at least one, one per platform, every artifact READY
   (S5-05's vocabulary) and belonging to the release's version:
   not-READY is one `422 RELEASE_ARTIFACT_NOT_READY` naming every
   offending platform in the `errors` member; an artifact of another
   version or application is the concealed `404 ARTIFACT_NOT_FOUND`
   (the artifact read's own shape); structural mistakes (empty list,
   duplicate platform, unparseable platform, duplicate artifact,
   platform/artifact mismatch) are `400 INVALID_REQUEST`.
5. **Targets** — at least one, tenants distinct: every target tenant
   must hold the application — tenant-owned by it, or organization-
   shared with an ACTIVE authorization (the S3-03 effective-visibility
   rule) — and every tester group must belong to its target tenant.
   Every offense accumulates into ONE `422 RELEASE_TARGETS_INVALID`
   keyed by tenant or group id in the `errors` member, so a Release
   Manager fixes the whole set in one pass. An empty `testerGroupIds`
   targets the tenant at large.

The response is `201` with the detail — the quotable id, ring, artifact
slots, and the full target set. Creation is audited as
`RELEASE_CREATED` carrying the complete target set (FR-024): ring,
per-platform artifacts, and every tenant with its group count.

## Reads

- `GET /api/v1/releases?applicationId=…` — the listing (AC5):
  `applicationId` required (the permission target and the listing's
  scope, `app:releases:read`), conjunctive optional filters `versionId`,
  `status`, `tenantId`; newest (quotable number) first; standard
  limit/cursor pagination. The tenant filter is a subquery over the
  target rows composed with (never instead of) the seam's scope
  predicate.
- `GET /api/v1/releases/{releaseId}?applicationId=…` — the detail: the
  full creation record. A release of another application is the
  concealed `404 RELEASE_NOT_FOUND`, byte-identical to an unknown id.

## Lifecycle — the state machine (S9-02)

A release moves through PRD 17's diagram and nothing else — the table
is exhaustive, pinned by a unit test over all 121 state pairs and an
API-level matrix test that walks a release into each source state and
attempts every target:

```text
DRAFT → VALIDATING → READY → APPROVAL_PENDING → APPROVED
      → DEPLOYING → ACTIVE → (PROMOTED → COMPLETED | ROLLED_BACK)

VALIDATING → FAILED        (with reason)
DEPLOYING  → FAILED        (with reason)
```

Eleven legal edges. FAILED, ROLLED_BACK, and COMPLETED are terminal —
no state exits a terminal, no state re-enters itself, and NO state
re-opens artifact mutation (the freeze from creation is absolute).
"Cannot enter DEPLOYING without APPROVED" and "cannot complete without
ACTIVE" are the table itself: DEPLOYING's only source is APPROVED,
COMPLETED's only source is PROMOTED, and PROMOTED's only source is
ACTIVE.

### Transitions — `POST /api/v1/releases/{releaseId}/transitions`

Body-addressed like every release route (`applicationId` in the body
is the permission target, `app:releases:manage`); the body names the
target state and, for the failure and rollback edges, the mandatory
reason. Not idempotency-keyed — the state is the replay answer.

```json
POST /api/v1/releases/{releaseId}/transitions
{
  "applicationId": "…",
  "to": "ROLLED_BACK",
  "reason": "crash spike in the field; pulling the direct channel"
}
```

- **Illegal crossing** — `409 RELEASE_TRANSITION_INVALID` with the
  `errors` member carrying `current` and `attempted` (a conflict with
  stored state; the 422 family stays for request semantics).
- **Missing reason on FAILED/ROLLED_BACK** — `422
  RELEASE_TRANSITION_REASON_REQUIRED` (the S4-03 revoke analog: a
  release that died or was pulled explains itself; the reason rides
  both the audit event and the timeline row). A reason is optional on
  every other edge; a blank one means none.
- **Unknown `to` value** — `400 INVALID_REQUEST` keyed by `to`.
- **Racing transitions** — serialized by the database compare-and-set
  (the V14 CAS discipline): the row moves only while its committed
  status is still the source state this attempt read, so two racing
  crossings can never both commit, double-write the timeline, or
  un-terminal a release; the loser re-reads and answers the precise
  409.
- **Every committed crossing** appends one `release_status_history`
  row, audits `RELEASE_TRANSITIONED` (FR-024: actor, before/after,
  reason), and publishes a `ReleaseTransitionedEvent` in-process — the
  event hook S12-01's bus subscribes to, so the machine never grows
  delivery concerns.

**The guard seam.** Every registered `ReleaseTransitionPolicy`
contributor is consulted before a legal crossing commits; a refusal is
`422 RELEASE_TRANSITION_BLOCKED` keyed by the contributor's name.
Since S10-01 the seam's first registrant ships: the approval
engine's `ReleaseApprovalPolicy` refuses APPROVAL_PENDING to APPROVED
while the release's latest approval round is PENDING or REJECTED and
permits when it is SATISFIED — or when no round exists, the ungated
crossing an organization that configured no workflow keeps (see
[approval workflows](#approval-workflows-s10-01)). Later
gates/distribution preconditions (E11/E13) compose on the same seam
without touching the machine. Policies refuse; they can never widen
PRD 17's graph.

**The FAILED retry path (documented decision).** A FAILED release
never re-enters the machine, and its artifacts cannot be fixed in
place: the artifact set froze the moment the release was created
(INV-004). The fix path is a NEW version (carrying corrected
artifacts) and a new release of it — the failure row and its reason
stay as permanent history. Rollback of the direct channel is the same
posture: `ROLLED_BACK` is terminal in M2 (store-channel rollback
orchestration is S13-06).

### History — `GET /api/v1/releases/{releaseId}/history?applicationId=…`

The append-only timeline (`app:releases:read`): the release summary
plus one row per real transition, chronological — the creation's DRAFT
row (`from` null, the creator as actor) and every committed crossing
with its actor, its reason where present, and the timestamp. This is
the record the S9-05 dashboard renders; nothing ever updates or deletes
a row (PRD P5). A release of another application conceals exactly like
an unknown id.

## Approval workflows (S10-01)

Nothing reaches testers or production without the right sign-offs
(FR-016, issue #40): an organization configures approval chains as
DATA — ordered steps, each demanding a role — and the engine gates the
APPROVAL_PENDING to APPROVED crossing through the S9-02 policy seam
until the chain is satisfied. Sequential only in this story; parallel,
any-of, and conditional modes are S10-02 (mode flags extend the same
tables, the story's technical note).

### The model

```text
Approval workflow "standard-chain"        (organization-grain)
Ring:        qa                            (direct attachment)
Steps:       1 QA_ENGINEER
             2 SECURITY_REVIEWER
             3 APP_OWNER                  (Product — PRD 7.3)
             4 RELEASE_MANAGER            (the final sign-off — PRD 7.6)
             (each step optionally narrowed to a tenant the release
              must target — a skipped step is never a failure)
```

- **Definitions** (`POST/GET /api/v1/approval-workflows`,
  `org:approval-workflows:manage/read` — the Release Manager, App
  Owner, and Org Admin personas): a lowercase-kebab binding NAME and
  an optional ring attachment; one name and one ring attachment per
  organization (409). Steps come from the five-role approval
  vocabulary — exactly the roles the RBAC matrix grants an
  `app:releases:approve-*` permission (`QA_ENGINEER`,
  `SECURITY_REVIEWER`, `COMPLIANCE_REVIEWER`, `APP_OWNER`,
  `RELEASE_MANAGER`), each at most once per chain, 1-10 steps.
- **Resolution** happens at the READY to APPROVAL_PENDING crossing,
  most specific first: the targeted environment's `approvalPolicy`
  NAME binding (V27's reserved reference — the environment update
  surface binds it) resolves against the organization's workflow
  names; else the starting ring's attached chain. Neither resolves ->
  no round, no gate: approval workflows are CONFIGURABLE (FR-016),
  and an unconfigured organization keeps the ungated machine S9-02
  shipped. A workflow defined AFTER a release crossed into
  APPROVAL_PENDING never retro-opens its gate.
- **Rounds** instantiate inside the crossing's transaction (the
  event-seam composition the manifest freeze uses): round 1 with one
  `release_approvals` record per applicable step (PRD 13's table —
  `approval_type` is its vocabulary: `QA`, `SECURITY`, `PRODUCT`,
  `COMPLIANCE`, `RELEASE_MANAGER`, 1:1 with the step roles; roles name
  WHO signs, types name WHAT they sign), plus one
  `ApprovalRequestedEvent` per step — S12-01's notification feed. A
  tenant-scoped step whose tenant the release does not target is not
  instantiated (skipped, never failed); a round whose every step
  scoped away stands SATISFIED by the same act.
- **Decisions** (`POST /api/v1/releases/{releaseId}/approvals`,
  body `{applicationId, decision: APPROVE|REJECT, step?, reason?}`):
  the chain's CURRENT step only — an out-of-order or already-decided
  assertion is `409 APPROVAL_STEP_OUT_OF_ORDER` carrying `current`
  and `attempted` (AC2). Authority is precise: the route's coarse
  gate is the release read authority every approver role holds (the
  evaluator's throwing concealment makes permission disjunctions
  unusable as gates), and the service checks the step's mapped
  `app:releases:approve-*` permission through the programmatic
  application-grant probe — a caller who holds no approval authority
  gets `403 APPROVAL_ROLE_NOT_HELD` naming the required role and
  permission. The release's CREATOR signs nothing (AC5,
  `403 APPROVAL_SELF_APPROVAL`). REJECT requires its reason (AC6,
  `422 APPROVAL_REJECT_REASON_REQUIRED`). Every decision is audited
  (`RELEASE_APPROVAL_GRANTED`/`REJECTED`, reason included) and the
  signature is a fact: `approver` + `approver_role` recorded at
  decision time — roles change later, the record does not. Decided
  exactly once (the repository's compare-and-set); racing approvers
  can never double-sign.
- **Rejection blocks progression** (AC3): a rejected round is
  terminal, the release STAYS APPROVAL_PENDING with the rejection
  recorded, and the APPROVED crossing keeps refusing (422 naming the
  rejected round). The documented rework path is a NEW round — never
  an un-reject: `POST /api/v1/releases/{releaseId}/approvals/rounds`
  (`app:releases:manage` — restarting a chain is release management,
  not a signature; only a REJECTED round restarts, 409 otherwise)
  instantiates round N+1 of the SAME frozen definition, one request
  event per step, the optional note audited.
- **All steps complete** (AC4): the last approval satisfies the round
  and the crossing becomes fireable through the ordinary transition
  route — the state machine stays the single writer of release status
  (one authority, one audit trail), and the manifest freezes the
  satisfied chain into `approval.required` at that same crossing.
- **The read** (`GET /api/v1/releases/{releaseId}/approvals?applicationId=…`,
  `app:releases:read`): every round with its steps and signatures —
  the rejected rounds and their reasons beside the live or satisfied
  one, the full audit-read of the approval moment. Foreign releases
  conceal exactly like unknown ids.
- **INV-008 discipline** (approvals never mutate artifacts): approvals
  run against the artifact set INV-004 froze at CREATION, and the
  manifest — approval block included — is the document OF the APPROVED
  crossing; the chain the policy verified and the chain the manifest
  froze are the same facts by construction.

## The frozen manifest (S9-03)

Every release carries PRD 16's reproducibility artifact: a complete,
immutable record of WHAT it ships, frozen at the moment the state
machine crosses into APPROVED and never touched again. The manifest
answers the compliance question — "exactly what did Acme receive on
October 4?" — from its own stored bytes:

```text
Manifest of R-1001
Release:        Banking 7.12.0 (R-1001)
Artifacts:      IOS build 421, sha256 a1a1…, 1024 bytes, ios-421.ipa
Targets:        Acme (groups: Pilot Group), Contoso
Ring:           tenant-pilot (Tenant Pilot, position 2)
Environment:    null                       (S9-04 owns environments)
Rollout:        percentage null            (E10 owns staged rollouts)
Configuration:  configVersion/featureFlags/tenantConfig null
                (S9-04's versioned config, resolved values when it lands)
Approval:       required [QA, SECURITY, PRODUCT,
                RELEASE_MANAGER]     (S10-01; empty for an
                ungated release)
sha256:         63061b4b…                  (canonical content digest)
frozenAt/By:    the APPROVED crossing's moment and actor
```

- **Freeze = the APPROVED crossing** (AC1). S9-02 reserved exactly this
  composition seam: a listener on `ReleaseTransitionedEvent` freezes
  inside the crossing's committing transaction, so a release is never
  APPROVED without its manifest and an assembly failure rolls the
  whole crossing back. Immutability is structural — V26's
  one-per-release unique constraint, an insert-and-read-only
  repository, an entity without setters, and a forward-only graph that
  cannot re-enter APPROVED. There is no mutation route (PUT/POST on
  the manifest path answer 405); `RELEASE_MANIFEST_IMMUTABLE` (409)
  exists so any future surface attempting a re-freeze answers loudly
  instead of overwriting.
- **Deep copy, never lazy references** (AC2). Artifacts are pinned by
  CONTENT (sha256, build number, byte size, filename, upload moment —
  the INV-003-immutable fields), targets by tenant id with their group
  selections, the ring by its frozen code/name/position, the
  application and version by name-at-freeze. Since S9-04 (issue #58)
  a release naming an environment freezes its CODE and its
  configuration snapshot's RESOLVED values — the version and the
  complete key-value map, never pointers (the story's technical
  note; the #57 follow-up discharged). Since S10-01 (issue #40) the
  approval block freezes the satisfied chain verbatim — the approval
  types that authorized the crossing, in chain order, through the
  `ReleaseApprovalSource` seam (empty for a release no workflow
  gated); the rollout percentage keeps its honest null until E10's
  staged rollouts — the document's shape is complete, and a manifest
  frozen before a block's story landed stays a valid document of its
  time forever.
- **Stored, never recomputed** (AC3). `GET
  /api/v1/releases/{id}/manifest?applicationId=…` serves the stored
  document; a later application rename or any world drift cannot
  rewrite history. `format=yaml` renders the same stored document as
  YAML (`application/yaml`) — one document, two serializations; the
  default JSON is the other. A release that has not crossed into
  APPROVED answers the honest `404 RELEASE_MANIFEST_NOT_FOUND`; a
  foreign release conceals as the standard `RELEASE_NOT_FOUND`,
  byte-identical to an unknown id.
- **The digest** is the SHA-256 over the canonical serialization
  (object keys sorted recursively, ISO-8601 instants, nulls included
  — jsonb's key reordering never reaches the hash). It is recorded in
  the freeze's `RELEASE_MANIFEST_FROZEN` audit event (AC4), and every
  read re-derives it from the stored content: a mismatch is the loud
  `500 RELEASE_MANIFEST_CORRUPT` — the platform detects drift loudly
  and never serves, repairs, or rewrites a document that fails
  verification (INV-003/004 discipline applied to the manifest
  itself; an operator investigates).
- **The diff** (AC5): `GET /api/v1/releases/{id}/manifest/diff?
  applicationId=…&otherReleaseId=…` answers what moved between two
  APPROVED freezes of the same application — artifact pins per
  platform (ADDED/REMOVED/CHANGED with both sha256/build facts), the
  target set (tenants added/removed, per-tenant group changes), and
  the ring/environment/rollout/configuration blocks that differ. A
  statement about two frozen moments, never about live state; the
  path release is the FROM side, `otherReleaseId` the TO; a self-diff
  is the `400` keyed by `otherReleaseId`.

### The rolled-back download cutoff (AC4)

The authorization engine's distribution layer (S7-01) reserved a
policy seam for exactly this: the release module registers
`RolledBackCutoffPolicy`, which blocks `DOWNLOAD` decisions for a
version whose release is ROLLED_BACK — the engine never learns release
schemas, and the module never touches the engine's chain. Downloads
deny as the download route's concealed miss shape
(`DISTRIBUTION_BLOCKED` on the decision audit trail); the version, its
artifacts, and the release's whole history stay readable (INV-006/007
— rollback never deletes; the portal shows what was pulled, it just
refuses to serve it again). One release exists per application and
version, so the version-grain cutoff is exact.

## The two distribution probes this landing binds

- **INV-004 artifact-set freeze (issue #19's contributor seam).** The
  release module registers on `VersionDistributionContributor`: the
  moment a version carries any release — DRAFT included — its artifact
  set is frozen (`409 VERSION_IMMUTABLE` on uploads). History freezes:
  releases are never deleted.
- **The ownership-scope precondition (issue #14's reserved
  zero-distributions rule).** A release anchors the application to its
  owning dimension in BOTH directions of the registry's scope
  transition: `409 APPLICATION_RELEASES_PRESENT` on
  TENANT-to-ORGANIZATION (the releases targeted the owning tenant's
  scope) and on ORGANIZATION-to-TENANT (the releases targeted authorized
  tenants a conversion would cut off). Any lifecycle state blocks;
  releases are distribution history.

## Error codes

See [error codes](error-codes.md) for the registry; the release
section carries `RELEASE_NOT_FOUND`, `RELEASE_ALREADY_EXISTS`,
`RELEASE_RING_UNKNOWN`, `RELEASE_TARGETS_INVALID` (the platform's first
422 family, with the `errors` member), `RELEASE_ARTIFACT_NOT_READY`,
the transition family (S9-02): `RELEASE_TRANSITION_INVALID` (409,
current/attempted), `RELEASE_TRANSITION_REASON_REQUIRED` (422), and
`RELEASE_TRANSITION_BLOCKED` (422, the policy seam), and the manifest
family (S9-03): `RELEASE_MANIFEST_NOT_FOUND` (404, the honest
state-dependent miss), `RELEASE_MANIFEST_IMMUTABLE` (409), and
`RELEASE_MANIFEST_CORRUPT` (500, the loud integrity failure), and the
S9-04 environment values `ENVIRONMENT_NOT_FOUND` (404, the concealed
environment miss of creation) and `ENVIRONMENT_DISABLED` (409).
