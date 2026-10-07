package com.shipperdog.api.release.internal.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Maps the {@code release_target_groups} table (V24): the tester-group
 * selection of one release target — the "tenants + tester groups" of the
 * creation API (FR-015). An empty group set targets the tenant at large;
 * groups narrow it.
 *
 * <p>A plain child of {@code release_targets} under the via-parent rule
 * (the eleventh amendment's junction doctrine): the composite foreign key
 * to the parent's composite primary key makes every row reachable only
 * through the target it hangs off, which itself hangs off the
 * seam-served release — no snapshot columns, no seam repository, no
 * route of its own.
 */
@Entity
@Table(name = "release_target_groups")
@IdClass(ReleaseTargetGroupId.class)
public class ReleaseTargetGroupEntity {

    @Id
    private UUID releaseId;

    @Id
    private UUID tenantId;

    @Id
    private UUID groupId;

    private Instant createdAt;

    protected ReleaseTargetGroupEntity() {}

    /** One group of one target of a new release (S9-01). */
    public ReleaseTargetGroupEntity(UUID releaseId, UUID tenantId, UUID groupId) {
        this.releaseId = releaseId;
        this.tenantId = tenantId;
        this.groupId = groupId;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public UUID getReleaseId() {
        return releaseId;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getGroupId() {
        return groupId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
