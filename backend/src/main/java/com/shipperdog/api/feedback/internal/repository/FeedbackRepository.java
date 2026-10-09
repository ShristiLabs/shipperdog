package com.shipperdog.api.feedback.internal.repository;

import com.shipperdog.api.feedback.internal.entity.FeedbackEntity;
import com.shipperdog.api.feedback.internal.entity.FeedbackEntity.Severity;
import com.shipperdog.api.feedback.internal.entity.FeedbackEntity.Status;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Storage for the feedback rows (V1's table, expanded by V35 and
 * V36). A plain {@link JpaRepository} (the V25 child doctrine): every
 * console read resolves the parent release through the release
 * module's scoped port first, so isolation is enforced at the parent
 * and never re-derived here. The listing's conjunctive filters (type,
 * status, severity, submitter tenant) arrive as {@code Specification}
 * parameters through {@link JpaSpecificationExecutor} — composed
 * beside the release-grain scope the service already established,
 * never instead of it.
 */
public interface FeedbackRepository
        extends JpaRepository<FeedbackEntity, UUID>, JpaSpecificationExecutor<FeedbackEntity> {

    /** The report by id (the service has already resolved and scoped the parent release). */
    Optional<FeedbackEntity> findByIdAndReleaseId(UUID id, UUID releaseId);

    /**
     * The open-critical-bugs metric (S11-02, FR-026/FR-027): the
     * release's count of unresolved CRITICAL reports — the real-time
     * query the issue's technical note names, read by the gate and
     * policy seams at evaluation time. A definitive answer for every
     * release (zero when none), never an "unknown".
     */
    long countByReleaseIdAndSeverityAndStatusIn(UUID releaseId, Severity severity, Collection<Status> statuses);

    /**
     * The release-dashboard rollup's source (S11-02, PRD 32): counts
     * grouped by severity, status, and type over the release grain.
     * The type member lets the service apply the SECURITY_ISSUE
     * restricted visibility per reader before folding — the rollup a
     * reader sees counts exactly the rows that reader can see.
     */
    @Query("select f.severity, f.status, f.type, count(f) from FeedbackEntity f "
            + "where f.releaseId = :releaseId group by f.severity, f.status, f.type")
    List<Object[]> rollupByReleaseId(@Param("releaseId") UUID releaseId);
}
