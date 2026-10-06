package com.shipperdog.api.authorization.internal.grant;

import com.shipperdog.api.tenant.CrossTenantByDesign;
import com.shipperdog.api.tenant.TenantScope;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

/**
 * Maps the {@code version_access} table (V1 baseline completed by V21,
 * PRD 13 and FR-011; issue #31, S7-02): one authorization grant — a
 * tester's right, granted through a tenant, to reach an application's
 * versions in one of four shapes.
 *
 * <p>The four FR-011 shapes share one row shape: {@code EXPLICIT_VERSION}
 * carries {@code versionId}, {@code APPLICATION} carries no target,
 * {@code TAG} carries {@code tag} (versions carrying the tag at
 * evaluation time — newly tagged versions join automatically), and
 * {@code LATEST_N} carries {@code latestN} (the N newest of the
 * application's working set by the V12 sort key). Exactly one target
 * column is set (database-checked); the per-shape pairing is the
 * service's contract, pinned by its validation and tests.
 *
 * <p>Grants are additive (union semantics — every contributor the
 * engine asks may answer, and overlapping grants compose); V1 has no
 * negative grants. {@code expiresAt} is enforced at evaluation time
 * only (null = never expires): an expired grant never authorizes, and
 * expiry is never swept — history lives in the audit trail, every
 * mutation of which carries actor + reason (issue #31 acceptance
 * criterion 7).
 *
 * <p>Tenant-scoped user data (ADR-0002): the row implements
 * {@link TenantScope} — the granting tenant owns it — so the endpoint
 * surface goes through the isolation seam
 * ({@link VersionAccessGrantScopedRepository}) and can never span
 * tenants. The engine's decision-time probe reads through the
 * module's {@link CrossTenantByDesign}-marked
 * {@link VersionAccessGrantCatalog} instead: an authorization input,
 * exactly like the role resolution the engine already does, and never
 * an endpoint data path.
 */
@Entity
@Table(name = "version_access")
public class VersionAccessGrantEntity implements TenantScope, Persistable<UUID> {

    /**
     * The FR-011 grant shapes (the four V21 {@code access_type}
     * values). {@code DIRECT} and {@code GROUP} are V1's speculative
     * sketch, kept in the database enum as reserved-unused (the V1
     * {@code DISABLED} precedent); group-shaped grants return with M2's
     * release targets.
     */
    public enum AccessType {
        EXPLICIT_VERSION,
        APPLICATION,
        TAG,
        LATEST_N,
        /** V1 sketch, reserved-unused (no writer). */
        @Deprecated
        DIRECT,
        /** V1 sketch, reserved-unused (no writer). */
        @Deprecated
        GROUP
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID tenantId;

    private UUID testerId;

    private UUID applicationId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    private AccessType accessType;

    private UUID versionId;

    private String tag;

    // The column name is explicit because the default naming strategy
    // underscores an uppercase letter only when a lowercase one follows
    // it — a trailing single-letter suffix would otherwise fold into
    // "latestn" (the sha256 precedent for named columns).
    @jakarta.persistence.Column(name = "latest_n")
    private Integer latestN;

    private Instant expiresAt;

    private UUID createdBy;

    private Instant createdAt;

    private Instant updatedAt;

    protected VersionAccessGrantEntity() {}

    /**
     * A new grant: the shape's target is carried by the matching
     * field; the service has validated the pairing before building
     * the row.
     */
    public VersionAccessGrantEntity(
            UUID tenantId,
            UUID testerId,
            UUID applicationId,
            AccessType accessType,
            UUID versionId,
            String tag,
            Integer latestN,
            Instant expiresAt,
            UUID createdBy) {
        this.tenantId = tenantId;
        this.testerId = testerId;
        this.applicationId = applicationId;
        this.accessType = accessType;
        this.versionId = versionId;
        this.tag = tag;
        this.latestN = latestN;
        this.expiresAt = expiresAt;
        this.createdBy = createdBy;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getTesterId() {
        return testerId;
    }

    public UUID getApplicationId() {
        return applicationId;
    }

    public AccessType getAccessType() {
        return accessType;
    }

    public UUID getVersionId() {
        return versionId;
    }

    public String getTag() {
        return tag;
    }

    public Integer getLatestN() {
        return latestN;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public UUID tenantId() {
        return tenantId;
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return createdAt == null;
    }
}
