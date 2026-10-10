# Distribution: Channel Adapters & Credential Delegation

The distribution adapter framework (issue #61, S13-01, FR-019, PRD
P6/19/25): every delivery channel — the platform's own Direct
download tier today; Apple TestFlight (#62–#63) and Google Play
(#64–#65) next; MDMs later — sits behind ONE interface, so a new store
plugs into distribution without a core change, and store credentials
live behind secret references the platform holds instead of secret
material.

- The SPI and its contract: `DistributionChannelAdapter` (below)
- The credential delegation chain (PRD 25): [Credential
  delegation](#credential-delegation)
- Where secret material actually lives: [Secret
  stores](#secret-stores) and [ADR-0008](adr/ADR-0008-credential-secret-storage.md)
- The dispatch lifecycle and its records: [Dispatch](#dispatch)
- The sandbox (timeouts, retries, the failure taxonomy): [Adapter
  sandbox](#adapter-sandbox)
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
| `distribute(command)` | Deliver the command's artifact slots to its targets. Sandbox-walled (see below); reports one `AdapterOutcome` per slot: the record's status, the provider's external id (store reconciliation's key), and on failure the taxonomy code. |
| `status(artifactId, channel, externalId)` | The provider-side status of one distributed artifact — the reconciliation half the #62–#65 store polls ride. |

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
