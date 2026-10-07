# Releases

The release surface (S9-01, issue #55; PRD 8.6, FR-015): a release is
the controlled distribution event everything in M2 orbits — created
from a version's artifacts, targeting tenants + tester groups + a ring,
so distribution is an explicit governed act rather than "upload and
hope". This page covers the model, the creation API and its validation,
the reads, and the two distribution probes the landing binds.

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

- **DRAFT is the only state this story writes.** The full state machine
  (PRD 17) is S9-02; the manifest (PRD 16) is S9-03; approvals and
  promotion (PRD 8.10's ring behavior) are E10.
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
| `releases` (V1 + V24) | + ownership snapshot (`organization_id` NOT NULL, `tenant_id` nullable), `release_number` (sequence, unique), `starting_ring_id` FK | Addressed by its own routes — ninth amendment: full snapshot, seam-served (`ReleaseEntity` implements `OrganizationScope`) |
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
3. **Artifacts** — at least one, one per platform, every artifact READY
   (S5-05's vocabulary) and belonging to the release's version:
   not-READY is one `422 RELEASE_ARTIFACT_NOT_READY` naming every
   offending platform in the `errors` member; an artifact of another
   version or application is the concealed `404 ARTIFACT_NOT_FOUND`
   (the artifact read's own shape); structural mistakes (empty list,
   duplicate platform, unparseable platform, duplicate artifact,
   platform/artifact mismatch) are `400 INVALID_REQUEST`.
4. **Targets** — at least one, tenants distinct: every target tenant
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
422 family, with the `errors` member), and `RELEASE_ARTIFACT_NOT_READY`.
