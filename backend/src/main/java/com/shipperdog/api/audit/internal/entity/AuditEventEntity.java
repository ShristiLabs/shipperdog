package com.shipperdog.api.audit.internal.entity;

import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreRemove;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import tools.jackson.databind.ObjectMapper;

/**
 * Maps the {@code audit_events} table (V5): one immutable audit record.
 * Append-only is enforced in three layers: the entity exposes no mutating
 * setters, the {@code @PreUpdate}/{@code @PreRemove} callbacks reject any
 * modification or removal attempt at runtime, and the repository
 * interface declares only insert and query methods. Database-level
 * enforcement (REVOKE UPDATE/DELETE from the runtime role) is a deployment
 * step documented in the migration.
 */
@Entity
@Table(name = "audit_events")
public class AuditEventEntity {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    private AuditEventType eventType;

    private Instant occurredAt;

    private Instant createdAt;

    private UUID userId;

    private UUID orgId;

    private UUID tenantId;

    private UUID applicationId;

    private UUID versionId;

    private UUID artifactId;

    private String ipAddress;

    private String correlationId;

    private String reason;

    private boolean success;

    /** Machine-readable context as a JSON object. */
    @JdbcTypeCode(SqlTypes.JSON)
    private String detail;

    /** JSON snapshot of the state before the operation. */
    @JdbcTypeCode(SqlTypes.JSON)
    private String beforeSnapshot;

    /** JSON snapshot of the state after the operation. */
    @JdbcTypeCode(SqlTypes.JSON)
    private String afterSnapshot;

    protected AuditEventEntity() {}

    /** Maps an enriched, redacted event to its durable row. */
    public static AuditEventEntity from(AuditEvent event) {
        AuditEventEntity entity = new AuditEventEntity();
        entity.eventType = event.type();
        entity.occurredAt = event.occurredAt();
        entity.userId = event.userId();
        entity.orgId = event.orgId();
        entity.tenantId = event.tenantId();
        entity.applicationId = event.applicationId();
        entity.versionId = event.versionId();
        entity.artifactId = event.artifactId();
        entity.ipAddress = event.ipAddress();
        entity.correlationId = event.correlationId();
        entity.reason = event.reason();
        entity.success = event.success();
        entity.detail = serializeDetail(event.detail());
        entity.beforeSnapshot = event.before();
        entity.afterSnapshot = event.after();
        return entity;
    }

    private static String serializeDetail(Map<String, String> detail) {
        if (detail.isEmpty()) {
            return "{}";
        }
        return MAPPER.writeValueAsString(detail);
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (occurredAt == null) {
            occurredAt = createdAt;
        }
    }

    /** Audit history is immutable (PRD 27); updates are rejected. */
    @PreUpdate
    void onUpdate() {
        throw new IllegalStateException("audit_events is append-only: update rejected");
    }

    /** Audit history is immutable (PRD 27); deletes are rejected. */
    @PreRemove
    void onRemove() {
        throw new IllegalStateException("audit_events is append-only: delete rejected");
    }

    public UUID getId() {
        return id;
    }

    public AuditEventType getEventType() {
        return eventType;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getApplicationId() {
        return applicationId;
    }

    public UUID getVersionId() {
        return versionId;
    }

    public UUID getArtifactId() {
        return artifactId;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public String getReason() {
        return reason;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getDetail() {
        return detail;
    }

    public String getBeforeSnapshot() {
        return beforeSnapshot;
    }

    public String getAfterSnapshot() {
        return afterSnapshot;
    }
}
