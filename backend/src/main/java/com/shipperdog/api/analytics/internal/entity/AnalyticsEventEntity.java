package com.shipperdog.api.analytics.internal.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
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

    /**
     * The {@code analytics_event_type} vocabulary (V38, extended by V57).
     * The four pre-existing values are the frozen SDK contract's legacy
     * family; the five device lifecycle values ride the {@code sdt1.}-
     * bound ingest since S16-08; {@code OTA_CHECK} stays
     * server-authoritative (D9 — the check surface's recorder, S16-07,
     * is its only writer, and the ingest refuses it like an unknown
     * word: a device cannot forge the platform's own counting).
     */
    public enum Type {
        /** A device install report ({@code result}: SUCCEEDED or FAILED). */
        INSTALL,
        /** A session summary: the report's session, crash, and ANR counts. */
        SESSION,
        /** A device uninstall report. */
        UNINSTALL,
        /** A tester's verdict on the release ({@code result}: APPROVED or REJECTED). */
        TESTER_VERDICT,
        /** The server-inserted check outcome (D9: the one OTA event where the release is known). */
        OTA_CHECK,
        /** A device's first observation of a served manifest (S16-08 ingest). */
        OTA_SEEN,
        /** A device's bundle download report (S16-08 ingest). */
        OTA_DOWNLOAD,
        /** A device's successful apply report (S16-08 ingest). */
        OTA_APPLIED,
        /** A device's apply failure report, {@code result} the open reason row value (S16-08 ingest). */
        OTA_FAILED,
        /** A device's rollback report (S16-08 ingest). */
        OTA_ROLLED_BACK
    }

    /**
     * The pre-existing family's result vocabulary (INSTALL:
     * SUCCEEDED/FAILED; TESTER_VERDICT: APPROVED/REJECTED) — the ingest's
     * validation enum. The OTA family's results are OPEN row values (the
     * V42 provider precedent V57 names: {@code update_available}, {@code
     * checksum_mismatch}, ...), so the column itself is a String since
     * S16-07 and this enum validates only the four pre-existing types.
     */
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

    /**
     * The event's outcome word: one of the pre-existing family's closed
     * vocabulary (the ingest validates it into {@link Result} and writes
     * the enum's name) or, since V57/S16-07, the OTA family's OPEN row
     * value ({@code update_available} and its kin, up to 32 characters —
     * the varchar the migration widened). String, never an enum: the OTA
     * vocabulary is row values, not schema migrations.
     */
    @jakarta.persistence.Column(name = "result", length = 32)
    private String result;

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

    /**
     * The OTA bundle the report attributes to (V57, S16-05/S16-08):
     * NULL on every legacy-family report, the manifest-served bundle's
     * id on every OTA report (the ingest's validated attribution —
     * {@code ck_analytics_events_ota_shape} requires it). The read
     * side (the bundle-scoped crash-free arithmetic and the S16-09/10
     * funnels) keys on it.
     */
    @jakarta.persistence.Column(name = "ota_bundle_id")
    private UUID otaBundleId;

    /**
     * The OTA report's free-form detail object (V57, S16-08): a JSON
     * object or NULL — the fleet device identity's carrier (the
     * {@code device_id} member, the check surface's own raw
     * identifier vocabulary) beside whatever lifecycle context the
     * report carries. Never secret material (V57's own comment).
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @jakarta.persistence.Column(name = "ota_payload")
    private Map<String, Object> otaPayload;

    protected AnalyticsEventEntity() {}

    /**
     * One validated report: the release's owning dimensions beside the
     * reporter's tenant, the device and tester the service has proven
     * against that tenant, and the type's own shape (the checks of
     * V38/V57 mirrored in code — the database refuses what the service
     * missed). The result crosses as its storage word (the ingest
     * writes the validated enum's name; the OTA family's open row
     * values render exactly as validated). The OTA attribution pair
     * (bundle, payload) is non-null only on the OTA family — the
     * legacy family's events carry both NULL (the ota_shape check's
     * own closed disjunction).
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
            String result,
            Integer sessions,
            Integer crashSessions,
            Integer anrSessions,
            Instant occurredAt,
            Instant receivedAt,
            UUID eventId,
            UUID otaBundleId,
            Map<String, Object> otaPayload) {
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
        this.otaBundleId = otaBundleId;
        this.otaPayload = otaPayload;
    }

    /**
     * One server-inserted OTA event row (S16-07, D9): no device, no
     * tester, no session aggregates, no client event id (the server's
     * own rows are plain appends — the V54 dedup key governs
     * client-carried ids, and a server check row is one per served
     * manifest by construction), and the bundle attribution V57's
     * {@code ck_analytics_events_ota_shape} requires.
     */
    public AnalyticsEventEntity(
            UUID organizationId,
            UUID tenantId,
            UUID applicationId,
            UUID versionId,
            UUID releaseId,
            Type eventType,
            String result,
            UUID otaBundleId,
            Instant occurredAt,
            Instant receivedAt) {
        this.organizationId = organizationId;
        this.tenantId = tenantId;
        this.applicationId = applicationId;
        this.versionId = versionId;
        this.releaseId = releaseId;
        this.eventType = eventType;
        this.result = result;
        this.otaBundleId = otaBundleId;
        this.occurredAt = occurredAt;
        this.receivedAt = receivedAt;
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

    /** The outcome word as stored: a closed-vocabulary name or an OTA open row value. */
    public String result() {
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

    /** The OTA bundle the report attributes to (V57); null on unattributed rows. */
    public UUID otaBundleId() {
        return otaBundleId;
    }

    /** The OTA report's detail object (V57); null on rows without one. */
    public Map<String, Object> otaPayload() {
        return otaPayload;
    }
}
