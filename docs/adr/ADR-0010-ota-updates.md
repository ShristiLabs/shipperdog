# ADR-0010: OTA updates ride the existing release machinery — an `sdc1.` app token identifies devices, app-scoped Ed25519 keys stay in the secret store, and the telemetry funnel splits between server and device

Date: 2026-10-17
Status: Accepted (issue #163, S16-01)

## Context

The OTA Updates milestone adds over-the-air bundle updates: apps
that self-apply updates (React Native / Flutter / Electron-style)
receive a generic platform-agnostic bundle plus a ShipperDog
manifest — checksum, size, platform tag — with the same governance,
monitoring, and telemetry rigor the platform applies to store
releases. The product owner approved the full design in
`docs/ota-updates-plan.md` (PR #161) as eleven numbered decisions,
D1–D11; this record is the architecture anchor for that plan. Four
of the eleven shape the architecture and are decided here:
where OTA releases live (D1), how a device authenticates a check
(D3), who holds bundle signing keys (D8), and where update-funnel
counts come from (D9).

The options considered, grouped by tension:

**D1 — where OTA releases live.**

1. **A parallel OTA release table.** A dedicated pipeline owning its
   own lifecycle. Rejected: approval workflows, the policy engine,
   gates, status history, and analytics rollups all key on
   `releases`; a second table forks every one of those surfaces into
   a stale-prone twin.
2. **Reuse `releases` / `release_targets` / `rings`.** OTA is a
   distribution channel of the existing release machinery, with the
   new `ota_bundles` / `ota_rollout_*` tables hanging off the
   release row.

**D3 — how a device authenticates the check.** The check happens
before any download, so no `sdt1.` telemetry grant exists yet.

1. **mTLS device certificates.** Rejected: mobile fleets cannot do
   it — the platform's devices are end-user phones, not enrolled
   infrastructure.
2. **The app's `bundleId` as the identifier.** No new credential to
   ship, but a bundleId is not tenant-unique — the same package name
   exists across organizations and tenants (the companion-spike gap
   note, `docs/companion-spike.md` "Platform gaps", is the concrete
   instance of the ambiguity) — so it cannot resolve an org/tenant.
3. **A new `sdc1.` app token.** An HMAC codec mirroring `sdt1.` /
   `TelemetryTokenCodec`, minted per application, embedded in the
   shipped app.

**D8 — bundle signing custody.** Devices must verify the downloaded
bytes end-to-end; whoever holds the signing key owns that guarantee.

1. **Keys as deployment configuration.** Environment-variable or
   committed key material. Rejected outright: the platform's
   credentials doctrine (ADR-0008, and the `webhook_secret`
   precedent before it) never commits key material, and a shared
   deployment key is neither per-app nor revocable per-app.
2. **A hand-rolled signature scheme.** No dependency cost, total
   control. Rejected: ADR-0009's doctrine — security-critical crypto
   belongs to proven libraries, never a home-grown path.
3. **Ed25519 per app, private key in the secret-store port.** The
   ADR-0008 store generalizes from channel credentials to an
   app-scoped `OtaSigningKeyService`; verification stays in the
   platform stacks (JCA on the server, CryptoKit in the iOS SDK).

**D9 — where funnel counts come from.** `analytics_events` is the
platform's telemetry ledger.

1. **Devices report `OTA_CHECK` like any lifecycle event.** Rejected:
   `analytics_events.release_id` is NOT NULL (V38
   `V38__release_analytics.sql`) and a no-update check has no
   release to attribute — the schema refuses the design.
2. **Count everything server-side.** Rejected: download, apply,
   rollback happen inside the app after the connection is gone; the
   server can only infer them, and inference is not telemetry.
3. **Split the funnel.** Checks counted server-side; device
   lifecycle events reported through the existing `sdt1.`-bound
   ingest.

## Decision

**D1: reuse. D3: the `sdc1.` token. D8: app-scoped Ed25519 in the
secret store. D9: the funnel splits.**

1. **D1 — OTA releases reuse the release machinery.** An OTA release
   is a normal `releases` row on a ring whose free-form
   `distribution_channel` (V32 `V32__release_rings.sql`, varchar(64))
   carries `'ota'`; `ota_bundles` and the `ota_rollout_*` triple hang
   off the release. Each bundle gets its own `versions` row (D2), so
   the `version_id` FKs on `releases` / `release_targets` /
   `analytics_events` and the REVOKED lifecycle stay intact.
2. **D3 — the `sdc1.` app token is an identifier, not a secret.** A
   new `OtaAppTokenCodec` mirrors `sdt1.` /
   `analytics/TelemetryTokenCodec.java`; tokens are minted per
   application from console/CLI, embedded in shipped apps, and held
   in a hashed, revocable, app-scoped registry (`ota_app_tokens`,
   V55). The token enables rate limiting and unambiguous
   org/tenant/app resolution at check time — it authorizes the check
   surface only, never secret data.
3. **D8 — Ed25519 per app; custody in the secret-store port.** The
   ADR-0008 port (`distribution/internal/secret/`) generalizes to an
   app-scoped `OtaSigningKeyService`: the bundle sha256 is signed at
   finalize, the private key lives behind the store in either
   posture (GCP Secret Manager in production, the AES-256-GCM
   envelope store self-hosted), and the public key plus `keyId` ride
   the check response — copyable in the console for SDK embedding.
   Key rotation is a new key under a new `keyId`. Verification is
   JCA / CryptoKit only (the ADR-0009 doctrine: no hand-rolled
   signature paths), so no new crypto dependency enters the tree.
4. **D9 — the funnel splits at the manifest.** Checks are counted
   server-side (Micrometer meters, plus a server-inserted
   `OTA_CHECK` analytics event only when the outcome is
   update-available and the release is known); device lifecycle
   events (`OTA_SEEN`, `OTA_DOWNLOAD`, `OTA_APPLIED`, `OTA_FAILED`,
   `OTA_ROLLED_BACK`) flow through the existing `sdt1.`-bound ingest.
   The driver is the schema: `analytics_events.release_id` is NOT
   NULL (V38), and `sdt1.` grants exist only when a manifest was
   returned (D4 mints one at check time) — the split preserves both
   invariants instead of fighting them.
5. **Supporting decisions anchored here, recorded in full in
   `docs/ota-updates-plan.md`.** D4: the check response mints the
   `sdt1.` ingest grant (the existing codec, carrying releaseId +
   deviceId — zero new auth surface). D5: downloads ride the
   ADR-0003 `ArtifactStore` port and the existing
   `DownloadTokenCodec` / `DownloadProxyController` mint, under
   ADR-0003's deployment-mode branch (V4 signed URLs in production,
   proxy tokens on the local stack) — a bundle is an ordinary
   `OTA_BUNDLE` artifact, no new storage path. D6: rollout
   bucketing is deterministic on the fly —
   `hash(device_id, plan_salt) mod 10000 < stage.basis_points` —
   stable per device with no cohort table (devices are not
   enumerable, unlike the V48 `V48__staged_tenant_rollout.sql`
   tenant cohorts); the `ota_rollout_*` triple mirrors V48's
   plans/stages/events shape and optimistic `version` column, with
   its own status vocabulary adding HALTED and PAUSED. D7: `forced` /
   `min_app_version` are per-bundle defaults, overridable per
   release target (per-ring) at release time. D10: alerts persist,
   surface in the console, and fan out over the ADR-0007
   notification bus — halt stays a manual, audited operator action;
   auto-halt is rejected. D11: SDK updater modules are strictly
   mobile (Kotlin, Swift) in v1; desktop adoption is a follow-up.

## Consequences

- Governance has one home: approvals, gates, the policy engine,
  status history, and rollups keep keying on `releases`; OTA is a
  channel, not a parallel product. The price is invariant upkeep in
  `ReleaseService` / `PromotionService` — an OTA-channel release
  carries exactly one bundle — and ring eligibility gaining the
  bundleId/platform branch that closes the companion-spike gap.
- A leaked `sdc1.` token lets a bearer ask whether an update exists,
  rate-limited and resolved to exactly one org/tenant/app; it grants
  no artifact reads and no cross-tenant surface. Revocation is the
  hashed registry's delete; the token ships inside an app binary and
  must be treated as public-known from the day it is embedded.
- Signing custody inherits ADR-0008's postures wholesale: no
  database plaintext, no committed material, a blank self-hosted KEK
  is the loud ephemeral fallback — never production. Rotation is
  generational (new `keyId`, old verifications age out with the
  embedded public keys), which makes rotation cadence a console
  concern, not a migration.
- Funnel numbers are honest by construction: no-update checks can
  never fabricate release attribution. The sequenced cost lands in
  V57 — the V38 CHECK constraints
  (`ck_analytics_events_result_shape`,
  `ck_analytics_events_session_shape`) are closed disjunctions that
  must be rewritten before the first OTA event type inserts, and the
  enum `ADD VALUE` keeps its own migration file (the V48 caveat).
- Bucketing without a cohort table trades a write per device for a
  hash evaluation per check — cheap and stateless — and makes
  stage advancement instantly effective for every device not yet
  updated, with no cohort backfill ever needed.
- Alert-only governance is deliberately slower than auto-halt: a
  degradation pages a human through the notification bus and the
  halt is one audited action away (D10). If a future need outgrows
  that — delta bundles, desktop SDKs, automatic halt — this record
  and `docs/ota-updates-plan.md` are the places to revisit, the
  same way ADR-0009 defers its SAML follow-ups.
