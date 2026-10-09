# ADR-0007: The domain-event bus ships in-process over a transactional outbox; the broker is a swap behind one port

Date: 2026-10-04
Status: Accepted (issue #51, S12-01)

## Context

PRD 51 wants internal state changes published as domain events, and
PRD 45 names Kafka or Cloud Pub/Sub as the backbone — "Cloud Pub/Sub
first (managed, less ops); Kafka when scale/retention demands", in
the S12-01 technical notes. But M1 deliberately deferred messaging
infrastructure: the compose stack runs PostgreSQL, Redis, and a GCS
emulator, no broker, and every E1-E11 story shipped its transitions
as typed in-process Spring events (`ReleaseTransitionedEvent`,
`ApprovalRequestedEvent`, `ReleasePromotedEvent`,
`FeedbackReceivedEvent`, `FeedbackMarkedCriticalEvent`) with the
notification consumer named as the future bus's first rider.

S12-01 must therefore formalize the event chain without a broker: the
durability a broker provides (a published event survives the
publisher) has to come from somewhere, and the choice of where it
comes from decides how expensive the eventual broker migration is.

## Decision

**The bus is a transactional outbox over PostgreSQL plus an in-process
Spring fan-out, behind a single port:**

1. **Domain modules keep publishing typed in-process events inside
   their committing transactions** (the established discipline,
   unchanged). S12-01 adds the missing events at the transitions the
   issue names (artifact upload/validation/download-issuance, release
   creation, tester invitation) so every wired transition has one.
2. **The outbox recorder** (notification module) observes each typed
   event `BEFORE_COMMIT` and appends a `notification_outbox` row in
   the SAME transaction — the `PersistentAuditEventSink` pattern from
   S1-06. A rollback cancels the event; a commit guarantees it; no
   crash separates the business change from its event.
3. **The relay** (scheduled drain) publishes each due row to the
   `DomainEventBus` port and then marks it RELAYED — publish before
   mark is what makes the guarantee at-least-once rather than
   at-most-once. Failures retry with exponential backoff to an
   attempt ceiling, then stay FAILED in the table, visible beside the
   delivery log.
4. **The port** (`DomainEventBus`) is the entire upgrade seam: V1.5's
   implementation is `InProcessDomainEventBus` (Spring's
   `ApplicationEventPublisher`). When Kafka or Cloud Pub/Sub lands, a
   new implementation of that one interface replaces it beside the
   relay — domain modules, the recorder, and the drain stay untouched;
   the notification dispatcher becomes a broker consumer exactly the
   way it is a Spring listener today.

**Consumers are idempotent by event id** (the outbox row's uuid, PRD
51's note): a replayed row re-publishes, and the delivery ledger's
unique key `(event, subscription, recipient)` collapses the duplicate
fan-out. Webhook receivers get the same id in the body's `event_id`
and should deduplicate the same way (docs/notifications.md).

**Names not wired now** (no transition exists to publish them):
`QUALITY_GATE_STARTED/PASSED` (no gate state machine; results are
recorded, S10-03), `DISTRIBUTION_*` and `TESTER_NOTIFIED`
(distribution execution, E10), `TESTER_INSTALLED` (the SDK analytics
stream owns that grain), `PROMOTION_REQUESTED` (a refused attempt is
the audited `RELEASE_PROMOTION_BLOCKED`; the committed hop carries
`RELEASE_PROMOTED`), `NEW_VERSION_AVAILABLE` (tester-visible
availability is distribution targeting). They are reserved vocabulary
— added with the story that owns the transition, never renamed.

## Alternatives considered

- **Direct broker integration now** (the Pub/Sub-first reading of the
  issue): rejected — it takes the platform's first messaging
  dependency for a single in-process consumer, adds broker
  provisioning to every environment, and buys nothing the outbox does
  not already provide at this scale. The port keeps the migration a
  one-bean change.
- **Listening to the audit stream** (reuse `audit_events` as the
  event source, as the #6 technical note once mused): rejected —
  audit records lack `release_id` and carry stringly-typed detail;
  the bus vocabulary would inherit the audit vocabulary forever, and
  PRD 51's entity-context contract deserves typed events.
- **After-commit publish without an outbox**: rejected — a crash
  between commit and publish loses the event silently; the outbox is
  the standard cure and the audit table already proved the pattern in
  this codebase.

## Consequences

- Every wired transition costs one extra insert in its transaction
  (the outbox row) — the same cost the audit sink already pays, and
  bounded by transition rate, not request rate.
- The relay's drain is idempotent-safe under double-publish (two
  relay instances, a crash between publish and mark); consumers must
  keep the event-id discipline.
- Outbox rows accumulate; retention (archive/purge policy) is an
  operational decision deferred to the first environment that needs
  it — the delivery ledger and webhook receipts are the short-horizon
  reads, the outbox the history.
- The broker migration (when scale/retention demand it, per PRD 45)
  is additive: implement the port, deploy, remove the in-process bean
  — and this ADR is the reminder that V1.5 shipped the seam on
  purpose.
