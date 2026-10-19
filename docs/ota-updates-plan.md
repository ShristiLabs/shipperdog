# OTA Updates — Implementation Plan

Status: **shipped** — the `OTA Updates` milestone landed (S16-01
through S16-16, issues #163-#178: ADR-0010, migrations V55-V61, the
`otabundle` module, OTA releases + rollouts, the device check API,
telemetry ingest, rollups/dashboards, alerts, both SDK updaters, CLI
commands, console surfaces, and the portal/companion awareness below).
This document stays as the frozen design record; the living
device-facing contract documentation is [docs/ota.md](ota.md), and the
migration ledger is [docs/database-migrations.md](database-migrations.md).
Apps that declare OTA support (React
Native / Flutter / Electron-style apps that self-apply bundle updates)
will be able to receive over-the-air bundle updates from ShipperDog,
with the same governance, monitoring, and telemetry rigor the platform
applies to store releases.

Scope decisions (confirmed by the product owner):

- **Generic bundles** — platform-agnostic artifact + a ShipperDog OTA
  manifest (checksum, size, platform tag, metadata). No Expo Updates /
  Electron-updater format emulation in v1.
- **Both SDKs gain an OTA updater module** (Kotlin + Swift): check →
  download → verify → app-provided apply callback → report.
- **Full bundles only** in v1 — the manifest carries a content hash so
  clients skip already-applied versions. Delta (binary-diff) updates are
  a follow-up.
- **Alert + manual halt** governance — degradation raises alerts;
  halting a rollout is a human, audit-trailed action. No auto-halt.

