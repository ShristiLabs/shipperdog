package com.shipperdog.api.workflow.internal.repository;

import com.shipperdog.api.workflow.internal.entity.ReleaseApprovalEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Storage for the per-step approval records (V28, issue #40, S10-01,
 * PRD 13): the signatures. A plain {@link JpaRepository} — a pure
 * child of its round, which is a pure child of the seam-served release
 * (the V25 doctrine applied one level deeper): callers resolve the
 * release first, then the round, then read exactly that round's steps.
 *
 * <p>The signature's compare-and-set lives here (the standing CAS
 * doctrine): a step is decided exactly once — PENDING to APPROVED or
 * REJECTED only while still PENDING — so racing approvers can never
 * double-sign or overwrite a rejection, whatever order the database
 * interleaves them under READ COMMITTED.
 */
public interface ReleaseApprovalRepository extends JpaRepository<ReleaseApprovalEntity, UUID> {

    /** One round's steps, in chain order (position ascending). */
    List<ReleaseApprovalEntity> findByRoundIdOrderByPositionAsc(UUID roundId);

    /**
     * The decision's compare-and-set (the V14 discipline): records the
     * signature and moves the step to {@code to} only while its
     * committed status is still PENDING — 1 when this call decided the
     * step, 0 when a concurrent decision (or a terminal round) moved
     * it first. The only writer after instantiation: a decided row is
     * terminal forever.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            value = "update release_approvals set status = cast(:to as approval_status), "
                    + "approver = :approverId, approver_role = cast(:approverRole as approval_step_role), "
                    + "reason = :reason, decided_at = :decidedAt, updated_at = now() "
                    + "where id = :approvalId and status = 'PENDING'",
            nativeQuery = true)
    int decideIfCurrent(
            @Param("approvalId") UUID approvalId,
            @Param("to") String to,
            @Param("approverId") UUID approverId,
            @Param("approverRole") String approverRole,
            @Param("reason") String reason,
            @Param("decidedAt") java.time.Instant decidedAt);
}
