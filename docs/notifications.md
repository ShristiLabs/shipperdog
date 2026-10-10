# Notifications and the event bus

The domain-event bus (S12-01, issue #51, FR-023, PRD 51) and the
notification service that rides it: what ships as events, how they
become durable, how an organization routes them to recipients, and
how a webhook receiver verifies what it receives.

## Architecture (ADR-0007)

```
domain module          notification module                    consumers
-------------          -------------------                    ---------
transition ──publish──▶ typed in-process event ──BEFORE_COMMIT──▶ outbox row
(in its tx)                    │                                 │
                               │                        relay (scheduled drain)
                               │                                 │ publish, then mark
                               │                                 ▼
                               │                        DomainEventBus port
                               │                          (in-process today;
                               │                           Kafka/PubSub seam)
                               │                                 ▼
                               │                        notification dispatcher
                               │                          subscriptions → deliveries
```

- **Transactional outbox**: the outbox recorder appends the event row
  in the SAME transaction as the business change (the audit sink's
  S1-06 pattern). Rollback cancels the event; commit guarantees it.
- **At-least-once relay**: the drain publishes each due row and marks
  it RELAYED afterwards — a crash between the two replays the row.
  **Consumers must be idempotent by `event_id`**; this service's
  delivery ledger enforces it with a unique key
  `(event, subscription, recipient)`.
- **The upgrade seam**: the `DomainEventBus` port is the single
  interface a Kafka or Cloud Pub/Sub implementation replaces (PRD
  45). No domain module changes with that migration.
- **Notification failures never break business flows**: the dispatcher
  runs off the request path (inside the relay's drain), and every
  failure is recorded in the delivery ledger and retried — never
  propagated to the transition that produced the event.

## The event contract

Every event carries PRD 51's attribute set: `event_id` (the
idempotency key), `name` (the frozen vocabulary below),
`organization_id` (always), `tenant_id` / `application_id` /
`version_id` / `artifact_id` / `release_id` (when the transition
carries them), `correlation_id` (the originating request's, PRD 51's
trace note — null when the transition happened off-request, e.g. a
scheduler), `occurred_at`, and a payload of identifiers and stable
vocabularies only — never prose, never PII a listener lacks the
authority to read.

### The frozen name vocabulary (wired)

| Name | Published at | Typed event |
|---|---|---|
| `ARTIFACT_UPLOADED` | Artifact registered from an upload session (S5-05) | `ArtifactUploadedEvent` |
| `ARTIFACT_VALIDATED` | Validation pipeline settled PASSED | `ArtifactValidatedEvent` |
| `ARTIFACT_FAILED` | Validation pipeline settled FAILED (FR-023) | `ArtifactValidatedEvent` |
| `TESTER_DOWNLOADED` | Download URL issued to an allowed caller (S8-04) | `ArtifactDownloadIssuedEvent` |
| `RELEASE_CREATED` | DRAFT release created with its target set (S9-01) | `ReleaseCreatedEvent` |
| `RELEASE_TRANSITIONED` | Any release-state crossing without its own name (S9-02) | `ReleaseTransitionedEvent` |
| `APPROVAL_REQUESTED` | One approval step instantiated (S10-01) | `ApprovalRequestedEvent` |
| `RELEASE_APPROVED` | The APPROVED crossing — the completed chain (FR-023 "Approval completed") | `ReleaseTransitionedEvent` |
| `RELEASE_PROMOTED` | One committed ring-promotion hop (S10-05) | `ReleasePromotedEvent` |
| `TESTER_INVITED` | Tester invited or re-invited (S6-01) | `TesterInvitedEvent` |
| `FEEDBACK_RECEIVED` | One tester report landed (S11-01) | `FeedbackReceivedEvent` |
| `FEEDBACK_MARKED_CRITICAL` | Report classified CRITICAL (S11-02) | `FeedbackMarkedCriticalEvent` |

Two deliberate suppressions: a validation outcome of `BLOCKED` (the
operational queue-refusal skip) publishes nothing, and the release
state machine's PROMOTED crossing publishes nothing (the promotion
hop's own `RELEASE_PROMOTED` carries that moment — one event, not
two).