- [Decisions](#decisions)
- [Architecture overview](#architecture-overview)
- [Change analysis by component](#change-analysis-by-component)
- [Issue tree](#issue-tree)
- [Workstream details](#workstream-details)
- [Key reuse map](#key-reuse-map)
- [Risks / compatibility gates](#risks--compatibility-gates)
- [Verification](#verification)

## Decisions

| # | Decision | Rationale |
|---|----------|-----------|
| D1 | OTA releases **reuse** `releases` + `release_targets` + `rings` (`rings.distribution_channel = 'ota'`, the free-form column added in V32); new `ota_bundles` / `ota_rollout_*` tables hang off the release | Approval workflows, gates, the policy engine, status history, and analytics rollups all key on `releases`; a parallel release table would fork governance |
| D2 | Each `ota_bundle` gets its **own `versions` row** (label = `bundle_version`) | Keeps the `version_id` FKs on `releases` / `release_targets` / `analytics_events` intact; reuses the version lifecycle (REVOKED) and `version_status_history` |
| D3 | Device auth is a new **`sdc1.` app token** (HMAC codec mirroring `sdt1.` / `TelemetryTokenCodec`), minted per application from console/CLI and embedded in the shipped app. It is an identifier, not a secret: it enables rate limiting and unambiguous org/tenant/app resolution (bundleId is NOT tenant-unique — see the companion-spike gap note). mTLS rejected: mobile fleets cannot do it | The check happens before any download, so no `sdt1.` grant exists yet |
| D4 | The check response **mints an `sdt1.` ingest grant** (existing codec, carries releaseId + deviceId), so lifecycle telemetry flows through the *existing* ingest endpoint — zero new auth surface | Matches the existing token doctrine |
| D5 | Downloads reuse existing plumbing: a bundle is an ordinary artifact (`artifact_kind = 'OTA_BUNDLE'`); the check response `url` is a `DownloadTokenCodec` mint served by `DownloadProxyController` (`GET /api/v1/downloads/{token}`, permitAll) | ADR-0003 `ArtifactStore` port; no new storage path |
| D6 | Device rollout is **deterministic on-the-fly bucketing**: `hash(device_id, plan_salt) mod 10000 < stage.basis_points` — stable per device, no cohort table (devices are not enumerable, unlike the V48 tenant cohorts). The table triple plans/stages/events mirrors V48 including the optimistic-lock `version` column and a `HALTED` status | Reuses the proven V48 shape |
| D7 | `forced` / `min_app_version` are per-bundle defaults, **overridable per release target (per-ring)** at release time | Flexible promotion semantics without re-upload |
| D8 | Bundle signing is **Ed25519 per app**; the private key lives in the secret-store port (`distribution/internal/secret/`, generalized to an app-scoped `OtaSigningKeyService`); the bundle sha256 is signed at finalize; the public key + keyId ride the check response and are copyable in the console for SDK embedding. JCA / CryptoKit only | ADR-0009 doctrine: no hand-rolled signature paths |
| D9 | Funnel split: **checks are counted server-side** (Micrometer meters + a server-inserted `OTA_CHECK` event when the outcome is update-available, where the release is known); **device-reported lifecycle** events (`OTA_SEEN`, `OTA_DOWNLOAD`, `OTA_APPLIED`, `OTA_FAILED`, `OTA_ROLLED_BACK`) flow through `sdt1.`-bound ingest | `analytics_events.release_id` is NOT NULL (V38) — a no-update check has no release to attribute. `sdt1.` grants only exist when a manifest was returned, which preserves the invariant |
| D10 | Alerts are persisted (`ota_alert_rules` + firings), surfaced in the console **and** fanned out via the notification bus (ADR-0007). Halt stays manual | Product-owner choice: alert + manual halt |
| D11 | SDK samples are strictly mobile (Android/iOS); the Kotlin/Swift modules are pure-language, so desktop adoption is a follow-up | v1 focus |

## Architecture overview

**Bundle lifecycle (authoring):** console/CLI creates an OTA bundle for
an OTA-enabled application → upload session (existing artifact path,
`OTA_BUNDLE` kind) → finalize computes the manifest (sha256, size,
platform) → `OtaSigningKeyService` signs → `SIGNED`. Publishing creates
a normal release on an `ota`-channel ring with exactly one bundle,
subject to the existing approval workflows and gates; rollout proceeds
through `ota_rollout_plans` stages (basis-point buckets) with
halt / pause / resume as audited operator actions.

**Device flow (delivery):**

```
app (sdc1. token)
  └─ GET /api/v1/ota/check?deviceId&platform&appVersion&bundleVersion&channel
       ├─ 204 No Content        (no update / not eligible / halted / up-to-date)
       └─ 200 OTA manifest      (bundleVersion, url[download-token mint],
                                 sha256, signature, keyId, sizeBytes,
                                 forced, minAppVersion,
                                 ingestToken[sdt1 mint], pollIntervalSeconds)
            ├─ GET /api/v1/downloads/{token}      (existing proxy, bytes)
            ├─ verify sha256 + Ed25519 signature (SDK, embedded public key)
            ├─ BundleApplier callback            (app-supplied)
            └─ POST /api/v1/tenants/{tenantId}/analytics/events
                 (existing ingest, sdt1-bound: OTA_SEEN/OTA_DOWNLOAD/
                  OTA_APPLIED/OTA_FAILED/OTA_ROLLED_BACK)
```

**Monitoring:** server-side check meters by outcome; device funnel
(check → seen → download → applied) with failure-reason breakdown;
adoption % over time; crash-rate comparison current vs new bundle (from
SESSION/crash events); hourly/daily rollups gain a nullable
`ota_bundle_id` dimension; alert rules evaluate rollups and fire to the
console + notification bus. Halt is one audited operator action away.

## Change analysis by component

| Component | Changes |
|---|---|
| **DB (Flyway)** | V55 `ota_bundles`, `ota_app_tokens`, `applications.ota_enabled`; V56 `ota_rollout_plans/_stages/_events`, `ota_alert_rules/_firings`; V57 `analytics_event_type` enum extension + **rewrite of the two V38 CHECK constraints**, `analytics_events.ota_bundle_id`/`ota_payload`, `metric_hourly/daily.ota_bundle_id` |
| **Backend — new `otabundle/` module** | `OtaBundleController` (bundle CRUD, finalize+sign), `OtaAppTokenController` (issue/revoke `sdc1.`), `OtaCheckController` (device check + manifest + `sdt1.` mint), `OtaRolloutService`, `OtaSigningKeyService`; entities/repos/services under `internal/`, on the `TenantScopedRepositoryImpl` base |
| **Backend — extensions** | `ReleaseService`/`PromotionService` (OTA-channel invariants), `RingEligibilityService` (bundleId/platform branch — closes the companion-spike gap), `AnalyticsIngestService` (OTA event types + payload), `MetricRollupService` (OTA dimensions), `AnalyticsQueryService` (funnel, bundle health), `DashboardController` (OTA dashboard endpoints), `MetricNames` + meters, alert evaluation scheduler, `SecurityConfig` (check route permitAll + `sdc1.` filter) |
| **Kotlin SDK** | new `sdk/android/ota` (+`ota-android`): `OtaConfig`, `OtaUpdater`, `OtaDownloader` (resumable), `OtaVerifier` (sha256 + Ed25519), `BundleApplier`, lifecycle events via existing `EventQueue` |
| **Swift SDK** | new `sdk/ios/Sources/ShipperDogOta` mirroring `ShipperDogTelemetry` (CryptoKit Ed25519) |
| **CLI** | `shipperdog ota create/upload/promote/halt/resume/status/funnel` + `ota token issue`; endpoint pins in `cli/internal/api/endpoints.go` |
| **Console** | `OtaBundlesPage` (upload wizard), rollout controls on `ReleaseDetailPage` (halt/resume/advance), `OtaDashboardPage` (funnel, failure reasons, adoption, crash-rate comparison, alerts), OTA-enable toggle + public-key copy on application detail, `otaRules.ts` |
| **Portal** | surface OTA release + bundle manifest on application/version pages |
| **Companion** | no updater in v1; `UpdateState` OTA-channel awareness only if cheap |
| **Docs/CI** | ADR-0010, OpenAPI baseline additions (additive, per-PR), `database-migrations.md`, READMEs, `sdk-ci` builds the OTA modules |

## Issue tree

Ordered; each issue lists its dependencies. OpenAPI baseline additions
land **inside** each API-touching issue (#3, #4, #6, #7, #8, #10, #11,
#14) so the oasdiff gate stays green per PR.

| # | Issue | Depends on |
|---|-------|-----------|
| 1 | ADR-0010: OTA updates architecture (reuse-releases, `sdc1.` doctrine, signing custody, funnel split) | — |
| 2 | Migrations V55–V57 (bundles, app tokens, rollout tables, telemetry enum + CHECK rewrite) | 1 |
| 3 | `otabundle` module: bundle CRUD, finalize+sign, `OtaSigningKeyService` (secret-store) | 2 |
| 4 | `sdc1.` app tokens: codec, auth filter, issue/revoke endpoints | 2 |
| 5 | OTA release integration: ring eligibility (bundleId/platform), exactly-one-bundle rule, crash-free gate | 3 |
| 6 | `OtaRolloutService`: plans/stages, deterministic device bucketing, halt/pause/resume + events/audit | 5 |
| 7 | Device check API `GET /api/v1/ota/check` + manifest + check-time `sdt1.` mint + baseline | 3, 4, 6 |
| 8 | Telemetry ingest: OTA event types, `ota_payload`, server-side `OTA_CHECK` | 2 |
| 9 | Rollups + analytics queries: OTA funnel, bundle health (crash-rate comparison) | 8 |
| 10 | Dashboard API endpoints + isolation-registry registration | 9 |
| 11 | Alerts: `ota_alert_rules`, evaluation scheduler, meters, console + bus delivery (no auto-halt) | 9 |
| 12 | Kotlin SDK OTA updater module (check/download/verify/apply/report) + sample | 7 |
| 13 | Swift SDK OTA updater module + sample | 7 |
| 14 | CLI `shipperdog ota *` commands + endpoint pins | 7, 9 |
| 15 | Console UI: bundles page, rollout controls, OTA dashboard, alerts, OTA-enable + public key | 3, 6, 10, 11 |
| 16 | Portal surfacing + companion `UpdateState` OTA awareness (thin) + docs/README/migration notes | 7 |

## Workstream details

### 1. Migrations V55–V57 (`backend/src/main/resources/db/migration/`)

- **V55__ota_bundles.sql** — `applications.ota_enabled boolean NOT NULL
  DEFAULT false` (+ optional per-platform capability jsonb);
  `ota_bundles` (id, org/tenant/app ids, `version_id` FK, `release_id
  uuid NULL` until released, artifact FK, `bundle_version`,
  `platform_tag`, sha256, `size_bytes`, signature, `key_id`, `forced`,
  `min_app_version`, status `UPLOADING/VALIDATING/SIGNED/RELEASED/
  REVOKED`); `ota_app_tokens` (hashed `sdc1.` registry, app-scoped,
  revocable).
- **V56__ota_rollout.sql** — `ota_rollout_plans` (release_id UNIQUE,
  mode, status `RUNNING/HALTED/PAUSED/COMPLETE`, current_stage,
  `plan_salt`, `version bigint`), `_stages` (position, basis_points,
  note), `_events` (append-only: commenced/halted/resumed/advanced);
  `ota_alert_rules` + `ota_alert_firings`.
- **V57__ota_telemetry.sql** — `ALTER TYPE analytics_event_type ADD
  VALUE` for the six OTA types in their **own migration file** (the
  ADD VALUE transaction caveat, as practiced in V48); **rewrite
  `ck_analytics_events_result_shape` and
  `ck_analytics_events_session_shape`** — they are closed disjunctions
  (V38 lines 71–82) and the new enum values would fail every OTA
  insert; nullable `ota_bundle_id uuid` + `ota_payload jsonb` on
  `analytics_events` (+ index `(ota_bundle_id, event_type,
  occurred_at)`); nullable `ota_bundle_id` on `metric_hourly` /
  `metric_daily`.

  Addendum (post-implementation): the shipped milestone carried four
  later migrations beyond this planned V55-V57 set — V58 (ota signing
  keys), V59 (OTA release integration: `release_targets.forced` /
  `min_app_version` + `uq_ota_bundles_release`), V60 (the rollout
  PAUSED event vocabulary), and V61 (ota alert rules' organization
  grain) — see [docs/database-migrations.md](database-migrations.md)
  for the full ledger. This table records the plan as written, not a
  rewrite of history.

### 2. `otabundle/` module — bundles + app tokens

`backend/src/main/java/com/shipperdog/api/otabundle/`: `OtaBundleController`
(`/api/v1/ota/bundles`: create → upload-session attach → finalize →
sign), `OtaAppTokenController` (`/api/v1/ota/app-tokens` issue/revoke —
returns the `sdc1.` exactly once), `internal/{entity,repository,service}`.
Finalize consumes the artifact-validated event pattern from `artifact/`,
computes the manifest, signs via `OtaSigningKeyService`, publishes
`OtaBundleSignedEvent` on the bus (outbox). Reuses `ApiProblems`,
`@Idempotent`, `CursorCodec` pagination, `TenantScopedRepositoryImpl`,
the secret-store port.

### 3. OTA releases + rollout orchestration

Minimal `ReleaseService`/`PromotionService` extension: an OTA-channel
release requires exactly one OTA bundle; `RingEligibilityService` gains
the bundleId/platform branch. `OtaRolloutService` provides
advance-stage / halt / pause / resume with optimistic-lock 409s (V48
pattern); every action writes `ota_rollout_events`, an audit entry, and
a bus event. Reuses approval workflows (the APPROVAL_PENDING path) and
adds one `gate/` definition — "crash-free sessions on current bundle ≥
X%" evaluated via `AnalyticsQueryService`.

### 4. Device check API

`OtaCheckController`: `GET /api/v1/ota/check` — `Authorization: Bearer
sdc1.…` + query `deviceId, platform, appVersion, bundleVersion,
channel`. New `OtaAppTokenCodec` + `OtaAppTokenAuthenticationFilter`
(mirroring `TelemetryTokenAuthenticationFilter`). Resolution: app →
OTA ring for channel → ACTIVE release → eligibility → rollout bucket
(D6) → bundleVersion/content-hash comparison. Response: the manifest or
204. Register in `SecurityConfig` permitAll and on the isolation
registry **excluded** list (unauthenticated device surface — mirror how
`/api/v1/downloads/{token}` is listed).

### 5. Telemetry ingest

Extend `AnalyticsIngestService` + the analytics catalog for the OTA
event types (`result` vocabulary: e.g. OTA_DOWNLOAD/SUCCEEDED|FAILED,
OTA_FAILED/checksum_mismatch|apply_error|signature_invalid); populate
`ota_bundle_id` / `ota_payload`; dedup rides the V54 `event_id` unique
index. Server-side `OTA_CHECK` insert per D9.
`TelemetryTokenAuthenticationFilter` unchanged.

### 6. Rollups, queries, dashboards

Extend `MetricRollupService` / scheduler for OTA funnel dimensions.
`AnalyticsQueryService.otaFunnel(bundleId, ring)` — check→seen→
download→applied, failure-reason breakdown, adoption % over time.
`DashboardQueryService.otaBundleHealth` — crash-rate comparison current
vs new bundle. New `DashboardController` endpoints under
`/api/v1/tenants/{tenantId}/dashboard/ota/*`. **Register every new
tenant-scoped GET in the `IsolationEndpointRegistry` test catalog** or
`IsolationEndpointCatalogTest` fails.

### 7. Alerts + meters

Micrometer meters in `config/MetricNames.java` (`ota.check.count` by
outcome, `ota.apply.failure` by bundle/reason). `ota_alert_rules`
(metric, comparator, threshold, window, severity) evaluated by a
scheduler beside the rollups; a firing writes an alerts row, publishes
a notification-bus event, and badges the console. **No auto-halt** —
halt is a manual action through the workstream-3 endpoints (audited).

### 8–9. SDK updaters (Kotlin, Swift)

`sdk/android/ota` (pure Kotlin) + `ota-android`: `OtaConfig` (app
token, poll interval), `OtaUpdater` (check loop → `OtaManifest`),
`OtaDownloader` (resumable stream-to-file), `OtaVerifier` (sha256 +
Ed25519 via `java.security`), `BundleApplier` interface, lifecycle
events through the existing `EventQueue`/`TelemetryConfig` ingest URL;
sample + tests mirroring the `telemetry/src` layout.
`sdk/ios/Sources/ShipperDogOta/` mirrors `ShipperDogTelemetry`
(`OtaConfig`, `OtaUpdater`, `OtaDownloader`, `OtaVerifier` with
CryptoKit Ed25519, `BundleApplier` protocol) with
`Tests/ShipperDogOtaTests`.

### 10. CLI

`cli/internal/api/endpoints.go` + `ota.go`: `shipperdog ota
create|upload|promote|halt|resume|status|funnel` and `ota token
issue`; extend `models.go`. cli-ci re-verifies the endpoint pins
automatically (it triggers on `baseline.yaml` changes).

### 11. Console

`frontend/src/pages/`: `OtaBundlesPage` (list/upload wizard), rollout
controls in `ReleaseDetailPage` (halt/resume/advance with
confirmation), `OtaDashboardPage` (funnel, success rate, failure
reasons, adoption, crash-rate comparison, alerts) + `otaRules.ts`;
application detail gains the OTA-enable toggle and public-key copy.
Per-page tests mirroring the existing `*.test.tsx`.

### 12. Portal + companion (thin)

Portal: surface the OTA release + bundle manifest on the
application/version pages. Companion: leave the updater out of v1; add
`UpdateState` OTA-channel awareness only if cheap.

### 13. Docs, ADR, CI, baseline

ADR-0010 records D1 (reuse-releases), D3 (`sdc1.` doctrine), D8
(signing-key custody), D9 (funnel split). Update
`docs/openapi/baseline.yaml` (all additive), `docs/database-migrations.md`,
READMEs. `sdk-ci` builds the OTA modules. Backend IT covers the full
chain: check → download → ingest → rollup → alert.

## Key reuse map

Existing code to reuse, not duplicate:

- `analytics/internal/telemetry/TelemetryTokenCodec.java` — the
  `sdc1.` codec pattern + check-time `sdt1.` minting
- `artifact/internal/download/ArtifactDownloadService.java`,
  `DownloadTokenCodec.java`, `DownloadProxyController` — download URL
  minting and serving
- `artifact/internal/storage/` `ArtifactStore` (ADR-0003) — bundle
  storage; the upload-session/finalize path
- `V48__staged_tenant_rollout.sql` — the structural template for
  `ota_rollout_*` (optimistic lock + events)
- `V38__release_analytics.sql` — the CHECK constraints V57 must
  rewrite (lines 71–82); `release_id NOT NULL` (line 58) drives D9
- `release/internal/service/` — `ReleaseStateMachine`,
  `RingEligibilityService`, `PromotionService`
- `workflow/`, `gate/`, `release/internal/policy/ReleasePolicyService`
  — governance reuse
- `notification/internal/bus` (ADR-0007 outbox) — OTA domain events;
  `distribution/internal/secret/` (ADR-0008) — signing keys
- `config/` — `ApiProblems`, `IdempotencyInterceptor`, `CursorCodec`,
  `MetricNames`, `CorrelationIdFilter`

## Risks / compatibility gates

1. **V38 CHECK constraints** — closed disjunctions: every OTA insert
   fails unless V57 rewrites both CHECKs before inserts; keep the
   `ADD VALUE` and the CHECK rewrite correctly sequenced/separate.
2. **oasdiff breaking gate** (ci.yml) — additive-only; the spec for
   `/api/v1/ota/check` must declare the permitAll-style surface (like
   downloads) in the baseline.
3. **IsolationEndpointRegistry** — register every new tenant-scoped
   GET; the `sdc1.`-authenticated `/api/v1/ota/check` goes on the
   excluded list, mirroring `/api/v1/downloads/{token}`.
4. **`metric_hourly`/`metric_daily` grain** — the new nullable
   dimension must be tolerated on historical rows by rollup code.
5. **cli-ci pins** — endpoint additions are additive, but the pin
   tests must be updated in lockstep.

## Verification

- **Backend** — unit + IT suites (`./mvnw verify` with
  `JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home`);
  a full-chain IT (mint `sdc1.` → check, update and 204 paths →
  download via token → ingest lifecycle events → rollup job → alert
  rule fires); the isolation catalog stays green.
- **Contract** — local oasdiff run against
  `backend/target/openapi.yaml` before every API-touching PR.
- **Console/portal** — `npm run build && npm test` + manual walkthrough
  of bundle upload → release → halt → dashboard funnel.
- **CLI** — `go test ./...` + pin-test update; manual `shipperdog ota`
  happy path against local compose (PostgreSQL 17 + Redis +
  fake-gcs-server).
- **SDKs** — sdk-ci jobs green; verifier unit tests including the
  tampered-signature and tampered-checksum cases.
