package com.shipperdog.api.tester.internal.entity;

import com.shipperdog.api.tenant.TenantScope;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

/**
 * Maps the {@code tenant_tester_memberships} table (V1 baseline, PRD 13
 * and 8.8; issue #28 / S6-03): which testers belong to which tenant,
 * with status — the first layer the E7 authorization chain evaluates.
 * The composite {@code (tenant_id, tester_id)} primary key lets one
 * tester belong to many tenants and many testers belong to one tenant
 * while the same pair exists at most once.
 *
 * <p>Tenant-scoped user data (ADR-0002, tenth amendment's reserved
 * landing — unlike {@link TesterGroupMemberEntity} this junction
 * carries its OWN tenant column, so it joins the isolation seam
 * directly, the eleventh amendment's via-parent rule deliberately not
 * applied): the entity implements {@link TenantScope}, its endpoint
 * repository extends the seam, and every read and write of a
 * membership row runs under the request's bound tenant context.
 *
 * <p>The status switch (V20, the V8 tenant_member_status vocabulary
 * precedent): ACTIVE is effective, SUSPENDED parks the membership
 * reversibly without unlinking the tester (revocation semantics stay
 * S6-04's), REVOKED is removal — the row is NEVER deleted, because
 * removal must preserve history (issue #28, acceptance criterion 4);
 * a re-link reactivates the retained row. Membership status is
 * independent of the tester person record's status (acceptance
 * criterion 1): effective access needs both switches on
 * {@code ACTIVE} (acceptance criterion 2).
 *
 * <p>Implements {@link Persistable} so Spring Data treats a
 * freshly-built membership (always an INSERT — the pair is new by
 * definition after the existence pre-check) correctly under the
 * UUID-free composite key.
 */
@Entity
@Table(name = "tenant_tester_memberships")
@IdClass(TenantTesterMembershipId.class)
public class TenantTesterMembershipEntity implements TenantScope, Persistable<TenantTesterMembershipId> {

    /** The membership switch (V20): see class documentation. */
    public enum Status {
        ACTIVE,
        SUSPENDED,
        REVOKED
    }

    @Id
    private UUID tenantId;

    @Id
    private UUID testerId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    private Status status;

    private Instant createdAt;

    private Instant updatedAt;

    protected TenantTesterMembershipEntity() {}

    public TenantTesterMembershipEntity(UUID tenantId, UUID testerId) {
        this.tenantId = tenantId;
        this.testerId = testerId;
        this.status = Status.ACTIVE;
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

    @Override
    public UUID tenantId() {
        return tenantId;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public TenantTesterMembershipId getId() {
        return new TenantTesterMembershipId(tenantId, testerId);
    }

    @Override
    public boolean isNew() {
        return createdAt == null;
    }
}