### Reserved (not yet wired)

`QUALITY_GATE_STARTED`, `QUALITY_GATE_PASSED`,
`DISTRIBUTION_STARTED`, `DISTRIBUTION_COMPLETED`,
`DISTRIBUTION_FAILED`, `TESTER_NOTIFIED`, `TESTER_INSTALLED`,
`PROMOTION_REQUESTED`, `NEW_VERSION_AVAILABLE` — the transitions they
describe do not exist yet (ADR-0007 names the owning stories). Names
are public contract: they arrive with the story that owns the
transition, and nothing already published is ever renamed.

## Subscriptions (FR-023)

An organization's routing table: event names x channel x recipients.
`org:notifications:read` to see it, `org:notifications:manage`
(ORG_ADMIN, APP_OWNER, RELEASE_MANAGER) to edit; every write audited
(`NOTIFICATION_SUBSCRIPTION_CREATED/UPDATED/DELETED`) — the webhook
signing secret never appears in a view or an audit line (write-only:
create or PATCH supply it; a PATCH with a new secret rotates it).

```
POST /api/v1/organizations/{orgId}/notification-subscriptions
{ "eventTypes": ["RELEASE_PROMOTED", "FEEDBACK_MARKED_CRITICAL"],
  "channel": "IN_APP" | "EMAIL" | "WEBHOOK",
  "recipientRoles": ["QA_ENGINEER"],          // IN_APP, EMAIL
  "recipientUserIds": ["<uuid>"],             // IN_APP, EMAIL
  "webhookUrl": "https://...",                // WEBHOOK
  "webhookSecret": ">=16 chars" }             // WEBHOOK
```

Validation is 422 `NOTIFICATION_SUBSCRIPTION_INVALID` with one
`errors` entry per offense: unknown event names, unknown roles, users
the directory does not know, IN_APP/EMAIL without at least one
recipient, WEBHOOK without a well-formed http(s) URL and a
16+-character secret. PATCH keeps absent fields; the channel never
moves; `enabled: false` parks the subscription. A concurrent edit of
the same subscription refuses with 409
`NOTIFICATION_SUBSCRIPTION_CONFLICT` (the row's optimistic `version`
stamp): reload and retry — a stale writer never silently resurrects
a rotated secret or a replaced URL. Routes: `GET` list
and item, `PATCH`, `DELETE` under
`/api/v1/organizations/{orgId}/notification-subscriptions[/{id}]`,
limit/cursor paginated like every platform collection.

Channels:

- **IN_APP** — the delivery row IS the inbox item, written at
  dispatch. Read your own through the self-scoped
  `GET /api/v1/notification-inbox` (the `/auth/me` shape: no
  resource id, the recipient filter is the permission).
- **EMAIL** — through the `EmailSender` seam, today the WARN-log
  stub; SMTP transport is S12-02.
