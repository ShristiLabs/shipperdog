# ADR-0003: Artifact object storage behind the ArtifactStore port

Date: 2026-10-06
Status: Accepted (issue #20, S5-01)

## Context

FR-004/FR-007 and PRD 24 require uploaded artifact binaries
(IPA/APK/AAB) to live in object storage under the layout
`/{organizationId}/{tenantId}/{applicationId}/{versionId}/{artifactId}/artifact`,
with PostgreSQL keeping metadata only — never bytes. PRD 45 names
Google Cloud Storage as the production target, and docker compose has
run `fsouza/fake-gcs-server` (the community GCS emulator, MIT-licensed)
since issue #1, unused until now. Issue #20 additionally demands a
direct-to-storage path for uploads above 100 MB with a short-lived
upload session (AC5), streaming uploads that never buffer a whole
binary in memory (the #8 buffering-ceiling debt is explicitly not to
be repeated), and INV-003: no code path may ever overwrite an
artifact's bytes.

The choice of client and emulator strategy determines every later
artifact story (S5-02/03 read metadata back, S8-04 serves downloads,
S5-05 validates), so it is decided once, here.

## Options considered

1. **google-cloud-storage (OSS, Apache-2.0) against the compose GCS
   emulator, behind a small module port.** One client, one wire
   protocol, two targets: the emulator when a host is configured
   (local/CI, no credentials), application default credentials against
   real GCS in production.
2. **MinIO or another S3-compatible store with the AWS SDK.** A second
   storage stack in compose (the GCS emulator already runs), a second
   protocol in production (PRD commits to GCS semantics), and a
   divergence between local and production behavior — exactly what a
   port exists to avoid.
3. **Store binaries in PostgreSQL (bytea).** Explicitly forbidden
   (FR-007, PRD 24, the "never store large binaries in PostgreSQL"
   rule); rejected without further consideration.

## Decision

Artifacts are stored through **`ArtifactStore`**, a small port owned
by the artifact module (`put`, `createResumableSession`, `hash`; no
overwrite, no delete — INV-003 made structural), implemented by
**`GcsArtifactStore`** on the **google-cloud-storage Java client**
(pinned in `backend/pom.xml`; version-managed like every other
dependency). The client is built once per process: when
`ARTIFACT_STORAGE_EMULATOR_HOST` is set, the GCS client points at the
emulator without credentials; when blank, it uses application default
credentials against real GCS (ADR-compatible with the PRD 45 stack;
the production bucket may be pre-created or auto-created at startup
per configuration).

Streaming discipline: multipart file parts are disk-buffered by the
servlet layer (multipart caps raised to sit above the artifact
ceiling), the service wraps the stream in a SHA-256 digest, and the
store writes through a chunked `WriteChannel` — no method on the port
ever holds a whole artifact in memory, and the checksum is computed
during the stream, before the metadata row exists (the V1
`artifacts.sha256 NOT NULL` invariant holds by construction).

The >100 MB rule (AC5) is **enforced, not advisory**: multipart
uploads above the configurable threshold answer 413 and are carried by
the resumable path instead — `POST /api/v1/artifacts/upload-sessions`
runs the same guards, asks GCS for a resumable session on the
pre-allocated object key, and hands the client the session URL; the
client streams bytes direct to storage; `POST /api/v1/artifacts/
{artifactId}/finalize` streams the stored object back through a SHA-256
digest (the server never saw the client's bytes), registers the
metadata row, and deletes the session. The session TTL is enforced at
finalize (410 past expiry).

The resumable-session initiation is the one request issued with
`java.net.http.HttpClient` rather than the storage client: the GCS
JSON API's documented `POST .../o?uploadType=resumable` whose
`Location` header is the session URL. The official client offers no
public way to create a session and hand its URL to someone else (its
own writer consumes sessions internally), and V4 signed URLs are
emulator-hostile. The call is authenticated with an application
default token in production and is unauthenticated against the
emulator — identical request, identical semantics, verified against
fsouza/fake-gcs-server 1.56.1 in the integration suite.

Object keys derive only from server-known ids (the artifact id is
allocated before any byte moves), with `_shared` in the tenant slot
for organization-owned versions; addressing is never authorization
(PRD 24's own note — the S8-04 signed download URLs carry that
decision). Writes are preconditioned write-once (`doesNotExist`),
belt-and-braces under keys that cannot collide.

## Amendment: the read side (issue #21, S5-02)

The port ADR-0003 left "S5-02/03 read metadata back" as a promise now
kept: the port gains `sizeOf` and a ranged `read(key, offset, length)`
— no whole-object stream, no download URL. The shape follows the
consumer: IPA (and APK/AAB from S5-03) are ZIPs, and a ZIP's index is
its central directory at the END of the object, so the parser needs
backward seeks and kilobyte slices, not a byte stream. `sizeOf` is a
metadata call (no content read); `read` is a seek-bounded channel
read. The no-whole-binary discipline of the write side now holds on
the read side structurally, and INV-003 is untouched (the additions
read only). The download tier (S8-04) will add its own surface when
that story arrives — serving bytes to testers is a different concern
from parsing them, and merging the two would couple an SLO-sensitive
parser path to a CDN-shaped delivery path.

## Amendment: session-URL reachability (issue #25, S5-06)

The original consequence line assumed the emulator's `-external-url`
makes its resumable-session URLs operator-reachable. It does not:
fake-gcs-server (verified at v1.56.1, `internal/urlhelper.GetBaseURL`)
builds the resumable `Location` header from the REQUEST's Host header,
and `-external-url` never applies to it. The API dialed the emulator
in-network (`gcs-emulator:4443`), so every granted `uploadUrl` carried
a compose-internal hostname no host-side client could resolve — a
latent gap from S5-01 that surfaced with the first such client, the
S5-06 CLI.

Fix (compose-only, no backend change): a `gcs-forward` socat sidecar
(`alpine/socat:1.8.1.3`) shares the API's network namespace and
bridges `localhost:4443` to the emulator service, and the API now
points `ARTIFACT_STORAGE_EMULATOR_HOST` at `http://localhost:4443`.
The Host header on the API's session-initiation request becomes
`localhost:4443`, so the granted URL carries the operator-reachable
published port. Verified end-to-end by a 101 MiB CLI upload through
create-session, direct PUT, and finalize. Operational caveat: the
forwarder shares the API's netns, so recreate both (`docker compose
up -d`, or `up -d api gcs-forward`) rather than the API alone.

## Consequences

- One dependency (`com.google.cloud:google-cloud-storage`) joins the
  build; it is flagged in the PR body and version-pinned.
- Local and CI run the same GCS semantics as production; the compose
  emulator's session URLs are reachable by the operator's machine via
  the localhost forwarder described in the amendment below (the
  original `-external-url` assumption is corrected there).
- The finalize replay (session gone, artifact present) makes the
  crash window between insert and session delete self-healing; an
  abandoned session leaves an unnamed, immutable object — cleanup is a
  follow-up sweeper, not this story.
- Artifacts join the tenant-isolation seam with the full
  owning-dimension snapshot (ADR-0002 ninth amendment, V15): the
  artifact routes address them directly and the same-content detection
  spans the application's versions — both break the eighth amendment's
  plain-child invariant, by its own rule requiring the amendment.

## See also

- ADR-0002 (the seam; ninth amendment recorded there)
- `docs/database-migrations.md` (V15)
- Issue #20 (S5-01), PRD 8.5/18/24, FR-004/FR-006/FR-007, INV-003
