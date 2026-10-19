# Dashboards (S11-05)

The console's landing surfaces (issue #50, PRD 29): the organization
dashboard — counts, per-application release health, and the caller's
role-filtered pending actions — and the tenant dashboard — the tenant
block's counts and the in-flight releases targeting the tenant. Both
are read-only aggregates served by the analytics module (FR-029's own
charter: "analytics feed the dashboards") on the S11-04 read-model
discipline: a fixed handful of aggregate SQL statements per view, no
per-row service loops, no N+1 list scans (AC5). No schema of their own
— the dashboards derive everything from the modules' tables
(`applications`, `tenants`, `versions`, `testers`,
`tenant_tester_memberships`, `releases`, `release_targets`, `rings`,
`release_approval_rounds`, `release_approvals`, `release_gates`,
`release_policy_holds`, `tenant_application_authorizations`).

## The API

| Method | Path | Permission | Result |
|---|---|---|---|
| GET | `/api/v1/organizations/{id}/dashboard` | `org:dashboard:read` | `200` the org view (below); `404 ORGANIZATION_NOT_FOUND` concealed for callers without the permission or an unknown organization |
| GET | `/api/v1/tenants/{id}/dashboard` | `tenant:dashboard:read` | `200` the tenant view; `404 TENANT_NOT_FOUND` concealed (foreign or unknown tenant) |
| GET | `/api/v1/tenants/{id}/dashboard/ota/bundles/{bundleId}/funnel` | `tenant:dashboard:read` | `200` the OTA funnel view (below); `404 BUNDLE_NOT_FOUND` concealed (unknown, foreign, or unvisible bundle), `404 TENANT_NOT_FOUND`, `400 INVALID_REQUEST` for an unknown `ring` |
| GET | `/api/v1/tenants/{id}/dashboard/ota/bundles/{bundleId}/health` | `tenant:dashboard:read` | `200` the crash-rate comparison (below); the funnel route's own concealments |

