package com.shipperdog.api.tester.internal.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

/**
 * Maps the {@code tester_group_members} junction (V1 baseline, PRD 13,
 * issue #27 / S6-02): a tester's membership in a group. The composite
 * {@code (group_id, tester_id)} primary key lets one tester join many
 * groups and many testers join one group while the same pair exists at
 * most once.
 *
 * <p>Deliberately NOT a {@link com.shipperdog.api.tenant.TenantScope}
 * row (ADR-0002, eleventh amendment): the table carries no tenant
 * column — its tenant dimension is inherited from the parent group, so
 * the seam cannot filter it directly. The service layer therefore
 * reaches members only THROUGH a group the seam already served in the
 * request's tenant context (load the group scoped, then address the
 * junction by that group's id), which keeps INV-001 by construction.
 * The tester side of the junction is served by
 * {@code idx_tgm_tester_id}.
 *
 * <p>Implements {@link Persistable} so Spring Data treats a
 * freshly-built membership (always an INSERT — the pair is new by
 * definition after the existence pre-check) correctly under the
 * UUID-free composite key.
 */
@Entity
@Table(name = "tester_group_members")
@IdClass(TesterGroupMemberId.class)
public class TesterGroupMemberEntity implements Persistable<TesterGroupMemberId> {

    @Id
    private UUID groupId;

    @Id
    private UUID testerId;

    private Instant createdAt;

    private Instant updatedAt;

    protected TesterGroupMemberEntity() {}

    public TesterGroupMemberEntity(UUID groupId, UUID testerId) {
        this.groupId = groupId;
        this.testerId = testerId;
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

    public UUID getGroupId() {
        return groupId;
    }

    public UUID getTesterId() {
        return testerId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public TesterGroupMemberId getId() {
        return new TesterGroupMemberId(groupId, testerId);
    }

    @Override
    public boolean isNew() {
        return createdAt == null;
    }
}
