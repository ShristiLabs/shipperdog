# SIEM export & audit streaming (S14-05)

The compliance feed of PRD 27/38 and FR-024: the immutable audit
trail streamed to a customer SIEM — in real time over HTTPS event
collectors, or as periodic bucket batches with signed manifests —
so security operations sees delivery-plane events alongside
everything else. Implemented by the notification module's
audit-stream family on top of the S12-01 delivery pipeline (the
issue's technical note: no second notifier), with the audit module
owning the store ([audit](audit.md)).

## Architecture

Two transport legs, one configuration surface:

- **HTTPS (real time)** — every published audit event of an
  organization with at least one enabled export bridges onto the
  transactional outbox (`DomainEventName.AUDIT_EVENT`, the outbox
  recorder's audit listener, written in the same transaction the
  audit row commits under); the standing relay drains it to the bus;
  the audit-stream dispatcher fans each event out to the
  organization's enabled HTTPS exports whose event-type filter
  selects it, as one signed POST per attempt through the same wire
  discipline the webhook channel rides. Delivery state is the
  `audit_stream_deliveries` ledger: one row per (event, export), the
  at-least-once idempotency collapse, retry with exponential backoff
  to the attempt ceiling, then the terminal FAILED row — the visible,
  replayable dead-letter.
- **BUCKET (periodic)** — the batcher's scheduled pass advances each
  enabled bucket export's keyset watermark over the append-only
  `audit_events` store (the audit module's `AuditEventFeed` port),
  bounded rows per pass, writing one JSONL batch plus its signed
  manifest per batch sequence, and committing the batch-history row
  with the watermark. Nothing is consumed until the batch commits;
  a pass that fails leaves the watermark standing and the next
  cadence retries. The write is idempotent by construction: the
  window is deterministic in the standing watermark, so a crash
  between the object write and the commit re-writes identical bytes.

The bridge is org-gated: organizations without enabled exports write
no outbox rows at all. Audit events without an organization context
do not bridge — the export is org-scoped routing.

## The exported event schema (versioned, public API)

The envelope is a versioned public contract; members are append-only
within a version's lifetime and breaking changes ride the S1-08
contract gate. Current version: `1.0`. The member set and its order
are frozen (pinned by `SiemEventFormatsTest`):

```json
{
  "event_id": "9b2f…",
  "schema_version": "1.0",
  "event_type": "ORGANIZATION_UPDATED",
  "occurred_at": "2026-10-12T04:25:43.793454Z",
  "organization_id": "6d0abf41-…",
  "tenant_id": null,
  "user_id": "682e7753-…",
  "application_id": null,
  "version_id": null,
  "artifact_id": null,
  "result": "SUCCESS",
  "reason": null,
  "ip_address": "127.0.0.1",
  "correlation_id": "979e79f9-…",
  "detail": { "action": "created" },
  "before": "{\"name\":{\"before\":\"a\"}}",
  "after": "{\"name\":{\"after\":\"b\"}}"
}
```

- `event_id` — the delivery-scoped dedup key: the outbox row id on
  the HTTPS leg, the audit row id on the bucket leg. Each
  destination's stream is idempotent under its own ids (at-least-once
  means receivers MAY see the same event twice after a retry;
  deduplicate on `event_id`); cross-destination correlation rides
  `correlation_id`.
- `detail`, `before`, `after` — exactly what the trail holds: every
  event was enriched and redacted by the publisher binding before
  persistence ([audit](audit.md)); the export re-renders, it never
  re-widens. `before`/`after` ride as raw JSON strings (the audit
  query API's own rendering — receivers parse).
- `result` — `SUCCESS` or `FAILURE`, the trail's success flag.

### Formats

The HTTPS export's `format` selects the wrapper:

- `SHIPPERDOG_JSON` — the envelope object itself, fixed key order,
  so the bytes a receiver re-reads for verification are the bytes
  that were signed.
- `SPLUNK_HEC` — the Splunk HTTP Event Collector's JSON envelope
  (the optional vendor helper): the audit envelope rides the `event`
  member with `time` (epoch seconds), `host` (the organization id),
  and the stable `sourcetype` `shipperdog:audit:v1`. The HEC token
  travels the auth header, never the body.

CEF/LEEF are documented future helpers (vendor-specific integrations
beyond the generic endpoints are the story's declared out-of-scope).

## Verification (the receiver's recipe)

Every HTTPS delivery carries the webhook channel's exact signature
discipline (docs/notifications.md):

```
X-Shipperdog-Event:     AUDIT_EVENT
X-Shipperdog-Timestamp: <epoch seconds>
X-Shipperdog-Signature: sha256=HMAC_SHA256(secret, timestamp + "." + body)
```

Verify with constant-time comparison over the raw body bytes. An
optional collector auth header (`authHeaderName` + a secret-manager
reference) rides the POST — it authenticates the sender to the
collector; the HMAC authenticates ShipperDog to the receiver.

### The bucket manifest

Each batch `manifest-<org>-<seq>.json` carries the completeness
facts — `schema_version`, `export_id`, `organization_id`,
`batch_seq`, `watermark_from`, `watermark_to`, `event_count`,
`batch_sha256` (the JSONL batch's SHA-256), `batch_object`,
`created_at`, `signature_algorithm` — and a `signature`:
`sha256=`-prefixed hex HMAC-SHA256 over the canonical serialization
of the manifest WITHOUT the `signature` member (fixed key order;
drop the member, re-serialize in order, verify). Verifiable
completeness: the cumulative `event_count` of the batch history
against the audit table's count for the same window.

## Configuration (org admins only)

`org:audit-stream:read` / `org:audit-stream:manage` — held by the
organization administrator ALONE (the story's AC5: the surface ships
security-relevant material off-platform; no other role holds either
permission). Routes under
`/api/v1/organizations/{organizationId}/audit-stream-exports`:
the CRUD, `test` (one synthetic envelope through the real pipeline,
answered synchronously), `deliveries` (the ledger with the FAILED
dead-letter filter), `replay` (every terminally failed delivery
returns to the retry schedule with a fresh attempt budget), and
`batches` (the bucket history).

Secrets are secret-manager REFERENCES only (AC5): `signingSecretRef`
(the HMAC key for deliveries and manifests) and the optional
`authSecretRef` (the collector header's value) resolve at delivery
time through the environment module's `SecretReferenceResolver` port
(the S9-04 seam; production wiring is the distribution module's
selected store). A reference that does not resolve is an honest
failed attempt — "the signing secret reference did not resolve" —
never a dropped event. On the audit trail the key-based scrubber
renders the reference member itself as `[REDACTED]` (any key
carrying "secret"): neither the material nor the reference rides an
audit line; the configuration view serves the reference.

The event-type filter selects from the frozen `AuditEventType`
vocabulary (docs/audit.md); an empty filter streams everything —
including the ABAC denial and evaluation-fault families the #70
follow-ups named natural SIEM candidates (`ABAC_POLICY_DENIED`,
`ABAC_EVALUATION_FAULT`, and every `AUTHORIZATION_DECISION`).

## Bucket destinations

`gs://bucket/prefix` URIs (the platform's object storage, PRD 24).
Production posture: the customer grants the platform's writer
identity (application default credentials) write access to their
bucket; the `s3://` connector is the documented follow-up on the
same `AuditStreamBucketStore` port. The initial watermark defaults
to now (forward-only); an explicit ISO-8601 `initialWatermark`
(e.g. `1970-01-01T00:00:00Z`) backfills the whole trail in bounded
passes.

## Retention

Log retention is org data policy, deliberately out of this story's
UI (the issue's out-of-scope): the trail keeps everything; what a
destination retains after delivery is the customer's SIEM's own
policy.
