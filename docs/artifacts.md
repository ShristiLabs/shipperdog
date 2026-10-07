# Artifacts

The artifact surface of ShipperDog: upload and object storage (S5-01,
issue #20), extracted metadata — iOS (S5-02, issue #21) and Android
(S5-03, issue #22) — build provenance capture (S5-04, issue #23), and
the validation pipeline with the artifact state machine (S5-05, issue
#24). An artifact is a
concrete immutable binary (PRD 8.5): bytes in object storage under the
PRD 24 layout
`/{organizationId}/{tenantId}/{applicationId}/{versionId}/{artifactId}/artifact`,
metadata only in PostgreSQL — never bytes (FR-007, ADR-0003).

## Routes

| Route | Permission | Notes |
|---|---|---|
| `POST /api/v1/artifacts` | `app:artifacts:upload` (DEVELOPER) | Multipart upload at or below the resumable threshold; honors `Idempotency-Key`; accepts the provenance form fields (S5-04) |
| `POST /api/v1/artifacts/upload-sessions` | `app:artifacts:upload` | Direct-to-storage session for large binaries (>100 MB rule); honors `Idempotency-Key`; captures provenance here (S5-04) |
| `POST /api/v1/artifacts/{artifactId}/finalize` | `app:artifacts:upload` | Registers the artifact after direct bytes; replays the registered state on retry; replays the session-captured provenance |
| `GET /api/v1/artifacts/{artifactId}?version_id={versionId}` | `app:artifacts:read` | The artifact read — where extraction outcomes land (S5-02), the provenance block answers (S5-04), and the validation block settles (S5-05) |
| `POST /api/v1/artifacts/{artifactId}/revalidate?version_id={versionId}` | `app:artifacts:upload` | The validation retry entry (S5-05): a FAILED artifact re-enters through `FAILED -> VALIDATING`, a blocked validation re-drives, a blocked extraction re-arms; 202 with the artifact view, 409 `ARTIFACT_NOT_VALIDATABLE` for anything else |
| `GET /api/v1/artifacts/{artifactId}/download?version_id={versionId}` | `isAuthenticated()` (the engine is the authority) | Download issuance (S8-04): the layered engine's DOWNLOAD decision for the caller is this route's entire authority — the #37 follow-up's deliberate relaxation of the console-grade gate (a pure tester holds no RBAC role, so a permission gate here would exclude the persona the download serves). ALLOW answers the routed channel/behavior, the short-lived signed URL, and its expiry — never the storage key; every denial conceals as the read's own two miss shapes. An ANDROID AAB is refused as the artifact-grain miss (S5-05: AAB is Play-only, never a direct download) |
| `GET /api/v1/downloads/{token}` | anonymous (the token is the credential) | The emulator-mode download proxy (S8-04, ADR-0003's download-tier amendment): stands in for GCS V4 signing wherever the storage target cannot verify a signature (the compose emulator). The HMAC-SHA256 token an engine ALLOW minted is unforgeable (not secret — the base64url payload is decodable by its holder) and TTL-bounded; every invalid, tampered, or expired token answers the same uniform 404 `DOWNLOAD_NOT_FOUND`. Production deployments mint GCS V4 URLs instead and never hand a client this route |

The surface is body-addressed: every route carries the version id in
the form/body/query (never the path), the permission evaluator
resolves it version -> application -> owning tenant/organization, and
every denial conceals as the uniform 404 of that target (a foreign
version answers `VERSION_NOT_FOUND`; an unknown artifact under your
own version answers `ARTIFACT_NOT_FOUND`). Since S7-01 (issue #30)
the read is additionally decision-guarded: the layered authorization
engine decides the subject's PRD 11 chain before the scoped data
read (deny-by-default; a pure tester denies until S7-02's grants),
rendering the same two conceal grains — see
[authorization](authorization.md). Since S7-03 the registry port
also answers the decision API's issuance question
(`issuanceInputsOf`: the version's distributable — READY or
DISTRIBUTED, newest first — artifact for a platform, with its PRD 24
storage key); since S8-04 those inputs are consumed server-side by
the download issuance route, which mints the signed URL — the key
never reaches a client on any path; the
engine's own artifact layer stays the availability authority.

## Artifact states and extraction (S5-02, issue #21)

A registered artifact lands as `VALIDATING`. Its bytes are then parsed
**asynchronously post-upload** on a bounded executor — the upload
response's `extraction.status` is always `PENDING`; read the artifact
back (`GET`) to watch it settle:

| `extraction.status` | Meaning | Artifact status |
|---|---|---|
| `PENDING` | Parsing has not produced an outcome yet | `VALIDATING` |
| `SUCCESS` | Fields extracted and stored | `VALIDATING`, then the validation pipeline (S5-05) settles it `READY` or `FAILED` |
| `FAILED` | The binary is malformed; `errorCode`/`errorMessage` are actionable | `FAILED` (the validation pipeline then records why on its own document) |
| `BLOCKED` | Extraction could not run (queue full, storage unreadable after retries); the error document says why | `VALIDATING` (the revalidation surface re-arms the block) |

On `SUCCESS`, the artifact view and the version views' artifact slots
carry the extracted identity fields: `bundleId`, `versionName`,
`minimumOs`, and `buildNumber` — filled from the binary only when the
uploader did not pin a CI build (a pinned build wins; a
`CFBundleVersion`/`versionCode` that is not a plain integer keeps the
column null and stores the raw string in the document). The full
extraction document (`extracted_metadata` jsonb) additionally carries
the display name, parser attribution, and timings; per platform: iOS
adds signing information lifted from the embedded provisioning profile
(profile name/UUID, team identifiers, expiration, entitlements —
fields stored only; verification is S5-05), Android adds
`targetOs` (targetSdkVersion, kept for M3's Play target-API policy
checks), `format` (the structurally detected APK/AAB verdict), and
`signingBlocks` (v2/v3 APK Signing Block pair presence, stored only —
see below).

### Extraction failure codes (stored, not HTTP)

`NOT_A_ZIP`, `ZIP64_UNSUPPORTED`, `MALFORMED_ZIP`,
`MISSING_INFO_PLIST`, `MALFORMED_PLIST`, `MISSING_MANIFEST`,
`MALFORMED_MANIFEST`, `MALFORMED_BUNDLE_CONFIG`, `UNKNOWN_FORMAT`,
`ENTRY_TOO_LARGE`, `ENTRY_ENCRYPTED`, `UNSUPPORTED_COMPRESSION` — the
binary's own faults, recorded in `extraction_error` with an
operator-readable message. `EXTRACTION_QUEUE_FULL` and
`EXTRACTION_UNAVAILABLE` are infrastructure blocks (the artifact stays
`VALIDATING`), likewise recorded so a missed extraction is visible
rather than silent.

### Parser

iOS (IPA): the ZIP central directory is walked with **ranged object
reads** — a 500 MB IPA costs kilobytes (the `< 30 s` SLO of PRD 42 is
met by not reading the 500 MB). `Info.plist` (binary or XML) parses
via [dd-plist](https://github.com/3to4nan/dd-plist) (pinned in
`backend/pom.xml`); the embedded provisioning profile's XML payload is
marker-extracted from the CMS container — full certificate chain
validation is S5-05. Both parsers sit behind the
`ArtifactMetadataParser` port.

Android (APK/AAB, S5-03): same ranged central-directory walk, one code
path for both formats. The format verdict is STRUCTURAL, never the
file name's claim: `base/manifest/AndroidManifest.xml` (or
`manifest/AndroidManifest.xml` beside a `BundleConfig.pb`) means AAB,
a root `AndroidManifest.xml` means APK, and anything else answers
`UNKNOWN_FORMAT` — the renamed-ZIP leg of the fixture matrix. Both
manifest shapes are Android binary XML (AXML), read by a minimal
in-house walker (`AxmlManifest`, ~250 lines): the string pool (UTF-16
or UTF-8) plus a start-element attribute walk with typed values,
bounded everywhere and identity-by-attribute-name, so no resource map
is needed. The library options were evaluated and declined:
`net.dongliu:apk-parser` is whole-file oriented and untouched since
2020, and bundletool drags protobuf+guava for execution concerns —
while this seam needs a bounded reader over the exact byte slice the
ZIP layer already fetched, the same reasoning that produced the
in-house `ZipRanges` walker (the fixture writer is an independent
implementation of the AXML format, which is what makes the matrix
round-trips meaningful). The AAB version-code nuance: bundletool
strips `android:versionCode` from the base-module manifest into
`BundleConfig.pb` (protobuf field 1), so the manifest is read first
and the config is consulted only when the attribute is missing — a
bounded varint walk, no protobuf runtime; the parser attribution
string records which source produced the build
(`android:axml-walker` vs `android:axml-walker+bundle-config-pb`).
Absent SDK levels or labels stay null (extraction reports what the
binary carries, not platform defaults; `@string/...` labels are
resource references and read as absent). Signing is M1 metadata only:
the APK Signing Block's footer and pair ids are probed with ranged
reads for the v2 (`0x7109871a`) and v3 (`0xf05368c0`) scheme markers —
presence stored in `signingBlocks`, never verified (S5-05 owns
judging signatures, and v1 JAR signatures in `META-INF` are out of
scope; a structurally inconsistent block reads as not-detected rather
than failing identity extraction).

## Validation pipeline and the artifact state machine (S5-05, issue #24)

The gate between upload and usability (FR-006, PRD 18): once the
extraction tier settles, a second async pipeline — its own bounded
executor, deliberately separate from the extraction pool since the
extraction job's settled phase is what schedules it — runs the seven
PRD-ordered stages and records every stage's outcome on the artifact's
`validation` document (V18 jsonb, rewritten per attempt: outcome,
attempts counter, timings, and each stage's status/code/reason):

```text
FILE -> FORMAT -> METADATA -> CHECKSUM -> SIGNING -> APPLICATION_MATCH -> VERSION_MATCH -> READY
```

- **FILE** — the stored object exists and its byte count equals the
  registered size (`FILE_MISSING`, `SIZE_MISMATCH`).
- **FORMAT** — the STRUCTURAL container verdict (the S5-03 detection,
  never the file name's claim: iOS parsing success is the IPA verdict,
  the Android document carries `APK`/`AAB`) checked against the
  platform and the uploaded extension, and recorded in the
  first-class `format` column (V18) so distribution-time
  channel-format rules act on a recorded fact — FR-006's note: an AAB
  is Play-only, direct download needs an APK, and an AAB upload
  itself stays valid. A renamed binary (AAB bytes named `.apk`) fails
  the coherence check (`FORMAT_MISMATCH`) WITH the structural truth
  recorded. A container-fault extraction (`NOT_A_ZIP`,
  `ZIP64_UNSUPPORTED`, `MALFORMED_ZIP`, `UNKNOWN_FORMAT`) fails here
  carrying the extraction's code.
- **METADATA** — the extraction outcome must be `SUCCESS`; a
  payload-fault extraction (malformed plist/manifest, unreadable
  entry) fails here (`METADATA_FAILED`) carrying the extraction's own
  code — the join point of the two async tiers.
- **CHECKSUM** — the stored object's SHA-256 digest re-proves the
  registered checksum against the bytes as stored
  (`CHECKSUM_MISMATCH`).
- **SIGNING** — M1 presence and self-consistency only (the issue's
  own scope line; certificate-chain and CMS verification depth is
  M2/M3): an iOS build must carry an internally coherent, unexpired
  embedded provisioning profile (`SIGNING_ABSENT`,
  `SIGNING_INCOHERENT`, `SIGNING_PROFILE_EXPIRED`); an APK must carry
  an APK Signing Scheme v2 or v3 block (`SIGNING_ABSENT`); an AAB
  passes with its JAR-signature presence explicitly deferred (outside
  the S5-03 marker's scope) — the deferred reason is recorded on the
  stage, not silently skipped.
- **APPLICATION_MATCH** — the extracted bundle id / package name
  equals the application registry's registered identifier (S3-01):
  a mismatch fails with the explicit expected/observed error
  (`APPLICATION_MISMATCH`).
- **VERSION_MATCH** — the extracted marketing version equals the
  version record's name, and the extracted build (when a plain
  integer) equals the CI-pinned build when the uploader pinned one.
  M1 rejects mismatches outright — no auto-amend proposal
  (`VERSION_MISMATCH`).

The first FAILED stage stops the run; every later stage records
`SKIPPED`. A FAILED artifact's read answers "which stage, which code,
what reason" through the `validation` block (per-stage
status/code/reason in pipeline order) — the story's actionable-detail
criterion. The two infra conditions are NOT stage failures:
`VALIDATION_QUEUE_FULL` (the bounded pool refused at schedule time)
and `VALIDATION_UNAVAILABLE` (storage unreadable after bounded
retries) close the attempt as `BLOCKED`, the artifact STAYS
`VALIDATING`, and the document is what makes the stall visible rather
than silent.

### The state machine (PRD 18)

Every artifact status change composes through the explicit
`ArtifactStateMachine` transition table — an illegal transition is
rejected at the attempt, never silently applied:

```text
UPLOADING   -> VALIDATING                  (registration: bytes stored, checksum known)
VALIDATING  -> READY | FAILED              (the pipeline; extraction's malformed-binary leg)
FAILED      -> VALIDATING                  (revalidation: the retry entry)
READY       -> DISTRIBUTED | ARCHIVED      (distribution epics own these; reserved here)
DISTRIBUTED -> ARCHIVED
```

PRD 18 has no REVOKED state — revocation is E7's authorization
lifecycle (INV-007: an authorization can be revoked without deleting
the artifact), never an artifact-row state. READY is the state from
which E7's authorization grants will accept an artifact (FR-011);
nothing grants off a FAILED or still-VALIDATING row. All transitions
preserve INV-003 by construction: the machine moves only the status
field and the pipeline's own writes — bytes, checksums, provenance,
and the extraction documents are read, never written.

### Revalidation (the retry scope)

`POST /api/v1/artifacts/{id}/revalidate?version_id=...` (202, the
`app:artifacts:upload` permission): a FAILED artifact moves back to
VALIDATING and the pipeline re-runs; a blocked validation re-drives
where it stands; a blocked EXTRACTION is re-armed (the block note
clears and the parser re-runs — its settled phase schedules
validation again, so the two async tiers re-enter in order).
Deterministic stages reproduce their verdict, but the WORLD may have
changed — a corrected registry identifier, recovered storage — and
the retry is how the corrected fact wins; the `attempts` counter
carries the history. Anything else answers 409
`ARTIFACT_NOT_VALIDATABLE`: PRD 18 has no path from UPLOADING, READY,
DISTRIBUTED, or ARCHIVED back to VALIDATING, and the machine is the
authority.

## Build provenance (S5-04, issue #23)

Every upload surface accepts the five build-provenance claims of FR-005
/ PRD 8.5 — the answer to "what exactly is build 842" during an
incident. The field names are FROZEN: they are the M2 CI-integration
contract the S12-03/S12-04 integrations will send, so the multipart
form names, the JSON member names, and the jsonb document keys never
rename.

| Claim | Multipart form field | JSON member | Shape when present |
|---|---|---|---|
| Git commit | `git_commit` | `gitCommit` | Hex SHA-1 (40) or SHA-256 (64); case-insensitive input, stored lowercase |
| Git branch | `git_branch` | `gitBranch` | 1-255 printable characters |
| Pipeline name | `pipeline` | `pipeline` | 1-255 printable characters |
| CI run URL | `ci_run_url` | `ciRunUrl` | Absolute http/https URL with host, at most 2048 characters |
| Build timestamp | `build_timestamp` | `buildTimestamp` | ISO-8601 instant with offset; canonicalized to UTC |

Capture points: the multipart route binds the form fields; the
resumable route captures the JSON members at **session creation** (the
upload-time moment of that surface) and finalize replays them into the
registered row — finalize takes no provenance of its own, because
hours may separate the two calls and the claims belong to the client
that made them. Storage is a dedicated `provenance` jsonb column on
`artifacts` (V17) — dedicated because the extraction document is
written once by the async parser over a write-once guard, so an
upload-time document cannot ride inside it — written with the
registration insert and never updated (INV-003). The version views'
artifact slots carry the same block.

Semantics:

- **Optional, never fabricated** (the story's own criterion): every
  field is optional and a manual upload may assert none — the view's
  `provenance` block is then null, the stored document absent, and the
  server never guesses a branch, a pipeline, or a build time. A blank
  input is treated as absent, never coerced into a value.
- **Shaped when present**: a malformed claim rejects the whole request
  with one 400 (`INVALID_REQUEST`) whose `errors` member names every
  offending wire field — bad commit hex, non-URL run links, unparseable
  timestamps, overlong labels — in a single round trip, before any
  byte is stored.
- **Trust model**: the values are client-asserted CLAIMS, validated
  for shape only. Nothing in M1 attests or verifies them — the
  authenticated uploader (`uploadedBy`) plus the audit trail is the
  accountability anchor; `ARTIFACT_UPLOADED` carries `gitCommit` and
  `pipeline` in its detail map when asserted. Cryptographic
  provenance/attestation verification is future work (S5-05 adjacent);
  treat the block as "what the uploader said", not "what was proven".

## Configuration

| Variable | Default | Meaning |
|---|---|---|
| `ARTIFACT_STORAGE_BUCKET` | `shipperdog-artifacts-local` | Object-storage bucket |
| `ARTIFACT_STORAGE_EMULATOR_HOST` | (empty) | GCS emulator host (local/CI); empty = production GCS via application default credentials |
| `ARTIFACT_MAX_UPLOAD_BYTES` | `2147483648` | Platform-wide artifact ceiling (413 beyond) |
| `ARTIFACT_RESUMABLE_THRESHOLD_BYTES` | `104857600` | The >100 MB rule: multipart refused above, session path required |
| `ARTIFACT_UPLOAD_SESSION_TTL` | `PT10M` | Upload-session lifetime (finalize 410 past it) |
| `ARTIFACT_EXTRACTION_CONCURRENCY` | `2` | Extraction pool size |
| `ARTIFACT_EXTRACTION_QUEUE_CAPACITY` | `200` | Bounded queue; full = loud `EXTRACTION_QUEUE_FULL`, never unbounded backlog |
| `ARTIFACT_EXTRACTION_MAX_ENTRY_BYTES` | `16777216` | Per-entry read/inflate cap (zip bombs become `ENTRY_TOO_LARGE`) |
| `ARTIFACT_EXTRACTION_STORAGE_RETRIES` | `3` | Attempts against transient storage failures before `EXTRACTION_UNAVAILABLE` |
| `ARTIFACT_VALIDATION_CONCURRENCY` | `2` | Validation pool size (its own pool — the extraction hand-off would self-deadlock a shared queue) |
| `ARTIFACT_VALIDATION_QUEUE_CAPACITY` | `200` | Bounded queue; full = loud `VALIDATION_QUEUE_FULL` block record, never unbounded backlog |
| `ARTIFACT_VALIDATION_STORAGE_RETRIES` | `3` | Attempts against transient storage failures before the attempt records `VALIDATION_UNAVAILABLE` |
| `ARTIFACT_DOWNLOAD_URL_TTL` | `PT15M` | Signed download URL lifetime (S8-04). Bounded twice at startup, fail-fast: at or under the authorization decision-input cache TTL (the S7-04 rule — no URL outlives the revocation backstop) and at or under the hard 15-minute ceiling FR-014 names |
| `ARTIFACT_DOWNLOAD_PROXY_SIGNING_KEY` | `shipperdog-local-download-key` | HMAC-SHA256 key behind the emulator-mode proxy URL (S8-04). Local-only default, the same policy as `AUTH_JWT_SECRET`; production injects a real secret through the environment — and in production the key is never read, because production issuance mints GCS V4 URLs (no emulator host) |

## Observability

- Audit: `ARTIFACT_UPLOAD_SESSION_CREATED`, `ARTIFACT_UPLOADED`
  (S5-01), `ARTIFACT_METADATA_EXTRACTED` (S5-02/S5-03 — success and
  failure legs, the failure carrying `errorCode`; Android success legs
  also carry the detected `format`), `ARTIFACT_VALIDATED`
  (S5-05 — one event per pipeline attempt: `outcome`, `attempts`,
  `durationMs`, the `format` verdict when one exists, and the failing
  `stage`/`errorCode` on a failure), and
  `ARTIFACT_REVALIDATION_REQUESTED` (S5-05 — the retry entry's
  acceptance).
- Metric: `shipperdog_artifact_upload_duration_seconds` (S5-01),
  `shipperdog_artifact_extraction_duration_seconds` (S5-02; tags
  `platform=IOS|ANDROID`, `outcome=success|failed-parse|failed-infra`)
  and `shipperdog_artifact_validation_duration_seconds` (S5-05; tags
  `platform=IOS|ANDROID`, `outcome=success|failed|blocked` — the
  PRD 42 "artifact metadata processing < 30 s" SLO observes the
  extraction meter plus this one end to end; `artifact_validation_duration`
  is the PRD 28 reserved name); since S8-04
  `shipperdog.download.url.generation.duration` (tags
  `outcome=issued|denied` — the PRD 42 "download URL generation <
  500 ms" SLO observes the `issued` leg) and
  `shipperdog.download.count` (tag `platform=IOS|ANDROID`, the PRD 28
  reserved `download_count` wired live) beside the durable
  per-artifact `download_count` column (V23) and the
  `DOWNLOAD_ISSUED` audit trail, which keep the number reconcilable.
  (Known bound, the #38 review's low finding: the column's
  read-modify-write increment can lose counts under concurrent
  issuance of the SAME artifact — the metric and the audit trail are
  the exact record; the atomic per-artifact aggregate is S11-04's
  concern.)

## Duplicates and immutability

Re-uploading identical content registers a NEW artifact (INV-003:
nothing is ever replaced); the response surfaces `duplicateOf`
informationally, capped at 50 rendered hits with `duplicateOfTruncated:
true` signaling the cap. Bytes are stored write-once at keys embedding
a fresh artifact id; there is no overwrite or delete anywhere on the
`ArtifactStore` port.
