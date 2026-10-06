# Artifacts

The artifact surface of ShipperDog: upload and object storage (S5-01,
issue #20), extracted metadata — iOS (S5-02, issue #21) and Android
(S5-03, issue #22) — and build provenance capture (S5-04, issue #23).
An artifact is a
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
| `GET /api/v1/artifacts/{artifactId}?version_id={versionId}` | `app:artifacts:read` | The artifact read — where extraction outcomes land (S5-02) and the provenance block answers (S5-04) |

The surface is body-addressed: every route carries the version id in
the form/body/query (never the path), the permission evaluator
resolves it version -> application -> owning tenant/organization, and
every denial conceals as the uniform 404 of that target (a foreign
version answers `VERSION_NOT_FOUND`; an unknown artifact under your
own version answers `ARTIFACT_NOT_FOUND`).

## Artifact states and extraction (S5-02, issue #21)

A registered artifact lands as `VALIDATING`. Its bytes are then parsed
**asynchronously post-upload** on a bounded executor — the upload
response's `extraction.status` is always `PENDING`; read the artifact
back (`GET`) to watch it settle:

| `extraction.status` | Meaning | Artifact status |
|---|---|---|
| `PENDING` | Parsing has not produced an outcome yet | `VALIDATING` |
| `SUCCESS` | Fields extracted and stored | `VALIDATING` (S5-05 owns later transitions) |
| `FAILED` | The binary is malformed; `errorCode`/`errorMessage` are actionable | `FAILED` |
| `BLOCKED` | Extraction could not run (queue full, storage unreadable after retries); the error document says why | `VALIDATING` |

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

## Observability

- Audit: `ARTIFACT_UPLOAD_SESSION_CREATED`, `ARTIFACT_UPLOADED`
  (S5-01), `ARTIFACT_METADATA_EXTRACTED` (S5-02/S5-03 — success and
  failure legs, the failure carrying `errorCode`; Android success legs
  also carry the detected `format`).
- Metric: `shipperdog_artifact_upload_duration_seconds` (S5-01) and
  `shipperdog_artifact_extraction_duration_seconds` (S5-02; tags
  `platform=IOS|ANDROID`, `outcome=success|failed-parse|failed-infra`
  — the PRD 42 "artifact metadata processing" SLO observes this
  meter).

## Duplicates and immutability

Re-uploading identical content registers a NEW artifact (INV-003:
nothing is ever replaced); the response surfaces `duplicateOf`
informationally, capped at 50 rendered hits with `duplicateOfTruncated:
true` signaling the cap. Bytes are stored write-once at keys embedding
a fresh artifact id; there is no overwrite or delete anywhere on the
`ArtifactStore` port.
