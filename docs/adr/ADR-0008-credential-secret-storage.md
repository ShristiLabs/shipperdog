# ADR-0008: Credential material lives behind a secret-store port — GCP Secret Manager in production, an AES-256-GCM envelope store as the self-hostable default

Date: 2026-10-04
Status: Accepted (issue #61, S13-01)

## Context

PRD 25 is explicit: store accounts (Apple, Google Play) are configured
at the organization level, inherited downward with overrides, and the
platform stores references, not credentials — "GCP Secret Manager plus
Cloud KMS". The environment module's `SecretReferenceResolver` port
(issue #58) shipped with an honest unbacked stub and reserved its
production wiring for exactly this story ("the secrets story this one
deliberately excludes").

The tension S13-01 must resolve: the production posture is a Google
Cloud service, but ShipperDog is an OSS repository whose compose stack
and CI (Testcontainers) run without any Google side — and the
credential-binding surface must be demonstrable end-to-end (bind,
resolve, verify) everywhere the product runs, not only where a GCP
project exists. The #51/#52 recorded posture (the `webhook_secret`
precedent: credentials are runtime configuration, never committed) and
the repo's public posture rule out any committed key material.

The options considered:

1. **GCP Secret Manager only.** The PRD's named posture; nothing else
   to build or vouch for. But the OSS deployment cannot run it (no
   project in CI or compose), leaving the binding surface a stub
   everywhere self-hosted and untestable end-to-end.
2. **Application-level encryption into a credential column.** Encrypt
   material and store it beside the reference. Rejected outright: AC3
   pins the credential row to `credential_id / provider / metadata /
   secret_reference` — the row is a reference holder by schema, and a
   ciphertext column there invites key-management drift into the
   credential surface itself.
3. **A secret-store port with two implementations** — GCP Secret
   Manager for production, and an envelope-encryption store (AES-256-
   GCM, one random data key per secret, the data key wrapped by a
   KEK from the deployment environment) writing ciphertext to its own
   `managed_secrets` table as the self-hostable default.

## Decision

**Option 3: one `ChannelSecretStore` port, two implementations,
selected by `shipperdog.secrets.provider`:**

1. **`gcp-secret-manager`** is the production posture (PRD 25). The
   store speaks the Secret Manager v1 REST surface through the
   platform's RestClient convention with application-default
   credentials (the same ADC the GCS artifact store rides) — no new
   client library enters the tree. References are
   `gcp-secret-manager://projects/<p>/secrets/<s>`; resolution reads
   the latest version (rotation is a new version under the same
   reference, the binding slot doctrine). At-rest encryption is
   Cloud KMS's side of the contract.
2. **`envelope`** (the default) is the self-hostable posture:
   AES-256-GCM, a fresh 32-byte data key per secret, the data key
   wrapped by the KEK (`SECRETS_ENVELOPE_KEK`, Base64 of 32 bytes,
   from the deployment environment — never committed), and the
   reference plus key id bound as additional authenticated data so
   ciphertext and its address cannot be swapped. Plaintext exists
   only in JVM memory between the binding write's decrypt and the
   adapter call; `managed_secrets` holds ciphertext and the wrapped
   key only (the schema test pins the column set); `SecretMaterial`
   redacts `toString()` by construction.
3. **A blank KEK mints an EPHEMERAL process-local key with a loud
   startup warning** — acceptable for a local stack whose secrets are
   re-bindable, never for production (a restart stops every reference
   resolving; the verify probe answers `resolved: false`, the honest
   failure; nothing is corrupted). A malformed or wrong-length
   configured KEK refuses startup.
4. **References are namespaced per store** (`local-envelope://`,
   `gcp-secret-manager://`); a store resolves only its own namespace
   and answers empty for every other shape — unknown, deleted,
   rotated-away, and access-denied are indistinguishable by design
   (the #58 resolution doctrine).
5. **The port backs the platform-wide `SecretReferenceResolver`**:
   the environment module's verify probe resolves real references for
   the first time; the honest unbacked stub remains only as the
   `@ConditionalOnMissingBean` fallback for contexts that register no
   store.

## Consequences

- The database is never trusted with plaintext in ANY posture: the
  credential rows are reference holders (AC3's schema test), and the
  envelope store's own table is ciphertext by construction. KEK
  rotation for the envelope posture is generational: a new KEK stops
  old references resolving (empty, never an error), and re-binding
  under the new generation is the documented rotation.
- The GCP store is wire-tested against a mock server (the create/
  addVersion/access shapes and the 409 rotation path); its ADC leg is
  the production deployment's concern, exercised by the same
  application-default-credentials setup the GCS store documents.
- Resolution is contained: a store that faults while resolving (the
  GCP posture's token source breaking at call time) answers "does not
  resolve" — logged at WARN — and never throws inside the dispatch
  crossing's transaction, so a store outage can fail records but never
  a release's ACTIVE crossing.
- The binding surface accepts material exactly once and forwards it
  to the selected store; every answer, audit event, and log line
  carries only the reference (the integration suite pins that the
  material appears in none of them). Rotation in place discards the
  replaced material's stored copy through the port's `delete`
  (namespace-scoped; the envelope store removes its ciphertext row,
  the GCP posture has nothing to delete).
- Self-hosted deployments that want durable secrets set
  `SECRETS_ENVELOPE_KEK` (or run their own GCP project); the
  deployment documentation carries the wiring.
