-- V5__audit_framework.sql
-- Audit logging framework (issue #6, S1-06): durable append-only audit
-- event store (PRD FR-024, section 27).
--
-- Conventions (see docs/database-migrations.md):
--   * Expand-only, forward-only migration (ADR-0001): one new table,
--     nothing dropped or altered.
--   * Indexes idx_<table>_<column>. No unique constraints: audit history
--     may legitimately contain identical events.
--
-- Design decisions recorded for reviewers:
--
-- event_type is varchar(64), not a PostgreSQL enum. Audit event types are
-- an open, story-growth vocabulary (every story from S2 on appends kinds
-- to the code-side AuditEventType enum); per-story ALTER TYPE churn buys
-- nothing. This mirrors the release_gates.gate_type precedent (V1) for
-- open vocabularies. The Java enum validates the vocabulary on write.
--
-- No foreign keys. The audit trail is immutable history and must survive
-- the full lifecycle of the entities it references (and application,
-- version, and artifact tables only gain their final shape in E3-E5).
-- Actors and context are recorded as plain UUIDs.
--
-- No updated_at column, deliberately: the table is append-only, no row is
-- ever updated, and the column would only suggest otherwise. created_at
-- (set by the database) records persistence time; occurred_at records
-- when the event happened (they differ by the outbox write delay, which
-- is within the 2 s SLO of PRD section 42).
--
-- The table doubles as the transactional outbox (issue technical notes):
-- rows are written in the same transaction as the business change; the
-- async relay to the event bus arrives with S12-01 and will add relay
-- bookkeeping columns in its own forward-only migration.
--
-- Deployment hardening (not enforceable here): production should run the
-- API under a role distinct from the migration owner and REVOKE UPDATE,
-- DELETE ON audit_events from it, making append-only a database-level
-- guarantee. The application itself exposes no update/delete path: the
-- repository interface declares only save/saveAll/search, and the JPA
-- entity rejects update/remove callbacks.

CREATE TABLE audit_events (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    event_type      varchar(64) NOT NULL,
    occurred_at     timestamptz NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    user_id         uuid,
    org_id          uuid,
    tenant_id       uuid,
    application_id  uuid,
    version_id      uuid,
    artifact_id     uuid,
    ip_address      varchar(45),
    correlation_id  varchar(64),
    reason          varchar(512),
    success         boolean NOT NULL,
    detail          jsonb NOT NULL DEFAULT '{}'::jsonb,
    before_snapshot jsonb,
    after_snapshot  jsonb
);

COMMENT ON TABLE audit_events IS
    'Append-only audit trail (PRD 27): INSERT only; no UPDATE/DELETE path exists in the application; production deployments should also REVOKE UPDATE/DELETE from the runtime role.';

-- Query paths of GET /api/v1/audit: actor, tenant, and organization
-- filters plus time-range scans. Correlation-id lookups (support and
-- incident workflows) resolve through a single sequential scan until
-- volume justifies an index.
CREATE INDEX idx_audit_events_user_id ON audit_events (user_id);
CREATE INDEX idx_audit_events_org_id ON audit_events (org_id);
CREATE INDEX idx_audit_events_tenant_id ON audit_events (tenant_id);
CREATE INDEX idx_audit_events_occurred_at ON audit_events (occurred_at);
