# Distribution: Channel Adapters & Credential Delegation

The distribution adapter framework (issue #61, S13-01, FR-019, PRD
P6/19/25): every delivery channel — the platform's own Direct
download tier; Apple TestFlight since #62 (submission and phased
release follow in #63); Google Play (#64–#65) and MDMs later — sits
behind ONE interface, so a new store plugs into distribution without
a core change, and store credentials live behind secret references
the platform holds instead of secret material.

- The SPI and its contract: `DistributionChannelAdapter` (below)
- The credential delegation chain (PRD 25): [Credential
  delegation](#credential-delegation)
- Where secret material actually lives: [Secret
  stores](#secret-stores) and [ADR-0008](adr/ADR-0008-credential-secret-storage.md)
- The dispatch lifecycle and its records: [Dispatch](#dispatch)
- The sandbox (timeouts, retries, the failure taxonomy): [Adapter
  sandbox](#adapter-sandbox)
- The first store connector — Apple TestFlight (#62): [The TestFlight
  connector](#the-testflight-connector)
- How to add a channel: [Adding an adapter](#adding-an-adapter)

## The SPI

`com.shipperdog.api.distribution.DistributionChannelAdapter` is the one
interface every channel implements as a Spring bean:

| Member | Contract |
|---|---|
| `channelId()` | The channel's stable reference id (`direct`, `testflight`, `play`) — the SAME vocabulary the environment and ring surfaces bind (`environments.distribution_channel`, `rings.distribution_channel`). Stored on distribution records; never renamed once served. `direct` is reserved for the platform's own download tier and is the default when an environment binds no channel. |
| `displayName()` / `description()` | Catalog facts the console renders. |
| `capabilities()` | The declared surface: `BUILD_UPLOAD`, `BUILD_STATUS`, `TESTER_GROUP_SYNC`, `SUBMISSION`, `ROLLOUT_CONTROL`, `DIRECT_ISSUANCE` (`AdapterCapability`). Data for operators, never a dispatch branch. |
| `credentialProviders()` | The provider vocabulary the channel's dispatches resolve the delegation chain for (`apple`, `google-play`). Empty means the channel needs no store account (Direct). |
| `distribute(command)` | Deliver the command's artifact slots to its targets. Sandbox-walled (see below); reports one `AdapterOutcome` per slot: the record's status, the provider's external id (store reconciliation's key), and on failure the taxonomy code. Since #62 the outcome also carries the provider's own lifecycle word (`providerStatus`, Apple's beta-review states) verbatim for the release dashboard. |
| `status(artifactId, channel, externalId, credential)` | The provider-side status of one distributed artifact — the reconciliation half the #62–#65 store polls ride. Since #62 the refresh-resolved credential rides the call (null when the channel declared no providers — Direct has none to poll). |

The `DistributionCommand` is complete and self-contained: the release's
identity and owning dimensions, the environment it targets (code and
resolved configuration), the frozen artifact slots (platform, format,
file name, size), the target tenant set, the credential the chain
resolved (reference + material behind `SecretMaterial`, whose
`toString()` is `<redacted>` by construction), and the trace context of
the adapter-call observation (`traceId`/`spanId`; PRD 28 — store
latency is a top debugging question).

Rules the framework holds for every adapter:

1. **A bean is the registration.** The registry
   (`DistributionAdapterRegistry`) collects every
   `DistributionChannelAdapter` bean at startup; duplicate channel ids
   fail startup. Adding a channel in a deployment is a configuration
   concern, never a code change (the framework proof: the integration
   suite registers a complete stub adapter from a test configuration
   with zero core changes).
2. **The adapter talks to its provider, never to platform tables.**
   Distribution records, credentials, and audit are the framework's.
   The provider SDK (App Store Connect API, Google Play Developer API)
   lives inside the adapter's implementation package only.
3. **Failures exit as `AdapterException`.** Anything else is
   `PERMANENT` (the taxonomy is closed). Never log credential material.

## Credential delegation

Store accounts are configured at the organization level and inherited
downward unless explicitly overridden (PRD 25):

```
Organization      (the default Apple / Google Play accounts)
    ↓ inherit
Application       (optional override)
    ↓ inherit
Application + Environment   (optional override — Production ≠ QA)
```

Resolution walks most-specific-first — environment, then application,
then organization — and the first defined row wins, per provider
independently. `uq_store_accounts_level_provider` (NULLS NOT DISTINCT)
makes "defined at a level" unambiguous: one account per provider per
level, so an override is a replacement, never a merge. Rotation in
place re-points the row's reference and the store discards the
replaced material's stored copy (`ChannelSecretStore.delete`; the
envelope store removes its ciphertext row, the GCP posture's rotation
is a new version under the same reference and answers nothing to
delete).

The console surface (org-grain, `org:distribution:read/manage`):

| Route | Purpose |
|---|---|
| `GET /api/v1/distribution/channels?organizationId=` | The channel catalog (registered adapters, capabilities, credential providers). |
| `GET /api/v1/distribution/credentials?organizationId=` | The organization's bindings across the chain's levels. |
| `GET /api/v1/distribution/credentials/effective?organizationId=[&applicationId=][&environmentId=]` | The delegation matrix: for every provider bound anywhere, the row that wins at the addressed scope. |
| `PUT /api/v1/distribution/credentials` | Bind or rotate: `{organizationId, applicationId?, environmentId?, provider, name, metadata?, secretMaterial}`. Material enters exactly once; the row keeps only the reference. |
| `DELETE /api/v1/distribution/credentials/{credentialId}?organizationId=` | Remove a binding; the scope falls back to the next level's row (or none). |
| `POST /api/v1/distribution/credentials/{credentialId}/verify?organizationId=` | The resolution probe: `{resolved}` — never what the reference resolves to. |
| `GET /api/v1/distribution/records?organizationId=&releaseId=` | One release's distribution records (the reconciliation view). |

An environment-level binding must name its application (the chain's
levels are org, app, app+env). The environment module's own credential
bindings (#58) share the same resolution port — their verify probe now
resolves through the selected secret store too.

## Secret stores

The platform holds references, not credentials (PRD 25). Two
implementations of `ChannelSecretStore` ship, selected by
`shipperdog.secrets.provider`:

- **`envelope`** (default): the OSS AES-256-GCM envelope store — one
  random data key per secret, the data key wrapped by the KEK from the
  deployment environment (`SECRETS_ENVELOPE_KEK`, Base64 of 32 bytes;
  blank mints an EPHEMERAL process-local key with a loud startup
  warning — local stacks only, a restart stops every reference
  resolving), reference and key id bound as AAD. Ciphertext lives in
  the `managed_secrets` table; the schema test pins that it is
  ciphertext only.
- **`gcp-secret-manager`** (the production posture of PRD 25):
  material lives in GCP Secret Manager (Cloud KMS at rest); the store
  speaks the v1 REST surface with application-default credentials and
  the platform holds only `gcp-secret-manager://projects/.../secrets/...`
  references.

References are namespaced by store; a store resolves only its own
namespace and answers empty for every other shape — an unknown,
deleted, rotated-away, or access-denied reference is
indistinguishable by design. The decision record is
[ADR-0008](adr/ADR-0008-credential-secret-storage.md).

## Dispatch

A release reaches its audience through the state machine (PRD 17); the
DEPLOYING → ACTIVE crossing is where channels take over. The
dispatcher (`DistributionDispatchService`) listens on
`ReleaseTransitionedEvent` synchronously — inside the crossing's
committing transaction, the manifest-freeze precedent — and:

1. Resolves the channel: the targeted environment's
   `distribution_channel` binding, `direct` when unbound or no
   environment is targeted. An unregistered channel id writes FAILED
   records with `CHANNEL_UNKNOWN` and audits; the crossing still
   commits (a configuration fault is recorded loudly, never a silent
   release stall).
2. Ensures one distribution record per (release, artifact, channel) —
   the unique index is the idempotency grain; re-dispatch reports onto
   the same rows.
3. Resolves the credential: the adapter's declared providers against
   the delegation chain; a provider with no binding anywhere — or a
   secret store that faults while resolving the bound material (a
   broken token source answers "does not resolve", never a throw)
   writes FAILED records with `CREDENTIAL_UNRESOLVED` (Direct needs
   none).
4. Calls the adapter under the sandbox and applies its report verbatim:
   status, external id, failure code, and the sandbox's consumed-call
   count onto the attempt clock.

**Store failures never fail the crossing.** An exhausted sandbox lands
FAILED records with the taxonomy code and audits the dispatch; the
release still went ACTIVE, because its platform-side availability (the
authorization engine's decisions) is not the store's to take away.
Rollback orchestration is S13-06; when the #62–#65 connectors'
latencies demand it, the sandbox call moves off the crossing's
transaction onto the bus — the listener and records stay.

The record lifecycle is V1's own `distribution_status` vocabulary —
PENDING, DISTRIBUTING, DISTRIBUTED, FAILED — reused as the new table's
column type (the V1 `distribution` sketch table itself stays
reserved-unused, its PORTAL/EMAIL enum vocabulary superseded by the
reference-name column V27/V32 established).

## Adapter sandbox

Every adapter call runs under a per-channel sandbox
(`shipperdog.distribution.adapters.<channel>.*`, defaults 30 s wall /
2 retries / 1 s backoff):

- one traced, timed observation per attempt
  (`shipperdog.adapter.call.duration`, tagged `channel`, `attempt`,
  `outcome` — the timer and the trace span are the same artifact);
- a wall-clock cut that interrupts the hung call;
- bounded retries keyed on the failure taxonomy.

The taxonomy (`AdapterException.Category` → public code → retry):

| Category | Retried | Exhausted code | Meaning |
|---|---|---|---|
| `TRANSIENT` | yes | `ADAPTER_FAILED` (502) | Upstream hiccup; retry with backoff. |
| `RATE_LIMITED` | yes | `ADAPTER_RATE_LIMITED` (503) | Upstream throttled. |
| `TIMEOUT` | yes | `ADAPTER_TIMEOUT` (504) | The wall cut the call. |
| `AUTHENTICATION` | never | `ADAPTER_AUTHENTICATION_FAILED` (502) | The credential was rejected — retrying a bad credential is a lockout generator. |
| `PERMANENT` | never | `ADAPTER_FAILED` (502) | Final fault (any non-taxonomy throw lands here). |

The codes ride distribution records as `failure_code` and are
registered in the public [error code registry](error-codes.md).

## The TestFlight connector

The first store connector on the SPI (issue #62, S13-02, FR-020,
PRD 19/25): the `testflight` channel — the App Store Connect API
conversation behind a release's ACTIVE crossing. Build upload stays
with CI or Xcode Cloud (the PRD's "build upload where applicable"):
the connector distributes EXISTING ASC builds, binding each ShipperDog
artifact to its ASC peer by the binary's own extracted facts (bundle
id and build number) and assigning it to the mapped TestFlight beta
groups.

### The API key (AC1)

Apple's triple rides the delegation chain exactly as PRD 25 shapes
it: the .p8 key is the binding's secret material (the store's
reference, never a column), and the issuer id and key id ride the
binding's non-secret metadata — `{"issuerId": "...", "keyId":
"..."}` beside the material, exactly what the App Store Connect
console hands an operator. The connector signs an ES256 JWS per
Apple's model (header `kid`, payload `iss`/`iat`/`exp`/`aud=asc`,
expiry at most twenty minutes) with the JDK's own JCA — no JWT
library — and caches one live token per (issuer, key id, key)
triple, refreshing a minute before expiry. A rotated key is a
different triple: its first call mints fresh and the superseded entry
is never answered again. An incomplete or malformed triple (missing
ids, control characters, wrong key format) is a configuration fault —
PERMANENT, never retried, never dialed.

### Group mapping and sync (AC2, AC5)

ShipperDog tester groups ↔ ASC beta groups link through
`channel_group_links` (V44): one row per (channel, tester group),
scoped to the application whose ASC app the link binds against — the
sync resolves the credential chain AT that scope, so an app-level
apple binding serves the sync exactly as it serves the dispatch.

| Route | Purpose |
|---|---|
| `GET /api/v1/distribution/testflight/groups?organizationId=` | The organization's links. |
| `PUT /api/v1/distribution/testflight/groups` | Link `{organizationId, applicationId, groupId, createOnDemand}`: an existing same-named external group wins; `createOnDemand` mints one; the link lands with its first membership push. |
| `POST /api/v1/distribution/testflight/groups/{linkId}/sync?organizationId=` | The idempotent re-sync: pushes membership, reports drift. |
| `DELETE /api/v1/distribution/testflight/groups/{linkId}?organizationId=` | Remove the link (the ShipperDog side only — an ASC group is never deleted). |

The sync's doctrine is **never-remove**: membership pushes are
additive (a second run adds nothing — idempotent), and provider-side
drift — testers Apple has that ShipperDog does not, builds manually
unassigned from the group — is REPORTED in the sync's answer and the
`DISTRIBUTION_GROUP_SYNCED` audit, never force-removed. An operator's
manual ASC work is evidence, not garbage; the safe re-sync's answer
is the report a Release Manager acts on. Unmatched external testers
are reported, not force-linked (the story's own note).

### Dispatch (AC3)

An ACTIVE crossing on an environment bound to `testflight` runs the
adapter under the sandbox: resolve the ASC app by bundle id, resolve
each iOS artifact's build by its extracted build number, assign the
build to every target group whose link the framework resolved (the
adapter never reads platform tables — the command carries the target
groups with their resolved links). The record lands DISTRIBUTING with
the ASC build id as `external_id` (Apple owns the next step), and a
release whose target groups carry no links fails its records loudly
with `TESTFLIGHT_GROUPS_UNMAPPED` — the mapping surface's gap, never
a silent skip. Non-iOS slots fail `TESTFLIGHT_IOS_ONLY`; a build
absent from ASC fails `TESTFLIGHT_BUILD_NOT_FOUND`.

### Beta review and reconciliation (AC4, AC5)

The record carries the provider's own lifecycle word:
`provider_status`/`provider_status_at` (V44) hold Apple's beta-review
state verbatim (`WAITING_FOR_BETA_REVIEW`, `IN_BETA_REVIEW`,
`READY_FOR_BETA_TESTING`, `BETA_REJECTED`, `EXPIRED`, ...), written
by the dispatch's first observation and every refresh.

- **The explicit refresh** — `POST
  /api/v1/distribution/records/{recordId}/refresh?organizationId=` —
  runs the adapter's status answer under the same sandbox and applies
  it verbatim: the release dashboard's beta-review answer (the
  console's distribution block renders the word beside the platform
  status). `READY_FOR_BETA_TESTING`/`IN_BETA_TESTING` moves the
  record DISTRIBUTED; the review words hold DISTRIBUTING; rejected
  and expired fail their honest codes.
- **The scheduled pass** — `shipperdog.distribution.reconciliation.
  enabled` (default **false**: a self-hosted stack never wakes up
  dialing Apple; the production opt-in is on the [deployment
  wiring](deployment.md)) — refreshes every PENDING/DISTRIBUTING
  store record with an external id on a fixed delay, one record's
  provider fault never the pass's.

### Rate limits (AC6)

Apple's 429 answers map to the sandbox's RATE_LIMITED category —
retried with the sandbox's backoff, and counted in the
`shipperdog.adapter.ratelimit` counter (tagged `channel`) the moment
the provider throttles, whether the retry lands or the exhaustion
fails the record with `ADAPTER_RATE_LIMITED`.

### The portal's install handoff

The download tier asks the distribution module through the
`ChannelDistributionRoutes` port: an artifact with a live
DISTRIBUTING/DISTRIBUTED record on a store channel routes through
THAT channel's mechanics. For TestFlight/iOS that is the S8-04
route-table row — `DEEP_LINK_TESTFLIGHT`, the `itms-beta://` deep
link — with no platform URL minted, no download counter, and no
platform download event: Apple's tier delivers the bytes while the
engine's DOWNLOAD decision still governs who may ask (PRD 19's
constraint — ShipperDog owns authorization and evidence, the channel
owns installation mechanics).

## Adding an adapter

1. Implement `DistributionChannelAdapter` as a Spring bean (its
   package under `distribution/internal/` keeps the module boundary
   honest; the provider SDK stays inside the adapter's package).
2. Pick the channel id — lowercase kebab, stable forever, matching the
   vocabulary environments and rings bind.
3. Declare capabilities and (if the channel needs accounts) credential
   providers; resolve material only at the provider call, never log
   it.
4. Exit failures as `AdapterException` with the honest category.
5. Nothing else: registry, catalog, credential resolution, records,
   trace, metrics, and audit follow with zero framework edits.
