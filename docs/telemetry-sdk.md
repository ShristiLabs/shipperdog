# Tester Telemetry SDK (S15-01)

The embedded SDK of PRD §37 (issue #72, FR-029, §33/§37): the small
library a test build includes to report install confirmation,
session summaries, and crash context to ShipperDog's frozen events
contract ([analytics](analytics.md)) — turning download counts into
the real install/crash/engagement metrics the gates (S10-03) and
policies (S10-06) evaluate. A telemetry beacon, not a framework:
crash REPORTING beside the customer's own tooling, never crash
ANALYSIS instead of it (PRD §34 — the SDK does not replace
Sentry/Crashlytics and ships no symbolication, grouping, or
signal-level handlers).

- What ships: [The two SDKs](#the-two-sdks)
- The ≤ 5-line integration: [Android](#android) and [iOS](#ios)
- How the SDK authenticates: [The telemetry token](#the-telemetry-token)
- What is (and is never) collected: [Privacy](#privacy)
- Offline behavior: [Offline batching and dedup](#offline-batching-and-dedup)
- Crash handling: [Crash context](#crash-context)
- The feedback handoff: [In-app feedback deep link](#in-app-feedback-deep-link)
- Size and battery: [Budgets](#budgets)
- Metrics this activates: [What the platform computes](#what-the-platform-computes)
- The uninstall metric's approach: [Uninstall absence heuristics](#uninstall-absence-heuristics)
- A live contract check: [Contract smoke against compose](#contract-smoke-against-compose)

## The two SDKs

| Platform | Path | Language | Dependencies | Size (stripped) |
|---|---|---|---|---|
| Android | `sdk/android` | Kotlin (JVM core + Android adapter) | ZERO runtime deps | ~36 KB (core 32 KB + adapter 4 KB) |
| iOS | `sdk/ios` | Swift (Swift Package) | ZERO external deps (Foundation) | ~183 KB static library |

The Android artifact is a pure Kotlin/JVM core (`:telemetry` — every
platform API it touches is JDK API Android ships: HttpURLConnection,
Files, Thread's uncaught handler) plus a thin adapter
(`:telemetry-android`) holding the ONLY Android-framework code: the
Application-lifecycle session tracking and the feedback deep-link
intent. The adapter compiles against the real framework classes (the
Robolectric `android-all` stub, `compileOnly` — never packaged), so
CI proves the integration compiles against actual Android API
without an emulator. The iOS package is one Foundation-only target
with the UIKit wiring guarded by `canImport(UIKit)`: the package
builds and tests on macOS while shipping the iOS paths (CI
typechecks them against an iOS simulator target).

## Android

The complete integration — the whole of it, proven compiling in
`sdk/android/sample`:

```kotlin
class DemoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val telemetry = TelemetryConfig(          // pasted from the download answer's telemetry block
            baseUrl = "https://shipperdog.example.com",
            token = telemetryBlock.token,
            tenantId = telemetryBlock.tenantId,
            applicationId = telemetryBlock.applicationId,
            releaseId = telemetryBlock.releaseId,
        )
        ShipperDogAndroid.start(this, telemetry)  // crash chain + sessions + install report + flushes
    }
}
```

`start` does everything: the offline queue directory, the chainable
crash handler, the crash-marker fold, the first-launch INSTALL
report (once per release), and the activity-lifecycle callbacks that
open and close sessions — each close flushes the queue. The host may
also call `sdk.optOut()` (settings surface) and
`ShipperDogAndroid.feedbackIntent(sdk, portalUrl, crashSummary)` for
the [feedback deep link](#in-app-feedback-deep-link).

## iOS

The complete integration — `sdk/ios/Samples/SDTelemetryDemo` carries
the same shape:

```swift
func application(_ application: UIApplication,
                 didFinishLaunchingWithOptions _: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
    let sdk = ShipperDogTelemetry.start(config: TelemetryConfig(
        baseUrl: "https://shipperdog.example.com",
        token: telemetryBlock.token,
        tenantId: telemetryBlock.tenantId,
        applicationId: telemetryBlock.applicationId,
        releaseId: telemetryBlock.releaseId,
        queueDirectory: applicationSupportDir))
    CrashReporter.install(sdk: sdk)
    sdk.installSessionTracking()   // foreground/background observers + flushes
    return true
}
```

## The telemetry token

The SDK's ONLY credential is the telemetry token the download
issuance mints (S15-01's authority evolution of the frozen events
contract): when a tester downloads through the portal, the issuance
answer carries a `telemetry` block — `{token, expiresAt, tenantId,
applicationId, releaseId, versionId, deviceId?}` — and the portal's
guidance dialog surfaces the token with a copy action. The block is
the SDK configuration; nothing else identifies the tester.

Server-side (the `TelemetryTokenCodec`): an opaque HMAC-SHA256
credential (`sdt1.…`, the DownloadTokenCodec doctrine) bound to
(the reporting tenant, the downloading tester, the release of the
downloaded version, and optionally one registered device). It is
narrow-capability by construction — exactly one authority
(`analytics:telemetry:ingest`), honored by nothing but the events
ingest, with the bindings enforced per batch:

- the request path's tenant and every event's release must be the
  token's own (a mismatch is the concealed `RELEASE_NOT_FOUND`,
  byte-identical to the genuine miss — a stolen token learns nothing
  about other tenants' or releases' existence);
- a device-bound token accepts only its device, and ANY named device
  must be the downloading tester's own ACTIVE device (spoofing
  another tester's device is the same field-addressed refusal);
- there is no client-supplied identity field anywhere on the
  contract — the token IS the reporter.

Revocation levers, in order of reach: the TTL (default 90 days,
`TELEMETRY_TOKEN_TTL` — a beta cycle's honest horizon); the device
registry's reversible status switch for device-paired tokens
(`DEACTIVATED` fails the ingest's ACTIVE check — the two-switches
rule of [devices](devices.md)); and key rotation
(`TELEMETRY_TOKEN_KEY`), the break-glass lever that invalidates
every minted token at once (the download proxy key's own posture).

The `device_id` pairing parameter exists on the issuance route for
programmatic flows; the portal does not send it today (testers do
not see the device registry), so portal-minted tokens are
tester+release scoped and the SDK's optional `deviceId`
configuration carries the registered device's id when operations
pair one (the ingest validates it as the tenant's own ACTIVE device
either way).

A second minter exists since the OTA work: the S16-07 check manifest
carries an `ingestToken` (`sdt1.`, device-unbound by design) for
fleet devices, and the S16-08 OTA lifecycle types ride the same
ingest under it — the SDK's OTA reporting lands with the updater
work (S16-12/S16-13); the contract is the OTA family extension of
[analytics](analytics.md).

## Privacy

Structural, not promissory: the payload vocabulary is closed
(INSTALL/SESSION/UNINSTALL/TESTER_VERDICT with counts and ids), and
the only identifiers it can carry are the telemetry token and the
paired device's REGISTRY id — never a UDID or serial (the identifier
never leaves the device module), never a user id, email, or free
text. Crash context ships as COUNTS (the crash-free metric's
numerator); stacks appear only inside the customer's own crash
tooling and, when the tester chooses to report, the [feedback deep
link's](#in-app-feedback-deep-link) capped summary. `optOut()` stops
all reporting and drops the queued history (and the one-shot
markers), and the SDK adds no network call, storage write, or
background work beyond the lifecycle-driven flushes.

## Offline batching and dedup

Every event lands in a bounded, file-backed queue first (default
2,000 events, `maxQueueEvents`; the OLDEST drop first — a device
offline for weeks is never a disk liability, and the freshest
telemetry is what survives). Flushes batch at most 500 events per
POST (the frozen contract's ceiling), one release per batch.
Accepted (2xx) and permanently refused (4xx — the batch's own shape;
the field-keyed report names the offender) batches leave the queue;
transport failures and 5xx KEEP the batch — at-least-once delivery.
Of the 5xx family, only the ingest's write contention (503
`INGEST_WRITE_CONTENTION` — a concurrent batch landed one of this
batch's events first) answers with a `Retry-After` header: advisory
for the SDK, whose retry cadence is the host's next lifecycle moment
anyway, and a bound for any custom integrator building its own
sender — honor it before re-flushing the kept batch.
A 401 drains the queue BY DESIGN: an expired or rotated token is
credential death (it never recovers), so those batches are dropped
rather than retried forever — the next download mints the fresh token
the new queue carries.

Retries are therefore idempotent end to end: every event carries a
fresh client `eventId` (UUID), and the ingest dedups by
(device, release, event id) — the one additive member S15-01 froze
into the contract for exactly this purpose (batches WITHOUT ids keep
the original land-twice semantics; see
[analytics](analytics.md#the-frozen-sdk-events-contract-s15-01-implements-this)).

## Crash context

**Android:** the handler installed over
`Thread.setDefaultUncaughtExceptionHandler` writes a durable crash
marker (a file write — the only act safe on a failing process) and
ALWAYS delegates to the previously installed handler. Nothing is
swallowed: Crashlytics/Sentry/Bugsnag keep every crash exactly as
before. The next launch folds the marker into a SESSION summary (one
session, one crash) — the crash-free metric's numerator.

**iOS:** `NSSetUncaughtExceptionHandler` chaining with the same
marker-and-delegate doctrine. Scope stated honestly: NSException
crashes are the covered set; SIGSEGV/SIGABRT-level faults terminate
the process before any handler can act — signal coverage is crash
analytics, deliberately not this beacon's job.

Hosts can flag a non-fatal crash context themselves
(`ForegroundGate.markCrashed` on Android; enqueue a session with
`crashes: 1` on iOS).

## In-app feedback deep link

The feedback handoff of PRD FR-027: `feedbackUrl(portalBaseUrl,
crashSummary?)` builds the tester portal's version deep link with
`?report=1&hint=<capped summary>` (2,000 characters, whitespace
collapsed — a URL parameter, not a log shipper), and the portal
opens its report dialog with the hint prefilled (the tester edits
and submits — nothing posts itself; the full submission contract is
the [portal's](portal.md#feedback-submission-s11-01)). Android opens
it via `ACTION_VIEW`; iOS via `UIApplication.open`.

## Budgets

**Size (< 200 KB per platform, CI-asserted):** the Kotlin core +
adapter jars total ~36 KB; the iOS static library, stripped, is
~183 KB for the whole macOS slice — and an app's dead-code
elimination links only what the integration calls, so the shipped
increment is smaller. `sdk-ci.yml` measures both on every PR.

**Zero background battery (documented posture):** no services, no
receivers, no timers, no background tasks, no push. Every network
call rides a lifecycle moment the host app already has — session end
(Android's last activity stopped; iOS's `didEnterBackground` within
the system's own grace window) or an explicit host call. Between
those moments the SDK does literally nothing.

## What the platform computes

With the SDK landed, the DEVICE_SDK family of the metrics dictionary
flips from the explicit NO_DATA (fail-closed — never a zero, never a
pass) to real values: `INSTALLATIONS` and `INSTALL_SUCCESS_RATE`
from INSTALL reports, `CRASH_FREE_SESSIONS` and `ANR_RATE` from
SESSION summaries, `UNINSTALL_RATE` from UNINSTALL reports. The
crash-free threshold gate and the §33 policy rules evaluate on the
same numbers (the gate seam in its percentage unit, the policy seam
in the dictionary's 0..1 rate) — the S10-03 activation the story
names: no sessions yet still blocks fail-closed; real data drives a
real verdict, including a data-driven refusal (the E2E test proves
both directions). Rollups fold the events hourly
([analytics](analytics.md) for the whole pipeline).

## Uninstall absence heuristics

A device cannot report its own removal, so the UNINSTALL event
belongs to flows that CAN observe it (an MDM integration, a
companion installer app — S15-02's spike is the natural first
emitter). Until an emitter exists, `UNINSTALL_RATE` answers NO_DATA
for SDK-only releases, and the platform's documented heuristic for
operators is comparative absence: a device whose INSTALL landed but
whose sessions stopped for a window the team defines (the per-device
analytics index, V38, serves the query) is the uninstall's proxy —
an operator-facing reading in the dashboards, never an event the
platform invents on the device's behalf.

## Contract smoke against compose

The backend suite's `TelemetrySdkIntegrationTest` is the E2E proof
(token mint at issuance → token-authenticated ingest → dedup →
metrics → the crash-free gate on real data), and the CLI
live-smoke precedent extends to the SDK: boot the compose stack,
provision a release, download through the portal API, and paste the
answer's telemetry block into the sample app's config — the sample's
flush posts to the live stack, and the release's metrics page shows
the install and session within the refresh window. The Kotlin core's
JVM suite covers the same wire shapes offline
(`TelemetryJsonTest`, `ShipperDogTest`), so the live smoke is a
wiring check, not the contract's only guard.
