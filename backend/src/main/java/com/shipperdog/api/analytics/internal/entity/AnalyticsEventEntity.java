package com.shipperdog.api.analytics.internal.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One device-side report against a release (issue #49, S11-04,
 * FR-029): a row of the frozen S15-01 SDK event contract's store —
 * installs, session summaries (with their crash and ANR counts),
 * uninstalls, and tester verdicts. M2 has no emitters; the events API
 * and this table freeze the shape so the SDK lands without a schema
 * change, and every DEVICE_SDK metric of the dictionary answers the
 * explicit NO_DATA until rows exist (fail-closed, S10-03 AC4 — never
 * a zero, never a pass).
 *
 * <p>Not a seam-scoped entity, by design: the {@code tenant_id} here
 * is the REPORTING device's tenant, while a release's events span
 * every targeted tenant's reporters — the per-release reads the
 * metrics computation performs are cross-tenant exactly like the
 * audit trail's own release evidence (the audit_events precedent:
 * dimension columns, not a console seam). The ingest route's tenant
 * interceptor binds the reporting context and the service validates
 * the release/target relationship before any write; no other write
 * path exists. Rows are append-only history (the audit doctrine):
 * corrections are new events, never edits.
 */
@Entity
@Table(name = "analytics_events")
public class AnalyticsEventEntity {

    /** The frozen {@code analytics_event_type} vocabulary (V38). */
    public enum Type {
        /** A device install report ({@code result}: SUCCEEDED or FAILED). */
        INSTALL,
        /** A session summary: the report's session, crash, and ANR counts. */
        SESSION,
        /** A device uninstall report. */
        UNINSTALL,
        /** A tester's verdict on the release ({@code result}: APPROVED or REJECTED). */
        TESTER_VERDICT
    }

    /** The per-type result vocabulary (INSTALL: SUCCEEDED/FAILED; TESTER_VERDICT: APPROVED/REJECTED). */
    public enum Result {
        SUCCEEDED,
        FAILED,
        APPROVED,
        REJECTED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID organizationId;

    private UUID tenantId;

    private UUID applicationId;

    private UUID versionId;

    private UUID releaseId;

    private UUID deviceId;

    private UUID testerId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    private Type eventType;

    @Enumerated(EnumType.STRING)
    private Result result;

    private Integer sessions;

    private Integer crashSessions;

    private Integer anrSessions;

    private Instant occurredAt;

    private Instant receivedAt;

    /**
     * The optional client event id (S15-01, V54): the dedup key with
     * (device, release) that makes the contract's at-least-once
     * delivery idempotent for emitters that carry one — null rows
     * keep the original non-idempotent semantics byte-for-byte.
     */
    private UUID eventId;

    protected AnalyticsEventEntity() {}

    /**
     * One validated report: the release's owning dimensions beside the
     * reporter's tenant, the device and tester the service has proven
     * against that tenant, and the type's own shape (the checks of
     * V38 mirrored in code — the database refuses what the service
     * missed).
     */
    public AnalyticsEventEntity(
            UUID organizationId,
            UUID tenantId,
            UUID applicationId,
            UUID versionId,
            UUID releaseId,
            UUID deviceId,
            UUID testerId,
            Type eventType,
            Result result,
            Integer sessions,
            Integer crashSessions,
            Integer anrSessions,
            Instant occurredAt,
            Instant receivedAt,
            UUID eventId) {
        this.organizationId = organizationId;
        this.tenantId = tenantId;
        this.applicationId = applicationId;
        this.versionId = versionId;
        this.releaseId = releaseId;
        this.deviceId = deviceId;
        this.testerId = testerId;
        this.eventType = eventType;
        this.result = result;
        this.sessions = sessions;
        this.crashSessions = crashSessions;
        this.anrSessions = anrSessions;
        this.occurredAt = occurredAt;
        this.receivedAt = receivedAt;
        this.eventId = eventId;
    }

    public UUID getId() {
        return id;
    }

    public UUID organizationId() {
        return organizationId;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public UUID applicationId() {
        return applicationId;
    }

    public UUID versionId() {
        return versionId;
    }

    public UUID releaseId() {
        return releaseId;
    }

    public UUID deviceId() {
        return deviceId;
    }

    public UUID testerId() {
        return testerId;
    }

    public Type eventType() {
        return eventType;
    }

    public Result result() {
        return result;
    }

    public Integer sessions() {
        return sessions;
    }

    public Integer crashSessions() {
        return crashSessions;
    }

    public Integer anrSessions() {
        return anrSessions;
    }

    public Instant occurredAt() {
        return occurredAt;
    }

    public Instant receivedAt() {
        return receivedAt;
    }

    public UUID eventId() {
        return eventId;
    }
}
