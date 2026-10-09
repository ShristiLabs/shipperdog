-- V38__release_analytics.sql
-- Release analytics pipeline (issue #49, S11-04, FR-029, PRD 28/29/33/41):
-- the SDK event store, the hourly/daily metric rollups, and the download
-- backfill that seeds them from the S8-04 facts.
--
-- Conventions (see docs/database-migrations.md):
--   * Expand-only, forward-only migration (ADR-0001): new tables and
--     indexes only; nothing existing is dropped, renamed, or re-typed.
--   * Constraint names use the explicit ck_/uq_/idx_/fk_ prefixes.
--
-- The pipeline (docs/analytics.md is the metrics dictionary):
--   * analytics_events: the frozen S15-01 SDK event contract's store —
--     installs, sessions (with crash/ANR counts), uninstalls, and
--     tester verdicts, reported per device against a release the
--     reporting tenant is targeted by. M2 has no emitters; the API and
--     this table freeze the shape so the SDK lands without a schema
--     change. The device-side metrics answer NO_DATA (fail-closed per
--     S10-03) until events arrive — never a zero.
--   * metric_hourly / metric_daily: precomputed rollups keyed by the
--     FR-029 dimensions (application, release, ring, time bucket),
--     recomputed idempotently from the source facts (audit downloads,
--     approval decisions, promotion timeline, analytics_events) — a
--     re-run overwrites the same buckets, so late events inside the
--     job's lookback heal by recompute and older history is repaired
--     by the backfill trigger. Counts aggregate by sum; rates and
--     durations recompute at each grain (rates never sum).
--   * The V37 header's deferred promise lands here: device-scoped
--     analytics joins get their index (analytics_events.device_id +
--     occurred_at), beside the release/application/tenant grains.
--
-- Ring attribution: an internal event's ring is the ring the release
-- occupied when it occurred — the starting ring until the first
-- COMPLETED promotion, then the last COMPLETED promotion's to-ring
-- (the timeline's own semantics; selective promotion moves targets,
-- and the release-level attribution is the rollup's documented grain).

-- ---------------------------------------------------------------------------
-- analytics_event_type: the frozen SDK vocabulary
-- ---------------------------------------------------------------------------
CREATE TYPE analytics_event_type AS ENUM (
    'INSTALL',
    'SESSION',
    'UNINSTALL',
    'TESTER_VERDICT'
);

-- ---------------------------------------------------------------------------
-- analytics_events: the append-only SDK event store
-- ---------------------------------------------------------------------------
CREATE TABLE analytics_events (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES organizations (id),
    -- The reporting device's tenant (devices are tenant-scoped, V37):
    -- the reporter, not the release's owning tenant.
    tenant_id       uuid NOT NULL REFERENCES tenants (id),
    application_id  uuid NOT NULL REFERENCES applications (id),
    version_id      uuid REFERENCES versions (id),
    release_id      uuid NOT NULL REFERENCES releases (id),
    device_id       uuid REFERENCES devices (id),
    tester_id       uuid REFERENCES testers (id),
    event_type      analytics_event_type NOT NULL,
    -- INSTALL: SUCCEEDED | FAILED. TESTER_VERDICT: APPROVED | REJECTED.
    -- SESSION/UNINSTALL carry none.
    result          varchar(16),
    -- SESSION aggregates: the session counts the report carries.
    sessions        int,
    crash_sessions  int,
    anr_sessions    int,
    occurred_at     timestamptz NOT NULL,
    received_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_analytics_events_result_shape CHECK (
        (event_type = 'INSTALL' AND result IN ('SUCCEEDED', 'FAILED'))
        OR (event_type = 'TESTER_VERDICT' AND result IN ('APPROVED', 'REJECTED'))
        OR (event_type IN ('SESSION', 'UNINSTALL') AND result IS NULL)),
    CONSTRAINT ck_analytics_events_session_shape CHECK (
        (event_type = 'SESSION'
            AND sessions IS NOT NULL AND sessions > 0
            AND crash_sessions IS NOT NULL AND crash_sessions >= 0
            AND anr_sessions IS NOT NULL AND anr_sessions >= 0
            AND crash_sessions + anr_sessions <= sessions)
        OR (event_type <> 'SESSION'
            AND sessions IS NULL AND crash_sessions IS NULL AND anr_sessions IS NULL))
);

-- The per-release metric reads (every device-side formula scans a
-- release's events by type and time).
CREATE INDEX idx_analytics_events_release
    ON analytics_events (release_id, event_type, occurred_at);

-- The cross-release/application windows.
CREATE INDEX idx_analytics_events_application
    ON analytics_events (application_id, event_type, occurred_at);

-- The tenant grain.
CREATE INDEX idx_analytics_events_tenant
    ON analytics_events (tenant_id, occurred_at);

-- The V37-deferred device-scoped analytics join index: device facts
-- joined to event history (the devices.md note named S11-04 as its
-- owner).
CREATE INDEX idx_analytics_events_device
    ON analytics_events (device_id, occurred_at);

-- ---------------------------------------------------------------------------
-- metric_hourly / metric_daily: the rollup store
-- ---------------------------------------------------------------------------

-- The nil uuid is the "no ring dimension" sentinel (the rings table's
-- own COALESCE trick, inverted: ring_id is NOT NULL so it can join the
-- primary key; metrics that carry no ring dimension store the nil id).
CREATE TABLE metric_hourly (
    organization_id uuid NOT NULL REFERENCES organizations (id),
    -- The release's owning tenant dimension (NULL = organization-shared
    -- application, the releases row's own shape).
    tenant_id       uuid REFERENCES tenants (id),
    application_id  uuid NOT NULL REFERENCES applications (id),
    release_id      uuid NOT NULL REFERENCES releases (id),
    ring_id         uuid NOT NULL DEFAULT '00000000-0000-0000-0000-000000000000',
    metric          varchar(64) NOT NULL,
    bucket_start    timestamptz NOT NULL,
    value           double precision NOT NULL,
    computed_at     timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (application_id, metric, bucket_start, release_id, ring_id),
    CONSTRAINT ck_metric_hourly_value_finite CHECK (value >= 0)
);

-- The organization-grain window reads (dashboards, cross-release).
CREATE INDEX idx_metric_hourly_org_window
    ON metric_hourly (organization_id, metric, bucket_start);

-- The tenant-grain window reads.
CREATE INDEX idx_metric_hourly_tenant_window
    ON metric_hourly (tenant_id, metric, bucket_start);

COMMENT ON TABLE metric_hourly IS
    'Hourly release-metric rollups (S11-04): recomputed idempotently from source facts; rates and durations recompute per grain, never sum.';

CREATE TABLE metric_daily (
    organization_id uuid NOT NULL REFERENCES organizations (id),
    tenant_id       uuid REFERENCES tenants (id),
    application_id  uuid NOT NULL REFERENCES applications (id),
    release_id      uuid NOT NULL REFERENCES releases (id),
    ring_id         uuid NOT NULL DEFAULT '00000000-0000-0000-0000-000000000000',
    metric          varchar(64) NOT NULL,
    bucket_start    timestamptz NOT NULL,
    value           double precision NOT NULL,
    computed_at     timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (application_id, metric, bucket_start, release_id, ring_id),
    CONSTRAINT ck_metric_daily_value_finite CHECK (value >= 0)
);

CREATE INDEX idx_metric_daily_org_window
    ON metric_daily (organization_id, metric, bucket_start);

CREATE INDEX idx_metric_daily_tenant_window
    ON metric_daily (tenant_id, metric, bucket_start);

COMMENT ON TABLE metric_daily IS
    'Daily release-metric rollups (S11-04): recomputed idempotently from source facts; rates and durations recompute per grain, never sum.';

-- ---------------------------------------------------------------------------
-- Backfill: pre-existing download facts seed the rollups (AC3)
-- ---------------------------------------------------------------------------

-- The S8-04 download trail attributes to a release through
-- release_artifacts (an artifact belongs to one version, and one
-- release exists per (application, version) — the join is functional).
-- A no-op on every greenfield database today; correct on any state,
-- the V24 discipline.
INSERT INTO metric_hourly (
    organization_id, tenant_id, application_id, release_id, ring_id,
    metric, bucket_start, value, computed_at)
SELECT
    r.organization_id,
    r.tenant_id,
    r.application_id,
    r.id,
    COALESCE(latest_hop.to_ring_id, r.starting_ring_id),
    'DOWNLOADS',
    date_trunc('hour', a.occurred_at),
    count(*),
    now()
FROM audit_events a
JOIN release_artifacts ra ON ra.artifact_id = a.artifact_id
JOIN releases r ON r.id = ra.release_id
LEFT JOIN LATERAL (
    SELECT rp.to_ring_id
    FROM release_promotions rp
    WHERE rp.release_id = r.id
      AND rp.status = 'COMPLETED'
      AND rp.occurred_at <= a.occurred_at
    ORDER BY rp.occurred_at DESC
    LIMIT 1
) AS latest_hop ON true
WHERE a.event_type = 'DOWNLOAD_ISSUED'
  AND a.success
GROUP BY r.organization_id, r.tenant_id, r.application_id, r.id,
         COALESCE(latest_hop.to_ring_id, r.starting_ring_id),
         date_trunc('hour', a.occurred_at)
ON CONFLICT (application_id, metric, bucket_start, release_id, ring_id)
    DO UPDATE SET value = EXCLUDED.value, computed_at = now();

INSERT INTO metric_daily (
    organization_id, tenant_id, application_id, release_id, ring_id,
    metric, bucket_start, value, computed_at)
SELECT
    r.organization_id,
    r.tenant_id,
    r.application_id,
    r.id,
    COALESCE(latest_hop.to_ring_id, r.starting_ring_id),
    'DOWNLOADS',
    date_trunc('day', a.occurred_at),
    count(*),
    now()
FROM audit_events a
JOIN release_artifacts ra ON ra.artifact_id = a.artifact_id
JOIN releases r ON r.id = ra.release_id
LEFT JOIN LATERAL (
    SELECT rp.to_ring_id
    FROM release_promotions rp
    WHERE rp.release_id = r.id
      AND rp.status = 'COMPLETED'
      AND rp.occurred_at <= a.occurred_at
    ORDER BY rp.occurred_at DESC
    LIMIT 1
) AS latest_hop ON true
WHERE a.event_type = 'DOWNLOAD_ISSUED'
  AND a.success
GROUP BY r.organization_id, r.tenant_id, r.application_id, r.id,
         COALESCE(latest_hop.to_ring_id, r.starting_ring_id),
         date_trunc('day', a.occurred_at)
ON CONFLICT (application_id, metric, bucket_start, release_id, ring_id)
    DO UPDATE SET value = EXCLUDED.value, computed_at = now();
