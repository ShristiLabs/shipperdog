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
| `NOTIFICATION_TEST_SENT` | A subscription's test message (S12-02) | the test action itself — no domain transition publishes it |
| `AUDIT_EVENT` | One immutable audit event bridged for the SIEM export (S14-05) | the outbox recorder's audit listener — every published `AuditEvent` of an organization holding an enabled export, org-gated ([siem](siem.md)) |
| `OTA_BUNDLE_SIGNED` | One OTA bundle finalized and signed under the application's active Ed25519 key (S16-03) | `OtaBundleSignedEvent` |

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
  "channel": "IN_APP" | "EMAIL" | "WEBHOOK" | "SLACK" | "TEAMS",
  "recipientRoles": ["QA_ENGINEER"],          // IN_APP, EMAIL
  "recipientUserIds": ["<uuid>"],             // IN_APP, EMAIL
  "webhookUrl": "https://...",                // WEBHOOK, SLACK, TEAMS
  "webhookSecret": ">=16 chars" }             // WEBHOOK, SLACK, TEAMS
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
  stub; SMTP transport is a standing follow-up.
- **WEBHOOK** — one signed POST per attempt (below), body = the
  canonical event JSON.
- **SLACK / TEAMS** (S12-02, FR-023's chat channels) — the same
  signed POST machinery, same URL + signing secret + target policy,
  with a provider-rendered message body (the chat cards below).

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

## The chat channels: Slack and Teams (S12-02)

The chat channels are subscription values, not a second model: a
SLACK or TEAMS subscription carries exactly what a WEBHOOK one does —
a `webhookUrl` (Slack incoming-webhook, Teams workflow, or connector
URL) and the write-only signing `webhookSecret` — validated by the
same rules (the target policy included: `hooks.slack.com`,
`*.logic.azure.com` workflow hosts, and `outlook.office.com` are
publicly routable and pass unmodified) and delivered by the same
signed POST with the same retry/backoff ledger. The recipient keys
are channel-prefixed (`slack:<url>`, `teams:<url>`) so the idempotency
grain and the delivery log read cleanly per channel.

Only the body is provider-shaped, rendered from the per-event
templates below.

### The message templates (data-driven)

Both formatters render the same per-event partials — a heading, a
semantic color, the entity-context facts the event's payload actually
carries (release reference, platform, rings, approval, round,
actor, feedback type — never invented blanks), and a deep link back
to the console entity:

- release-grain events link `/releases/{application}/{release}`;
- artifact-grain events link the application page;
- every card carries the event name and occurrence time.

Failure events (`ARTIFACT_FAILED` with its `failureCode`,
`FEEDBACK_MARKED_CRITICAL`, rejection reasons on
`RELEASE_TRANSITIONED`) surface their blocker vocabulary first-class.
A name without a template renders the default partial — reserved
names arriving with their owning stories read correctly on day one.
The template table is deliberately the contract (the story's own
note): the S14-05 SIEM formatter and V3 AI summaries render the same
partials through new formatters, not new templates.

Deep links are built on `shipperdog.notifications.console-base-url`
(`CONSOLE_BASE_URL` in the deployment env; the default is the local
compose console — production sets its real origin).

### The Slack body (Block Kit)

```json
{
  "text": "Release promoted — Banking 7.12.0",
  "blocks": [
    { "type": "header", "text": { "type": "plain_text", "text": "Release promoted — Banking 7.12.0" } },
    { "type": "section", "fields": [
        { "type": "mrkdwn", "text": "*Release*\nBanking 7.12.0" },
        { "type": "mrkdwn", "text": "*To ring*\nbeta" } ] },
    { "type": "context", "elements": [
        { "type": "mrkdwn", "text": "RELEASE_PROMOTED • 2026-10-04T12:00:00Z" } ] },
    { "type": "actions", "elements": [
        { "type": "button", "text": { "type": "plain_text", "text": "Open release" },
          "url": "https://console.example/releases/<app>/<release>" } ] }
  ]
}
```

`text` is the fallback strip notification surfaces render; Slack
ignores the signature headers (its URLs are themselves the bearer
capability) — they cost nothing and fronting relays can still verify.

### The Teams body (connector card)

```json
{
  "@type": "MessageCard",
  "@context": "http://schema.org/extensions",
  "themeColor": "2EB67D",
  "summary": "Release promoted — Banking 7.12.0",
  "sections": [ {
    "activityTitle": "Release promoted — Banking 7.12.0",
    "facts": [ { "name": "Release", "value": "Banking 7.12.0" } ],
    "text": "RELEASE_PROMOTED • 2026-10-04T12:00:00Z",
    "markdown": false,
    "potentialAction": [ {
      "@type": "OpenUri", "name": "Open release",
      "targets": [ { "os": "default", "url": "https://console.example/releases/<app>/<release>" } ] } ] } ]
}
```

The card is the connector-card contract (the acceptance criterion's
own shape). For Teams workflow URLs — the Power Automate mechanism
that replaced retired Office 365 connector creation — create the
workflow from the "Post to a channel when a webhook request is
received" template; the posted facts carry through the same, and the
card's facts and action map onto the adaptive card the workflow
posts. Teams workflow URLs are long: they must fit the family's
shared 2048-character ceiling (the policy enforces it for every
channel alike).

### The test message (the console's "Send test")

```
POST /api/v1/organizations/{orgId}/notification-subscriptions/{id}/test
-> { "delivered": true|false, "channel": "SLACK", "error": null|"...", "sentAt": "..." }
```

One synthetic `NOTIFICATION_TEST_SENT` event through the REAL channel
pipeline — the same card a chat client would render, the same signed
POST a receiver would verify, the same ledger row the delivery log
would show — so a passing test is end-to-end proof and a failing one
names why, in the response AND in the delivery log (a refused test is
a SCHEDULED row the retry drain keeps working, exactly like a real
delivery; failures are never silent). The synthetic event is recorded
in the outbox born RELAYED — the synchronous dispatch IS its
publication; the relay never re-fans a test out. `IN_APP` tests land
the row in the recipients' inbox; `EMAIL` tests ride the seam.
Audited as `NOTIFICATION_SUBSCRIPTION_TESTED` (channel and outcome;
never the URL or secret). Not gated by the subscription's event
routing — the test verifies the configuration, whatever it routes.

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
| `shipperdog.notifications.console-base-url` | http://localhost:5173 | The console's externally reachable origin — the base of every deep link in Slack/Teams messages (S12-02) |

See [ADR-0007](adr/ADR-0007-event-bus-transport.md) for the transport
decision and the broker migration path, [authorization.md](authorization.md)
for the permission matrix, and [database-migrations.md](database-migrations.md)
for V39 (the outbox, subscription, and delivery tables).
