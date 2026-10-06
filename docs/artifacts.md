# Artifacts

The artifact surface of ShipperDog: upload and object storage (S5-01,
issue #20) and extracted metadata (S5-02, issue #21). An artifact is a
concrete immutable binary (PRD 8.5): bytes in object storage under the
PRD 24 layout
`/{organizationId}/{tenantId}/{applicationId}/{versionId}/{artifactId}/artifact`,
metadata only in PostgreSQL — never bytes (FR-007, ADR-0003).

## Routes

| Route | Permission | Notes |
|---|---|---|
| `POST /api/v1/artifacts` | `app:artifacts:upload` (DEVELOPER) | Multipart upload at or below the resumable threshold; honors `Idempotency-Key` |
| `POST /api/v1/artifacts/upload-sessions` | `app:artifacts:upload` | Direct-to-storage session for large binaries (>100 MB rule); honors `Idempotency-Key` |
| `POST /api/v1/artifacts/{artifactId}/finalize` | `app:artifacts:upload` | Registers the artifact after direct bytes; replays the registered state on retry |
| `GET /api/v1/artifacts/{artifactId}?version_id={versionId}` | `app:artifacts:read` | The artifact read — where extraction outcomes land (S5-02) |

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
`CFBundleVersion` that is not a plain integer keeps the column null
and stores the raw string in the document). The full extraction
document (`extracted_metadata` jsonb) additionally carries the display
name, signing information lifted from the embedded provisioning
profile (profile name/UUID, team identifiers, expiration, entitlements
— fields stored only; verification is S5-05), parser attribution, and
timings.

### Extraction failure codes (stored, not HTTP)

`NOT_A_ZIP`, `ZIP64_UNSUPPORTED`, `MALFORMED_ZIP`,
`MISSING_INFO_PLIST`, `MALFORMED_PLIST`, `ENTRY_TOO_LARGE`,
`ENTRY_ENCRYPTED`, `UNSUPPORTED_COMPRESSION` — the binary's own
faults, recorded in `extraction_error` with an operator-readable
message. `EXTRACTION_QUEUE_FULL` and `EXTRACTION_UNAVAILABLE` are
infrastructure blocks (the artifact stays `VALIDATING`), likewise
recorded so a missed extraction is visible rather than silent.

### Parser

iOS (IPA): the ZIP central directory is walked with **ranged object
reads** — a 500 MB IPA costs kilobytes (the `< 30 s` SLO of PRD 42 is
met by not reading the 500 MB). `Info.plist` (binary or XML) parses
via [dd-plist](https://github.com/3to4nan/dd-plist) (pinned in
`backend/pom.xml`); the embedded provisioning profile's XML payload is
marker-extracted from the CMS container — full certificate chain
validation is S5-05. The parser sits behind the
`ArtifactMetadataParser` port; the Android sibling (S5-03) is a new
implementation of the same port.

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
  (S5-01), `ARTIFACT_METADATA_EXTRACTED` (S5-02 — success and failure
  legs, the failure carrying `errorCode`).
- Metric: `shipperdog_artifact_upload_duration_seconds` (S5-01) and
  `shipperdog_artifact_extraction_duration_seconds` (S5-02; tags
  `platform`, `outcome=success|failed-parse|failed-infra` — the PRD
  42 "artifact metadata processing" SLO observes this meter).

## Duplicates and immutability

Re-uploading identical content registers a NEW artifact (INV-003:
nothing is ever replaced); the response surfaces `duplicateOf`
informationally, capped at 50 rendered hits with `duplicateOfTruncated:
true` signaling the cap. Bytes are stored write-once at keys embedding
a fresh artifact id; there is no overwrite or delete anywhere on the
`ArtifactStore` port.
