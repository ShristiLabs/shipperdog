# OTA updates — the device-facing contract

The `OTA Updates` milestone (S16, issues #163-#178, ADR-0010) lets an
application that declares OTA support distribute self-applying bundle
updates through the same governance, monitoring, and telemetry rigor
store releases get. This document is the living reference for the
DEVICE-facing contract: what a shipped app presents, what it receives,
how it reports, and how the rollout decides who sees what. The frozen
design record is [ota-updates-plan.md](ota-updates-plan.md); the
decision rationale is
[ADR-0010](adr/ADR-0010-ota-updates.md); the wire schemas live in
[openapi/baseline.yaml](openapi/baseline.yaml) (`/api/v1/ota/**`).

The pieces, end to end:

1. The application opts in (console toggle; `applications.ota_enabled`)
   and generates its app-scoped Ed25519 signing key (console or CLI;
   the private half stays behind the secret-store port, ADR-0008).
2. An author uploads a generic bundle (console or CLI): a
   platform-agnostic archive with its own versions row, finalized and
   signed — sha256 + size + platform tag + Ed25519 signature.
3. The bundle publishes on an ordinary release over an `ota`-channel
   ring (exactly one bundle per OTA release), inheriting the whole
   governance stack: approvals, gates, policy, status history.
4. A rollout plan stages the delivery (below). The shipped app polls
   `GET /api/v1/ota/check`; when an update stands, it downloads the
   signed URL, verifies checksum and signature, applies through the
   app's own callback, and reports each step as lifecycle telemetry.
5. The console's OTA dashboard reads the funnel (checks are counted
   server-side; lifecycle is device-reported), and alert rules watch
   the degradation signals. Halting a rollout is a human action.

## The check — `GET /api/v1/ota/check`

Authenticated by the app's embedded `sdc1.` app token
(`Authorization: Bearer sdc1.…`; the credential doctrine is
[authentication.md](authentication.md) — an identifier, not a secret,
presenting exactly this one route). Query parameters:

| Parameter | Required | Shape |
|---|---|---|
| `deviceId` | yes | the device's own stable identifier, at most 128 chars — the rollout bucketing's raw input |
| `platform` | yes | the bundle platform tag (an OPEN vocabulary: `android`, `ios`, …), bounded 64 |
| `appVersion` | yes | the running STORE version, at most 64 chars |
| `channel` | yes | the ring code vocabulary (`production`, …), bounded 64 |
| `bundleVersion` | no | the OTA label the device already carries (absent on a first check), at most 128 |

The answer is binary by design:

- **204 No Content** — no update for this device, for ANY of these
  reasons, indistinguishable on the wire: up to date, not eligible, a
  halted or paused rollout, a live release without its plan, a stage
  the device's bucket has not reached, or no live release on the
  channel. The funnel's split lives in the server-side meters, never
  in the response (ADR-0010 D9).
- **200 + manifest** — an update stands; the body is the manifest
  below with a freshly minted download URL and `sdt1.` ingest grant.

A malformed query is the surface's one coded 400 (`INVALID_REQUEST`,
field-addressed — see [error-codes.md](error-codes.md)); an invalid,
tampered, or revoked `sdc1.` is the filter's uniform 401.

### The manifest

