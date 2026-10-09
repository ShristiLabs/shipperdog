# Release Analytics

The release analytics pipeline (S11-04, issue #49, FR-029, PRD 28/29/33/41):
the metric dictionary, the facts-to-rollups-to-reads pipeline, the frozen
SDK events contract, and the two evaluation seams the gates (S10-03) and
policies (S10-06) resolve their metric-backed questions through. The
dashboards of S11-05 render this surface (and their landing read APIs
live in this module — see [Dashboards](dashboards.md), including the
series-folding rule every chart consumer of these reads follows: counts
sum across the per-(bucket, release, ring) points, rates never do); the
computation lives here.

## The pipeline

```
facts                      aggregates                reads
-----------------------    ----------------------    -------------------------
S8-04 download trail   ->  metric_hourly /       ->  per-release current values
S10-01 approval rounds     metric_daily              bounded time series
promotion timeline         (idempotent               (application/tenant/
S11-04 SDK event store     recompute,                ring/release/window)
(frozen S15-01 contract)   bounded windows)
```

- **Facts.** Internal facts already exist: every allowed download issuance
  writes an audit `DOWNLOAD_ISSUED` row beside the artifact counter
  (`artifacts.download_count`, the S8-04 pair); approval decisions land in
  `release_approval_rounds` / `release_approvals`; ring occupancy is the
  promotions timeline. Device-side facts (installs, sessions, uninstalls,
  tester verdicts) arrive through the frozen events API below — M2 has no
  emitters, and every metric that needs them answers the explicit NO_DATA
  until they land (never a zero, never a pass; the fail-closed posture of
  S10-03 AC4 that gates and policies inherit).
- **Aggregates.** `metric_hourly` / `metric_daily` (V38) hold one row per
  (application, release, ring, metric, bucket). Every rollup statement
  RE-DERIVES a bucket's whole value from the facts and upserts it: a re-run
  over the same window writes the same numbers (idempotent by recompute),
  and a late fact inside a window is folded by the next pass over that
  window. Counts aggregate from their events; rates and durations recompute
  at each grain — rates never sum. Sources are append-only or monotone, so
  upsert-only recompute is safe; the one mutable input (the target set, a
  membership row can be removed) only shrinks a denominator the recompute
  re-derives. The hourly job recomputes the trailing lookback (24h default,
  `ANALYTICS_ROLLUP_LOOKBACK`) — the late-event healing bound; older history
  is repaired by the recompute trigger.
- **Reads.** The per-release current values are computed on demand over the
  release's own bounded, index-backed facts; the time series read the
  precomputed rollups (the p95 < 1s cached reads — one indexed window scan,
  never an on-the-fly aggregation over history).

## The metrics dictionary

The registry in code is `AnalyticsCatalog` (the enum javadoc carries each
formula); this table is the dictionary's human copy. Every metric is
computed **per release**; the rollups additionally key the **ring** the
release occupied when the underlying event occurred (the starting ring
until the first COMPLETED promotion, then that hop's to-ring — the
timeline's own semantics), so release, application, tenant (the owning
dimension), ring, and time-window views aggregate from the same rows.

| Metric | Unit | Source | Formula | NO_DATA when |
|---|---|---|---|---|
| `DOWNLOADS` | count | internal (day one, with backfill) | allowed `DOWNLOAD_ISSUED` audit rows of the release's artifacts | never — zero is a real value |
| `INSTALLATIONS` | count | device SDK | `INSTALL` events reported | no event reported |
| `INSTALL_SUCCESS_RATE` | rate 0..1 | device SDK | succeeded `INSTALL` / reported `INSTALL` | no `INSTALL` reported |
| `CRASH_FREE_SESSIONS` | rate 0..1 | device SDK | 1 - crash sessions / total sessions over `SESSION` reports | no sessions reported (the gate seam's `crash-free-threshold`) |
| `ANR_RATE` | rate 0..1 | device SDK | ANR sessions / total sessions over `SESSION` reports | no sessions reported |
| `UNINSTALL_RATE` | rate 0..1 | device SDK | `UNINSTALL` / `INSTALL` reports | no `INSTALL` reported |
| `TESTER_ENGAGEMENT` | rate 0..1 | internal (day one) | distinct targeted testers who downloaded / distinct targeted testers (targets expanded: a tenant target's retained members, narrowed by its named tester groups when present) | the release targets no testers |
| `APPROVAL_DURATION` | minutes | internal (day one) | average of (final decision time - round start) over the release's decided approval rounds | no decision has landed |
| `TIME_IN_RING` | minutes | internal (day one) | current: minutes since the release entered the ring it holds; rollups: the average of ring occupancies the bucket closed (open spans are the live read's) | never — a release always occupies its starting ring |

The policy seam's wider vocabulary (PRD 33): `TESTER_APPROVAL_RATE` =
`APPROVED` / (`APPROVED` + `REJECTED`) verdicts (NO_DATA until any lands);
`CRITICAL_VULNERABILITIES` has no source yet (the validation seam it needs
is a later story) and stays NO_DATA; `OPEN_CRITICAL_BUGS` remains the
feedback module's definitive real-time count (S11-02) — analytics never
double-answers it.

## The read API

- `GET /api/v1/applications/{applicationId}/releases/{releaseId}/metrics`
  (app:analytics:read) — the whole dictionary for one release: current
  ring, lifecycle status, and one entry per metric with `value` (null =
  the explicit NO_DATA), `unit`, and `source`.
- `GET /api/v1/applications/{applicationId}/metrics?metric=&from=&to=`
  (app:analytics:read) — one metric's series over a bounded window, from
  the rollups. Optional: `grain=hour|day` (default hour; hour serves spans
  up to 31 days), `ring=<code>` (only that ring's rows; metrics that carry
  no ring dimension are excluded by a ring filter), `releaseId=`, and the
  platform's `limit`/`cursor` pagination. Points are per (bucket, release,
  ring) rows — consumers fold them per grain; the platform refuses to
  guess an aggregation across releases (counts sum, rates do not).
- `GET /api/v1/tenants/{tenantId}/metrics?...` (tenant:analytics:read) —
  the same series over the tenant's OWNING dimension (releases of the
  applications the tenant owns; the isolation-consistent grain —
  targeted-tenant reach stays application/release-grain with ring
  filters).
- Window semantics: `[from, to)` selects buckets that START within the
  window (a day's bucket starts at its midnight). Windows wider than 366
  days are refused, never clamped; `to` may not be in the future beyond
  clock skew.

## The recompute trigger

`POST /api/v1/applications/{applicationId}/metrics/rollups` with
`{from, to}` (app:analytics:manage — APP_OWNER, RELEASE_MANAGER, and the
ORG_ADMIN overlay) recomputes every dictionary metric over the bounded
window at both grains. This is the backfill surface (the V38 migration
seeds the pre-existing download history once; the trigger repairs and
re-derives any window on demand) and the late-history repair beyond the
hourly job's lookback. Idempotent: the same window recomputes the same
numbers. The receipt is `{rows, from, to}` (rows written or refreshed).

## The frozen SDK events contract (S15-01 implements this)

`POST /api/v1/tenants/{tenantId}/analytics/events`
(tenant:analytics:ingest — tenant administrators until S15-01's device
authentication evolves the authority; the CONTRACT does not change):

```json
{
  "events": [
    {"type": "INSTALL", "result": "SUCCEEDED", "releaseId": "...",
     "deviceId": "optional", "occurredAt": "2026-10-05T12:00:00Z"},
    {"type": "SESSION", "releaseId": "...", "occurredAt": "...",
     "sessions": 100, "crashSessions": 2, "anrSessions": 1},
    {"type": "UNINSTALL", "releaseId": "...", "occurredAt": "..."},
    {"type": "TESTER_VERDICT", "result": "APPROVED", "releaseId": "...",
     "occurredAt": "..."}
  ]
}
```

One batch carries at most 500 events and exactly one release. The release
must target the reporting tenant (the `telemetryFactsOf` resolution — a
release the tenant cannot see is the concealed 404 `RELEASE_NOT_FOUND`);
a named `deviceId` must be the reporting tenant's own ACTIVE device; a
`SESSION` report carries positive `sessions` with non-negative crash/ANR
counts whose sum stays inside the total; `occurredAt` may lag arbitrarily
(healed inside the lookback by the hourly job, older by the trigger) but
not lead by more than five minutes. Whole-batch validation: one invalid
event lands nothing, and the field-keyed `errors` report names every
offender. Rows are append-only — corrections are new events. Delivery
is at-least-once with no dedup key in this contract: a retried batch
lands twice, and while the rate metrics self-correct (a retry doubles
numerator and denominator alike), the count metrics (`INSTALLATIONS`)
do not — the optional client event id S15-01 may add is additive to
this contract. The ingest
is audited as `ANALYTICS_EVENTS_INGESTED` with the batch's shape (counts
by type, the release ref); device identifiers never reach the audit trail.

## The evaluation seams

The gate engine (S10-03) and the policy engine (S10-06) resolve
metric-backed questions at evaluation time through their seams
(`GateMetricSource`, `ReleasePolicyMetricSource`). Registrants:

- feedback (S11-02): `OPEN_CRITICAL_BUGS` — definitive for every release.
- analytics (this story): `CRASH_FREE_RATE` on the gate seam; the crash,
  ANR, uninstall, install-success, engagement, and verdict family on the
  policy seam — the same formulas the dictionary documents, answered empty
  (NO_DATA) while the release holds none of the events they need. Each
  seam speaks its own unit: the policy seam the dictionary's 0..1 rate,
  the gate seam the `NUMERIC_MIN` surface's percentage (a 0.98 rate
  answers 98.0 there — the registrant is the conversion boundary).

The engines iterate registrants and take the first definitive answer; an
empty answer means "this source cannot speak to it", never zero. A gate or
rule whose metric stays unanswered evaluates fail-closed (`NO_DATA`,
distinct from FAILED — no data yet is not a bad result, but it blocks all
the same until data arrives or the governed override applies).

## Reconciliation

The S8-04 download counter and the rollups are two independent records of
the same acts: every issuance increments `artifacts.download_count` AND
writes an audit row, and the rollup counts the audit trail. The analytics
suite pins the equality (`download_count` == summed `DOWNLOADS` rollups at
both grains, held across re-runs and late events). Downloads of an
artifact outside any release attribute to no release-grain row (the
artifact-in-release mapping is functional: one release per application and
version); they simply never enter the release dictionary.

## Configuration

| Variable | Default | Meaning |
|---|---|---|
| `ANALYTICS_ROLLUP_ENABLED` | true | the hourly job's switch |
| `ANALYTICS_ROLLUP_CRON` | `0 0 * * * *` | the hourly schedule (spring cron) |
| `ANALYTICS_ROLLUP_LOOKBACK` | PT24H | the late-event healing window each run recomputes |
| `ANALYTICS_MAX_SERIES_WINDOW` | P366D | the widest series window (refused beyond) |
| `ANALYTICS_MAX_HOURLY_SPAN` | P31D | the widest span the hourly grain serves |

The hourly job is the platform's first background schedule (module-local
`@EnableScheduling`); a crashed run costs only the next hour's delay — the
pass is the same idempotent recompute the trigger runs.