All four routes are members of `IsolationEndpointRegistry` — the isolation
suite's cross-tenant matrix proves the concealment parity, and the
tenant read is scoped by the bound tenant id in every statement (the
route's own permission target). The response carries `generatedAt`;
the console re-reads every 30 seconds (the story's technical note).

### The organization view

```json
{
  "organizationId": "...",
  "generatedAt": "2026-10-04T12:00:00Z",
  "counts": {
    "applications": 24, "tenants": 184, "activeReleases": 7,
    "pendingApprovals": 5, "testing": 12, "production": 31
  },
  "applications": [
    { "applicationId": "...", "name": "Banking", "health": "TESTING",
      "activeReleases": 2, "testing": 2, "production": 0,
      "blockedFindings": 0 }
  ],
  "pendingActions": {
    "approvals": [ { "releaseId": "...", "releaseRef": "R-1028",
      "applicationId": "...", "applicationName": "Banking",
      "approvalType": "QA", "roundNumber": 1, "scopeTenant": null,
      "decidableByCaller": true } ],
    "reviews": [ ... the SECURITY steps, the same shape ... ],
    "failedGates": [ { "releaseId": "...", "releaseRef": "R-1028",
      "applicationId": "...", "applicationName": "Banking",
      "gateType": "AUTOMATED_TESTS" } ]
  }
}
```

**Counts reconcile with the list endpoints** (AC1, pinned by the
integration test): `applications` is the registry listing's both
ownership scopes (`GET /api/v1/applications?organizationId=`), `tenants`
the tenant listing unfiltered, `activeReleases` the non-terminal
statuses (DRAFT..PROMOTED — COMPLETED/ROLLED_BACK/FAILED are terminal),
`pendingApprovals` the APPROVAL_PENDING filter of the release listing,
and `testing`/`production` the ring occupancy of the in-distribution
states (DEPLOYING/ACTIVE/PROMOTED): a release is **testing** while any
of its targets sits outside the effective vocabulary's final ring and
**production** once every target sits at it — rings are per-target
(a promotion widens distribution tenant by tenant), so one release can
be production for one target tenant and testing for another.

### Release health (AC2)

Per application, derived in one rule with a strict priority —
Blocked > Testing > Healthy:

| Status | When |
|---|---|
| `BLOCKED` | any release of the application holds a blocked finding: a FAILED release state, a currently-FAILED gate nobody has overridden (`release_gates.status = FAILED AND overridden_by IS NULL`), or a PENDING policy hold awaiting its acknowledge |
| `TESTING` | not blocked, and an in-distribution release still has a target outside the final ring |
| `HEALTHY` | not blocked or testing, and an in-distribution release's every target sits at the final ring (production-active) |
| `INACTIVE` | nothing in flight (draft-only or release-free application) |

The row carries the counts the health bar renders (`testing`,
`production`, `blockedFindings`) so the console draws the §29 stacked
bar without a second read. The final ring is the effective
vocabulary's highest position — the org-preferred-over-system
resolution the ring module serves, enforced Production-last on every
ring write.

### Pending actions (AC3)

Every item carries the deep-link context (release id and ref,
application id and name) the console routes to the release dashboard
with. The three queues are role-filtered server-side:

- **approvals** — the PENDING steps of the active group of the latest
  PENDING round of each APPROVAL_PENDING release (the evaluator's own
  active-group rule, re-expressed as SQL). A step carries
  `decidableByCaller`: true when the caller's organization role maps
  to the step's approval type (the five-role 1:1 vocabulary) AND they
  did not create the release (the self-approval guard the workflow
  engine enforces at decision time). Non-matching callers see only
  their own decidable steps; the organization administrator and the
  platform wildcard see the whole queue with the flag honestly false
  everywhere (an administrator signs nothing).
- **reviews** — the SECURITY steps of the same active set, rendered
  only for ORG_ADMIN and SECURITY_REVIEWER (plus the platform
  override) — the feedback module's restricted-visibility bar, the
  same standing.
- **failedGates** — releases with a currently-failed, un-overridden
  gate, rendered only for the override and owner personas (ORG_ADMIN,
  RELEASE_MANAGER, APP_OWNER, plus the platform override).

Each list is bounded at 25 items (newest release first).

### The tenant view

```json
{
  "tenantId": "...",
  "generatedAt": "2026-10-04T12:00:00Z",
  "counts": {
    "applications": 4, "availableVersions": 17,
    "activeTesters": 82, "pendingReleases": 2
  },
  "pendingReleases": [ { "releaseId": "...", "releaseRef": "R-1028",
    "applicationId": "...", "applicationName": "Banking",
    "status": "DEPLOYING", "ring": "testing" } ]
}
```

- **applications** — the catalog union the tenant's listing serves:
  tenant-owned plus organization-shared with an ACTIVE authorization.
- **availableVersions** — the working set (DRAFT/ACTIVE) of those
  applications.
- **activeTesters** — the effective view: person record ACTIVE and
  membership ACTIVE (the two-switches rule, live).
- **pendingReleases** — the non-terminal releases targeting the
  tenant, each with its tenant-facing ring label: `production` when
  this tenant's target sits at the final ring, else `testing`.

## The OTA dashboard endpoints (S16-10, issue #172)

The console's OtaDashboardPage reads the S16-09 bundle reads (docs/
[analytics.md](analytics.md), "The OTA bundle reads") over HTTP: the
funnel and the crash-rate comparison, both under the tenant
dashboard's own authority (`tenant:dashboard:read` — TENANT_ADMIN
with the owning organization's overlay; the DEVELOPER persona is
denied, concealed as the 404 miss like every resource-scoped
refusal). The bundle must belong to an application the addressed
tenant can see — the same catalog union the tenant view's
`applications` count serves — and every miss (unknown, foreign, or
unvisible bundle) conceals as the one `404 BUNDLE_NOT_FOUND`; an
unknown tenant is the tenant view's own `TENANT_NOT_FOUND`. The
bundle listing the page's picker needs is no new surface: the
console rides the authoring registry's `GET /api/v1/ota/bundles`.

### The funnel

`GET /api/v1/tenants/{id}/dashboard/ota/bundles/{bundleId}/funnel`
serves the S16-09 `otaFunnel` read whole — one document, the three
panels the page renders:

```json
{
  "bundleId": "...", "bundleVersion": "1.4.2",
  "applicationId": "...", "releaseId": "...", "releaseRef": "R-1028",
  "stages": { "checks": 5, "seen": 5, "downloads": 4,
              "applied": 3, "failed": 1, "rolledBack": 1 },
  "failures": [ { "reason": "checksum_mismatch", "count": 1 } ],
  "adoption": [ { "day": "2026-10-18", "seenDevices": 4,
                  "appliedDevices": 2, "adoptionRate": 0.5 } ]
}
```

The optional `?ring=` filter narrows every leg — stages, failure
breakdown, and curve alike — to the reports that occurred while the
bundle's release occupied the named ring; an unknown ring code is
refused (`400 INVALID_REQUEST` naming `ring`), never a silently
empty funnel. The computation doctrine (adoption's identity rule,
the monotone cumulative curve, attribution-is-history for a detached
bundle) is the S16-09 service's own, pinned there.

### The crash-rate comparison

`GET /api/v1/tenants/{id}/dashboard/ota/bundles/{bundleId}/health`
serves the S16-09 `otaBundleHealth` read: the addressed (new)
bundle beside the application's CURRENT bundle, each side carrying
`sessions`, `crashSessions`, and `crashFreeRate`, with
`crashFreeRateDelta` (new minus current — positive is better). A
side without sessions carries the explicit null (`NO_DATA`), never
a zero, and the delta serves only when both sides are evidenced.

## Permissions

Two authorities joined the catalog with this story (the full matrix in
[authorization](authorization.md)):

- `org:dashboard:read` — ORG_ADMIN, APP_OWNER, QA_ENGINEER,
  SECURITY_REVIEWER, COMPLIANCE_REVIEWER, RELEASE_MANAGER, VIEWER (the
  console's functional roles; the pending-actions panel personalizes
  by role server-side). NOT DEVELOPER (the CLI persona without
  registry read — the release-screen precedent) and not ORG_MEMBER
  (deny-by-default).
- `tenant:dashboard:read` — TENANT_ADMIN, with the owning
  organization's overlay (ORG_ADMIN) above it; nobody else.

## The console surface

- **Organization dashboard** (`/dashboard`, the console's index): the
  six count cards, the health table (stacked production/testing/blocked
  bars; a row click opens the application's releases), and the
  pending-actions panel — every item links to its release screen, and
  a "waiting on you" chip marks the caller's own decidable steps. The
  nav section probes the landing read itself (the `usePermission`
  convention).
- **Tenant dashboard** (`/tenants/{id}/dashboard`, reached from the
  tenants listing's Dashboard action): the four count cards, the
  pending-release list with ring chips, and a downloads-per-day bar
  chart over the last seven days, rendered from the EXISTING tenant
  series (`GET /api/v1/tenants/{id}/metrics?metric=DOWNLOADS&grain=day`)
  through the folding rule below.
- **Charting** is MUI X Charts (`@mui/x-charts`, pinned in the
  9.14.0 line with the console's existing `@mui/x-data-grid`) — the
  MUI ecosystem's own charts package, no new vendor surface; the
  health bars are MUI layout primitives (a charting library would be
  overweight for a stacked count bar).

## The series-folding rule (the S11-04 follow-up this story owns)

The analytics series serve points per **(bucket, release, ring)** row
and refuse to guess an aggregation across releases — the consumer
folds (docs/analytics.md). The console's single folding
implementation (`frontend/src/api/analytics.ts`):

- **COUNT metrics** (`DOWNLOADS`, `INSTALLATIONS`) **sum** across the
  contributing rows of a bucket.
- **RATE metrics** (everything else in the dictionary) **average** —
  never sum. Summing rates across releases or rings is the classic
  dashboard bug (two releases at 0.98 and 1.0 crash-free are 0.99, not
  1.98); recomputing from numerators and denominators would be exact,
  but the series points carry values only — the honest mean is the
  recomputable-from-points rule, and the unit tests pin both legs.
- Buckets fold and order oldest-first; an empty series renders the
  honest empty state, never a zeroed chart.