| Member | Meaning |
|---|---|
| `bundleId` | the served bundle's id — the one identifier the device cannot derive itself; every lifecycle report attributes back through it |
| `bundleVersion` | the update's semver-triple label (the bundle's own versions row, D2) |
| `url` | the short-lived signed download URL (the same issuance machinery tester downloads ride, D5) |
| `sha256` | 64 lowercase hex over the bundle bytes — verify after download |
| `signature` / `keyId` | Base64 Ed25519 signature over the `sha256` HEX STRING and the signing key generation's id (D8) — the public key is copyable from the console for embedding |
| `sizeBytes` | the exact byte size |
| `forced` | the effective forced flag (per-target release override ?? the bundle's default, D7) |
| `minAppVersion` | the effective minimum store version the update applies to (same override resolution) |
| `ingestToken` | the minted `sdt1.` grant for this check's release + device — the credential the lifecycle reports below present |
| `pollIntervalSeconds` | the server-owned poll cadence (default 3600; `OTA_CHECK_POLL_INTERVAL`) |

## Credentials — `sdc1.` and `sdt1.`

- **`sdc1.` app token** — the fleet's embedded identity, issued per
  application from console/CLI, shown exactly once, stored as a
  SHA-256 hash, never expiring, revocable by row or by key rotation.
  It exists to identify and rate-limit the caller and resolve
  org/app unambiguously (bundleId is not tenant-unique). Full
  doctrine: [authentication.md](authentication.md).
- **`sdt1.` ingest grant** — minted BY the check response (D4): the
  existing telemetry codec carrying releaseId + deviceId, so lifecycle
  telemetry flows through the pre-existing ingest endpoint
  (`POST /api/v1/tenants/{tenantId}/analytics/events`) with zero new
  auth surface.

## Lifecycle telemetry and result vocabularies

The device reports its update journey through the `sdt1.`-bound
ingest as analytics events of the OTA family (the SDK does this
itself; the vocabularies are the contract):

| Event | Carries (result vocabulary — open row values, the V42 doctrine) |
|---|---|
| `OTA_SEEN` | the manifest was returned and the update noticed |
| `OTA_DOWNLOAD` | `SUCCEEDED` or `FAILED` (the failure reason rides `ota_payload`) |
| `OTA_APPLIED` | the app's apply callback completed |
| `OTA_FAILED` | `checksum_mismatch`, `signature_invalid`, `apply_error`, or any SDK-reported reason |
| `OTA_ROLLED_BACK` | the app's own rollback path ran |

Every OTA event carries `ota_bundle_id` (NOT NULL — the attribution
key) and an optional `ota_payload` jsonb detail object. Checks
themselves are counted server-side only (meters + a server-inserted
`OTA_CHECK` event when an update is available and the release is
known) — a no-update check attributes to nothing by design (D9).

## Signing contract (D8)

Per-application Ed25519; JCA / CryptoKit only, no hand-rolled paths
(ADR-0009 doctrine). The private key lives behind the secret-store
port; the public key and its `keyId` ride the check response and are
copyable in the console for SDK embedding. What is signed is the
64-character lowercase-hex `sha256` STRING (not the raw digest
bytes) — both SDKs verify exactly this (Kotlin `:ota`, Swift
`ShipperDogOta`; see [telemetry-sdk.md](telemetry-sdk.md)). Rotation
is generational: a new `keyId` supersedes, old signatures stay
verifiable against the retained public half until the generation is
retired.

## Rollout semantics

One rollout plan per OTA release (`ota_rollout_plans`, V56): a
ladder of cumulative stages, each a basis-point threshold, advanced
manually or automatically (mode). Device admission is deterministic
on-the-fly bucketing — `hash(deviceId, planSalt) mod 10000 <
stage.basisPoints` — stable per device per plan, no cohort table
(devices are not enumerable, unlike the V48 tenant cohorts). A device
below the current stage's threshold gets the same 204 as an up-to-date
device.

Plan standing: `RUNNING`, `PAUSED` (devices hold; already-served
devices keep their manifest), `HALTED` (the human, audit-trailed
stop), `COMPLETE`. Controls (console or CLI): pause, resume, advance,
halt — every crossing an append-only `ota_rollout_events` row and an
audit line. Alert rules ([dashboards](dashboards.md), S16-11) watch
failure rates and crash-free regressions; halting stays manual
(ADR-0010 D10).

## Where the surfaces live

- Console: bundles page, rollout controls, OTA dashboard, alerts,
  the OTA-enable toggle and public-key copy ([frontend.md](frontend.md))
- CLI: `shipperdog ota bundle|rollout|token|key` ([cli.md](cli.md))
- Tester portal: OTA-channel releases surface on the application and
  version pages with the bundle manifest ([portal.md](portal.md))
- SDK updaters: `sdk/android` `:ota` + `:ota-android`, `sdk/ios`
  `ShipperDogOta` ([telemetry-sdk.md](telemetry-sdk.md))
- Schema: [database-migrations.md](database-migrations.md) V55-V61;
  errors: [error-codes.md](error-codes.md) (OTA bundle section)
