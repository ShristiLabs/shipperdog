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
  is repaired by the recompute trigger. The OTA count family (S16-09) rides
  the same engine with the bundle dimension V57 added: `ota_bundle_id`
  (nullable on both tables, NULL on every legacy row — the legacy statements
  are untouched, so historical outputs are byte-identical) carried by the
  six OTA metric names. OTA rows attribute to the BUNDLE's own release,
  never the reporting release's: the ingest admits a report naming any
  bundle of the release's application (attribution is history, S16-08), so
  only the bundle's own linkage makes the (application, metric, bucket,
  release, ring) grain one row per bundle by construction — the
  release-to-bundle uniqueness the funnel reads key on.
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
The OTA count family (S16-09) additionally keys the **bundle**
(`ota_bundle_id`, V57) — every OTA event row carries the bundle its
manifest served, so the funnel views bundle from the same rollup rows.

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
| `OTA_CHECKS` | count | internal (S16-09) | update-available `OTA_CHECK` rows attributed to the bundle (D9's server-side leg — the check surface is the one writer) | no event reported |
| `OTA_SEEN` | count | device SDK (S16-09) | `OTA_SEEN` reports attributed to the bundle | no event reported |
| `OTA_DOWNLOADS` | count | device SDK (S16-09) | `OTA_DOWNLOAD` reports attributed to the bundle (either result — the failure legs ride `OTA_FAILED`) | no event reported |
| `OTA_APPLIED` | count | device SDK (S16-09) | `OTA_APPLIED` reports attributed to the bundle | no event reported |
| `OTA_FAILURES` | count | device SDK (S16-09) | `OTA_FAILED` reports attributed to the bundle (the per-reason breakdown is the funnel read's, not this count's) | no event reported |
| `OTA_ROLLBACKS` | count | device SDK (S16-09) | `OTA_ROLLED_BACK` reports attributed to the bundle | no event reported |

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

## The OTA bundle reads (S16-09, issue #171)

Two service-layer reads the S16-10 dashboard endpoints (#172) serve; no
HTTP surface ships with this story. Both are computed on demand over the
bundle's own bounded, index-backed facts (`idx_analytics_events_ota_bundle`),
and both resolve the bundle through the tenant's visible-application
catalog (tenant-owned or ACTIVE-authorized — the dashboard union):
unknown, foreign, and unvisible answer the one concealed `BUNDLE_NOT_FOUND`.

- **`AnalyticsQueryService.otaFunnel(tenantId, bundleId, ring)`** — the
  device funnel of one bundle: the six stage counts (check -> seen ->
  download -> applied, beside the failure and rollback legs), the
  failure-reason breakdown (`OTA_FAILED`'s open row values —
  `checksum_mismatch` and kin — ordered count-descending then by word),
  and the adoption curve over time: for each day the bundle drew a seen
  or applied report, the cumulative distinct identified devices that
  applied over the cumulative distinct that observed. Reports without
  the payload's `device_id` count toward the stage counts but never
  toward adoption — adoption needs identity; a day with observers and
  no applies answers the real 0.0, and honest arithmetic is never
  clamped (a device whose apply landed without any seen report can
  push a day past 1.0 — partial reporting reported as it stands). The
  optional ring filter narrows EVERY leg — stages, breakdown, and
  curve — to the reports that occurred while the bundle's release
  occupied the named ring (the rollups' own attribution); an unknown
  ring code is refused, never silently empty.
- **`DashboardQueryService.otaBundleHealth(tenantId, bundleId)`** — the
  crash-rate comparison current vs new: the addressed (new) bundle
  beside the application's CURRENT bundle — its most recent OTHER
  RELEASED bundle by the release ladder (a first rollout answers the
  new side alone). Each side's crash-free rate is the dictionary's
  CRASH_FREE_SESSIONS formula over the bundle's release's SESSION
  reports (the release-keyed attribution V57's doctrine names:
  `ota_bundle_id` is NULL on SESSION rows by schema, and an OTA release
  carries exactly one bundle) — null while the release holds no
  sessions (the explicit NO_DATA, never a zero), and the delta (new
  minus current — positive is better) serves only when both sides are
  evidenced.

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

`POST /api/v1/tenants/{tenantId}/analytics/events` — two authorities
since S15-01 (issue #72): the tenant administrator's
`tenant:analytics:ingest` (the CI and operations path) and the
tester telemetry SDK's token (`sdt1.…`, minted by the S8-04 download
issuance for store testers — see [telemetry-sdk](telemetry-sdk.md) —
and by the S16-07 OTA check manifest for fleet devices). The token carries
exactly one authority (`analytics:telemetry:ingest`) and its
bindings are enforced per batch: the path tenant and every event's
release must be the token's own, a device-bound token accepts only
its device, and any named device must be the downloading tester's
own ACTIVE device — every misbinding the concealed 404 or the
field-addressed refusal, never a landed row attributed to another
reporter. The BODY contract changed only additively (the `eventId`
member S15-01 froze in advance):

```json
{
  "events": [
    {"type": "INSTALL", "result": "SUCCEEDED", "releaseId": "...",
     "deviceId": "optional", "eventId": "optional client UUID",
     "occurredAt": "2026-10-05T12:00:00Z"},
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
is at-least-once: a batch WITHOUT client event ids may land twice on a
retry (the rate metrics self-correct — a retry doubles numerator and
denominator alike — the count metrics do not), while a batch WITH them
lands at most once per (device, release, event id) — the V54 dedup
index, and the receipt's additive `deduplicated` count says how many
at-least-once duplicates were recognized and dropped. The ingest
is audited as `ANALYTICS_EVENTS_INGESTED` with the batch's shape (counts
by type, the release ref); device identifiers never reach the audit trail.

### The S16-08 OTA family extension (issue #170 implements this)

Five device lifecycle types join the accepted vocabulary on the SAME
endpoint and the SAME whole-batch discipline: `OTA_SEEN`,
`OTA_DOWNLOAD`, `OTA_APPLIED`, `OTA_FAILED`, `OTA_ROLLED_BACK`. The
fleet device authenticates with the check-minted `sdt1.` grant (the
manifest's `ingestToken`, D4 — deliberately device-unbound: a fleet
device is not the device registry's tester hardware), so the batch
carries no registry `deviceId`; the fleet device's identity rides the
payload's `device_id` member instead, and the landed rows carry no
registry device or tester. `OTA_CHECK` is NOT one of them — it stays
server-authoritative (D9: the S16-07 check recorder is its one writer;
a device-posted check row would forge the platform's own counting),
and the ingest refuses it byte-identically to an unknown type: the
wire teaches nothing about the server's vocabulary.

The additive OTA members (the `eventId` precedent's own doctrine —
optional on the wire, validated whole-batch):

- `otaBundleId` — REQUIRED on the five lifecycle types, forbidden on
  the legacy four (the row it would land can never violate V57's
  `ck_analytics_events_ota_shape` disjunction). It names the bundle
  the manifest served — the manifest's additive `bundleId` (S16-08),
  the one identifier a device cannot derive for itself — and the
  ingest proves it: the bundle must resolve and belong to the
  release's own application, an unknown and a foreign bundle one
  concealed field-addressed answer (no oracle). Attribution is
  history, not re-resolution: a bundle the release has since rolled
  past still attributes (whatever its status), and so does a bundle
  of the application's OTHER releases — funnel readers (S16-09/S16-10)
  must therefore key on `ota_bundle_id`, never on an assumed
  release-to-bundle uniqueness.
- `otaPayload` — OPTIONAL: an object of at most 32 members whose JSON
  serialization stays within 4096 characters (nested content at full
  serialized weight — the per-event byte bound on the fleet write
  path), with the `device_id` member, when present, a non-blank string
  of at most 128 characters (the check surface's own raw identifier
  vocabulary, S16-07).

The per-type result vocabulary, validated in code beside V57's widened
CHECKs: `OTA_DOWNLOAD` and `OTA_APPLIED` take the closed
`SUCCEEDED`|`FAILED` pair (case-insensitive, stored uppercase — the
INSTALL precedent); `OTA_FAILED` takes the documented open row-value
set `checksum_mismatch`|`apply_error`|`signature_invalid` (stored as
given, lowercase — the V42 provider doctrine); `OTA_SEEN` and
`OTA_ROLLED_BACK` are resultless — a carried word is the
field-addressed refusal, never a silent drop. OTA rows carry no
session columns (the DB's own closed disjunction) and — the one
deliberate dimension swap — their `versionId` is the BUNDLE's, not
the release's: the server-side `OTA_CHECK` row's own funnel grain,
so both writers of one funnel land identical dimensions and the
S16-09/S16-10 reads compose them. The V54 `eventId` dedup is
byte-identical for the family.

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
- analytics (S16-05, the OTA twin): `BUNDLE_CRASH_FREE_RATE` on the gate
  seam for `bundle-crash-free-threshold` — "crash-free sessions on the
  CURRENT bundle" (ADR-0010 workstream 3): 1 - crash sessions / total
  sessions over the SESSION reports V57's `ota_bundle_id` attributes to
  the release's linked bundle. While the bundle holds none — before
  the fleet's attributed reports arrive, or in their gaps — the
  documented fallback is the release-keyed SESSION standing the SDK
  already reports (an OTA
  release carries exactly one bundle, so its release-attributed sessions
  are by construction sessions on that bundle). A release with no bundle
  link is not this metric's to answer: the empty step-aside, so a store
  release that attaches the gate type evaluates the honest NO_DATA,
  never another release's numbers. Same percentage boundary as its
  store twin; same fail-closed NO_DATA until data exists.

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
