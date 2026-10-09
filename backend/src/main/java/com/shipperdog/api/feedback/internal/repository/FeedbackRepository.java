package com.shipperdog.api.feedback.internal.repository;

import com.shipperdog.api.feedback.internal.entity.FeedbackEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Storage for the feedback rows (V1's table, expanded by V35). A
 * plain {@link JpaRepository} (the V25 child doctrine): every console
 * read resolves the parent release through the release module's
 * scoped port first, so isolation is enforced at the parent and never
 * re-derived here. The listing's conjunctive filters (type, status,
 * submitter tenant) arrive as {@code Specification} parameters through
 * {@link JpaSpecificationExecutor} — composed beside the
 * release-grain scope the service already established, never instead
 * of it.
 */
public interface FeedbackRepository
        extends JpaRepository<FeedbackEntity, UUID>, JpaSpecificationExecutor<FeedbackEntity> {

    /** The report by id (the service has already resolved and scoped the parent release). */
    Optional<FeedbackEntity> findByIdAndReleaseId(UUID id, UUID releaseId);
}
