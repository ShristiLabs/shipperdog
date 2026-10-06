package com.shipperdog.api.tester.internal.repository;

import com.shipperdog.api.tester.internal.entity.TesterGroupMemberEntity;
import com.shipperdog.api.tester.internal.entity.TesterGroupMemberId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Plain repository over the {@code tester_group_members} junction
 * (issue #27): the table carries no tenant column — its tenant
 * dimension is inherited from the parent group (ADR-0002, eleventh
 * amendment) — so the seam cannot filter it directly and the entity is
 * not a {@code TenantScope} row. Isolation holds by construction
 * instead: the service reaches the junction only through a group the
 * seam already served in the request's tenant context (the
 * group-scoped queries), or through the organization-grain
 * groups-per-tester path whose tenant set the caller's permission
 * check established first (the tester index).
 *
 * <p>Both sides of the junction are index-backed for the E7 resolution
 * direction (PRD 8.9: rings and release targets resolve groups to
 * testers under a decision-latency SLO): the composite primary key's
 * {@code group_id} prefix serves the per-group queries below,
 * {@code idx_tgm_tester_id} serves the per-tester one.
 */
public interface TesterGroupMemberRepository extends JpaRepository<TesterGroupMemberEntity, TesterGroupMemberId> {

    /** The membership pair, for the idempotent add/remove pre-checks. */
    Optional<TesterGroupMemberEntity> findByGroupIdAndTesterId(UUID groupId, UUID testerId);

    /** A group's memberships in join order (PK prefix; the testers-per-group listing). */
    Page<TesterGroupMemberEntity> findByGroupIdOrderByCreatedAtAscTesterIdAsc(UUID groupId, Pageable pageable);

    /** A tester's memberships across all groups (tester index; groups-per-tester). */
    List<TesterGroupMemberEntity> findByTesterId(UUID testerId);

    /** A group's memberships, for the delete path's child cleanup. */
    List<TesterGroupMemberEntity> findByGroupId(UUID groupId);
}
