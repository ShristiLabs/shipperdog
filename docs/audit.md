# Audit logging (S1-06)

The immutable audit trail behind every security-sensitive operation
(PRD section 27, FR-024): who did what, when, from where, and why,
recorded append-only and queryable by compliance reviewers. Implemented
by the `audit` module (`backend/src/main/java/com/shipperdog/api/audit`).
Every security event since S1-03 already flows through the module's
`AuditEventPublisher` seam — S1-06 makes that same seam durable and
transactional without changing any caller.

## Recording events

The one-line API is `AuditLogger` (inject it; it stamps `occurredAt`
from the shared clock when omitted):

```java
audit.record(AuditEvent.builder(AuditEventType.ORGANIZATION_UPDATED)
        .userId(actorId)
        .orgId(organizationId)
        .before(beforeJson)
        .after(afterJson)
        .reason("credential rotation")
        .success(true)
        .build());
```

The publisher binding enriches every event with request context
(correlation id, client IP — attached only where the event did not
carry one), scrubs it (see [Redaction](#redaction)), writes one
structured log line, and persists it. Events published inside a
transaction are persisted in that transaction; events outside one
(denials, login flows) persist immediately.

Every authenticated permission denial is captured automatically by the
RBAC enforcement point as an `ACCESS_DENIED` event carrying the actor,
the required permission, and the attempted method/URI — opaque denials
(concealed 404s and plain 403s alike; see
[authorization](authorization.md)) leave no HTTP trace of what was
denied, so the audit trail is the only record. Unauthenticated attempts
are 401s at the filter chain and are not audited there.

## Event catalog

One `audit_events` row per event (V5 migration; see
[database migrations](database-migrations.md)). Field set per FR-024:

| Field | Meaning |
|---|---|
| `eventType` | kind, from the `AuditEventType` vocabulary below |
| `occurredAt` / `createdAt` | when it happened / when it was persisted (within the 2 s SLO, PRD section 42) |
| `userId` | acting user (null for anonymous) |
| `orgId` / `tenantId` | tenancy context |
| `applicationId` / `versionId` / `artifactId` | affected entity references (plain UUIDs — the table is deliberately FK-free so history outlives its entities) |
| `ipAddress` | client address (first `X-Forwarded-For` hop when proxied), stored truncated to 45 chars |
| `correlationId` | request correlation id (see below) |
| `before` / `after` | JSON snapshots of the changed state (nullable) |
| `reason` | human/compliance-readable motivation (max 512 chars) |
| `success` | outcome |
| `detail` | machine-readable context as JSON (marker codes and similar) |

`AuditEventType` values are never removed; stories append kinds as they
integrate the framework. Current vocabulary: identity
(`USER_REGISTERED`, `INVITATION_CREATED`, `LOGIN_SUCCESS`,
`LOGIN_FAILURE`, `USER_LOCKED_OUT`, `PASSWORD_RESET_REQUESTED`,
`PASSWORD_RESET_COMPLETED`, `TOKEN_ISSUED`, `TOKEN_REFRESHED`,
`TOKEN_REVOKED`, `TOKEN_REUSE_DETECTED`, `PAT_CREATED`,
`PAT_REVOKED` — see [authentication](authentication.md)),
organization (`ORGANIZATION_CREATED`, `ORGANIZATION_UPDATED`,
`ORG_MEMBER_ADDED`, `ORG_IDP_CONFIGURED`, `ORG_MEMBER_ROLE_CHANGED`,
`ORG_MEMBER_REMOVED`), tenant (`TENANT_CREATED`, `TENANT_UPDATED`,
`TENANT_MEMBER_ADDED`, `TENANT_MEMBER_ROLE_CHANGED`,
`TENANT_MEMBER_STATUS_CHANGED`, `TENANT_MEMBER_REMOVED`,
`CROSS_TENANT_ACCESS` — a platform administrator entering a
tenant-scoped route through the override, carrying actor, tenant, and
method/URI; see [authorization](authorization.md)), application
(`APPLICATION_CREATED`, `APPLICATION_UPDATED`, `APPLICATION_SCOPE_CHANGED`
— registry registration, metadata edits, and the audited ownership-scope
transition carrying `ownershipScope.before/after` and
`tenantId.before/after` (S3-02)
and metadata/lifecycle edits, with identifier changes audited as
before/after pairs because they affect artifact matching, S3-01), and
platform (`ACCESS_DENIED`, S1-06).

## Persistence and transactional semantics

The durable sink listens for republished events with a
`BEFORE_COMMIT` transactional listener: inside a transaction the
audit insert joins the caller's transaction (transactional-outbox
behavior), so an audited change and its audit row commit atomically —
a business rollback leaves no orphan audit row, and a failed audit
insert fails the business change with it. Outside any transaction the
insert executes immediately. Persistence is synchronous and well
inside the 2 s SLO. The async relay of persisted rows to the event bus
arrives with S12-01, consuming this table as its outbox.

## Append-only guarantees

Three layers in the application, one in deployment:

- The repository surface declares only `save`/`saveAll` (plain Spring
  Data `Repository` marker — no delete/update/flush-capable parent).
- The JPA entity has no mutating setters; `@PreUpdate`/`@PreRemove`
  callbacks throw on any update or removal attempt.
- No code path constructs update/delete SQL for the table; the read
  side uses the criteria API only.
- Production deployments should additionally run the API under a role
  distinct from the migration owner and `REVOKE UPDATE, DELETE ON
  audit_events` from it (documented in the V5 migration); local/CI
  schemas do not enforce this.

Hash-chain or periodic-anchoring tamper evidence is documented future
hardening, deliberately out of M1 scope (issue #6). The table carries
no `updated_at` by design — rows are never updated.

## Redaction

Applied centrally by the publisher binding to every event's `detail`
map and `before`/`after` snapshots; no caller can bypass it. Key-based:
any key whose normalized name (lower-cased, separators stripped)
contains one of the markers `password`, `secret`, `token`,
`authorization`, `credential`, `cookie`, `apikey`, `privatekey` is
redacted — casing and separator style (`apiKey`, `PRIVATE_KEY`,
`client-secret`) do not evade it. In detail maps the value becomes
`[REDACTED]`; in JSON snapshots the whole subtree under a secret key
is replaced, at any depth. A snapshot that does not parse as JSON is
replaced wholesale with `{"redacted":"unparseable-snapshot"}` so
nothing smuggles past the scrubber. Ordinary keys pass through
untouched.

## Correlation ids

Every request gets a correlation id before anything else runs — the
filter registers at highest precedence, ahead of the security chains,
so 401/403 denials are correlated too. Inbound `X-Correlation-Id` is
accepted verbatim only when it is 8-64 characters over the
URL/filename-safe alphabet (`A-Z a-z 0-9 - _ .`); anything hostile or
malformed is discarded and replaced with a fresh UUID. The id is
echoed on every response, placed in the SLF4J MDC under
`correlationId` (the log pattern renders it as `[<id>]` on every
line), attached to audit events during enrichment, and cleared after
the request. Traces join the same id since S1-07: every span of the
request carries it as the `correlation_id` attribute (see
[observability](observability.md)).

## Querying the trail

`GET /api/v1/audit` — platform administrators only
(`platform:audit:read`; per the PRD 7.1 wildcard this is platform
admins alone). Non-admins receive an opaque `403` problem+json envelope
(since S1-08; see [API conventions](api-conventions.md)) and their
attempt is audited. All filters are optional and conjunctive:

| Parameter | Type | Filters on |
|---|---|---|
| `actor` | UUID | `userId` |
| `organization` | UUID | `orgId` |
| `tenant` | UUID | `tenantId` |
| `eventType` | `AuditEventType` | event kind |
| `applicationId` / `versionId` / `artifactId` | UUID | entity reference |
| `from` / `to` | ISO-8601 instant | `occurredAt`, inclusive |
| `limit` / `cursor` | int / opaque token | pagination (`limit` default 50, 1..100, rejected outside; `cursor` from a prior `next_cursor`) |

Results are newest-first, one page per response:
`{items, next_cursor}` where each event carries the full catalog above
with `detail`/`before`/`after` as raw JSON, and `next_cursor` is
present exactly when another page follows (the platform's cursor
convention, S1-08). Malformed filter values (non-UUID, unknown event
type) and out-of-range limits fail fast with `400` problem+json.
