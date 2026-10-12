package com.shipperdog.api.notification.internal.entity;

import com.shipperdog.api.notification.AuditStreamFormat;
import com.shipperdog.api.notification.AuditStreamKind;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Maps the {@code audit_stream_exports} table (V53): one org-scoped
 * SIEM export of issue #71 (S14-05) -- a destination, a format, an
 * event-type filter, and the SECRETS AS REFERENCES ONLY (AC5): the
 * HMAC signing key and the optional collector auth value are
 * secret-manager references resolved at delivery time through the
 * environment module's {@code SecretReferenceResolver} port; no
 * secret material ever lands on the row, in a view, or on a log
 * line. Organization-grain reference data (the rings pattern): the
 * organization dimension only, a plain repository with
 * organization-scoped queries.
 *
 * <p>The bucket leg's {@code watermark} pair ({@code occurredAt} +
 * {@code id}) is the keyset cursor of the last exported row and
 * {@code batchSeq} the last written batch's sequence -- the batcher's
 * own columns, advanced only by a committed batch.
 */
@Entity
@Table(name = "audit_stream_exports")
public class AuditStreamExportEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID organizationId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    private AuditStreamKind kind;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    private AuditStreamFormat format;

    /** The stream's event-type filter ({@code AuditEventType} names); empty streams everything. */
    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> eventTypes = new ArrayList<>();

    private String destinationUrl;

    private String bucketUri;

    private String signingSecretRef;

    private String authHeaderName;

    private String authSecretRef;

    private boolean enabled = true;

    /** The batcher's keyset cursor: the last exported row's occurrence instant, null until the first batch. */
    private Instant watermark;

    /** The batcher's keyset tiebreak: the last exported row's audit id. */
    private UUID watermarkId;

    private long batchSeq;

    /**
     * The optimistic-lock stamp: a stale concurrent edit refuses with
     * the public 409 instead of silently resurrecting a rotated
     * secret reference or replaced destination.
     */
    @Version
    private long version;

    private UUID createdBy;

    private Instant createdAt;

    private Instant updatedAt;

    protected AuditStreamExportEntity() {}

    /**
     * One export (validated by the service before any write): the
     * owning organization, the kind, the kind-appropriate destination
     * and format, the filter, the secret references, and the author.
     */
    public AuditStreamExportEntity(
            UUID organizationId,
            AuditStreamKind kind,
            AuditStreamFormat format,
            List<String> eventTypes,
            String destinationUrl,
            String bucketUri,
            String signingSecretRef,
            String authHeaderName,
            String authSecretRef,
            Instant initialWatermark,
            UUID createdBy,
            Instant now) {
        this.organizationId = organizationId;
        this.kind = kind;
        this.format = format;
        this.eventTypes = new ArrayList<>(eventTypes);
        this.destinationUrl = destinationUrl;
        this.bucketUri = bucketUri;
        this.signingSecretRef = signingSecretRef;
        this.authHeaderName = authHeaderName;
        this.authSecretRef = authSecretRef;
        this.enabled = true;
        this.watermark = initialWatermark;
        this.watermarkId = initialWatermark == null ? null : UUID.fromString("00000000-0000-0000-0000-000000000000");
        this.batchSeq = 0;
        this.createdBy = createdBy;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /**
     * Replaces the mutable facts (the kind never moves; an edit
     * omits a secret reference to keep the standing one, supplies it
     * to rotate). An explicit backfill watermark move is refused --
     * the cursor is the batcher's alone.
     */
    public void reconfigure(
            AuditStreamFormat format,
            List<String> eventTypes,
            String destinationUrl,
            String bucketUri,
            String signingSecretRef,
            String authHeaderName,
            String authSecretRef,
            boolean enabled,
            Instant now) {
        if (format != null) {
            this.format = format;
        }
        if (eventTypes != null) {
            this.eventTypes = new ArrayList<>(eventTypes);
        }
        if (destinationUrl != null) {
            this.destinationUrl = destinationUrl;
        }
        if (bucketUri != null) {
            this.bucketUri = bucketUri;
        }
        if (signingSecretRef != null) {
            this.signingSecretRef = signingSecretRef;
        }
        if (authHeaderName != null && authSecretRef != null) {
            this.authHeaderName = authHeaderName;
            this.authSecretRef = authSecretRef;
        }
        this.enabled = enabled;
        this.updatedAt = now;
    }

    /** Whether this export streams the named audit event type (an empty filter streams everything). */
    public boolean streams(String auditEventTypeName) {
        return enabled && (eventTypes.isEmpty() || eventTypes.contains(auditEventTypeName));
    }

    /** The batcher's commit step: advance the keyset cursor and the sequence together. */
    public void advanceWatermark(long nextSeq, Instant occurredAt, UUID auditRowId, Instant now) {
        this.batchSeq = nextSeq;
        this.watermark = occurredAt;
        this.watermarkId = auditRowId;
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public AuditStreamKind getKind() {
        return kind;
    }

    public AuditStreamFormat getFormat() {
        return format;
    }

    public List<String> getEventTypes() {
        return List.copyOf(eventTypes);
    }

    public String getDestinationUrl() {
        return destinationUrl;
    }

    public String getBucketUri() {
        return bucketUri;
    }

    /** The HMAC key's secret-manager reference. Delivery-side resolution only; never render what it resolves to. */
    public String getSigningSecretRef() {
        return signingSecretRef;
    }

    public String getAuthHeaderName() {
        return authHeaderName;
    }

    public String getAuthSecretRef() {
        return authSecretRef;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Instant getWatermark() {
        return watermark;
    }

    public UUID getWatermarkId() {
        return watermarkId;
    }

    public long getBatchSeq() {
        return batchSeq;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