- **WEBHOOK** — one signed POST per attempt (below). Slack and Teams
  formatters (FR-023's other channels) are S12-02 on top of this
  machinery.

## The delivery ledger and retries

`GET /api/v1/organizations/{orgId}/notification-deliveries` (optionally
`?subscriptionId=` and `?status=SCHEDULED|DELIVERED|FAILED`) — every
attempt, newest first: channel, recipient, status, attempts, the last
error, delivery stamps. Failed deliveries retry with exponential
backoff (`NOTIFICATIONS_DELIVERY_BACKOFF` doubling per attempt) to the
`NOTIFICATIONS_DELIVERY_MAX_ATTEMPTS` ceiling (default 5), then stay
FAILED with the final error recorded — visible, never silent, and
never able to affect the business flow that produced the event.

## Webhook delivery

### The target policy

A webhook URL is an address this service POSTs to from inside its
own network, so both writes that can set it (create and edit) run it
through the SSRF policy: http(s) scheme only, no embedded
credentials, at most 2048 characters, and a **publicly routable
host** — `localhost`/`*.internal` names and any host that resolves
into loopback (`127.0.0.0/8`, `::1`), link-local
(`169.254.0.0/16`, `fe80::/10` — the cloud metadata endpoints live
here), private (`10/8`, `172.16/12`, `192.168/16`), or IPv6
unique-local (`fc00::/7`) address space refuse with the
field-addressed 422. A host nothing resolves refuses too.
`shipperdog.notifications.webhook-allow-private-addresses` (default
`false`) skips the host and DNS checks for local dev/test receivers —
production keeps the default.

Two known horizons, deliberate for V1.5: the target is vetted at
validation time, not again at POST time, so a host whose DNS later
re-points inward (rebinding) is behind this policy — re-checking at
delivery is the broker-era follow-up; and the delivery client never
follows redirects (a receiver's 3xx is one failed attempt, never a
bounce to an unvetted host).

### The signed POST

Each attempt POSTs the canonical event JSON (snake_case members,
fixed key order):

```json
{
  "event_id": "…uuid…", "name": "RELEASE_PROMOTED",
  "organization_id": "…", "tenant_id": null, "application_id": "…",
  "version_id": "…", "artifact_id": null, "release_id": "…",
  "correlation_id": "…or null…", "occurred_at": "2026-10-04T12:00:00Z",
  "payload": { "…": "…" }
}
```

Headers: `X-Shipperdog-Event` (the name), `X-Shipperdog-Timestamp`
(epoch seconds), and

```
X-Shipperdog-Signature: sha256=hex(HMAC-SHA256(secret, timestamp + "." + body))
```

**Verify like this**: read the timestamp and signature headers, HMAC
the exact received body bytes with your secret over
`timestamp + "." + body`, hex-encode with the `sha256=` prefix, and
compare constant-time. Reject stale timestamps (your window) and
deduplicate on `event_id` — the relay is at-least-once, so a
deliberate replay of the same `event_id` is the same event. Any 2xx
is success; anything else, a connect failure, or a timeout is one
failed attempt for the retry ledger.

### The signing secret's posture

The secret is write-only in life (create or PATCH supply it; no
view, log line, or audit entry returns it — a PATCH with a new value
rotates it) and stored in a dedicated column in cleartext: at-rest
protection is the database's job (disk/volume encryption, the
deployment runbook's posture), matching the credential-reference
convention the environment module set. Rotation takes effect on the
next attempt — in-flight retries sign with the secret they re-read
at send time, so a receiver mid-rotation may see one attempt under
the old secret before the new one lands.

## Configuration

| Property | Default | Meaning |
|---|---|---|
| `shipperdog.notifications.relay-enabled` | true | Run the scheduled drains (tests/manual drains disable it) |
| `shipperdog.notifications.relay-fixed-delay` | PT2S | Drain cadence |
| `shipperdog.notifications.relay-batch-size` | 100 | Rows per drain pass |
| `shipperdog.notifications.relay-max-attempts` | 5 | Relay attempts before a row is terminally FAILED |
| `shipperdog.notifications.relay-backoff` | PT2S | Exponential base between relay attempts |
| `shipperdog.notifications.delivery-max-attempts` | 5 | Delivery attempts before the ledger row is FAILED |
| `shipperdog.notifications.delivery-backoff` | PT2S | Exponential base between delivery retries |
| `shipperdog.notifications.webhook-timeout` | PT5S | Webhook POST connect/read budget |
| `shipperdog.notifications.webhook-allow-private-addresses` | false | Skip the webhook target policy's host/DNS checks (local test/dev receivers only) |

See [ADR-0007](adr/ADR-0007-event-bus-transport.md) for the transport
decision and the broker migration path, [authorization.md](authorization.md)
for the permission matrix, and [database-migrations.md](database-migrations.md)
for V39 (the outbox, subscription, and delivery tables).
