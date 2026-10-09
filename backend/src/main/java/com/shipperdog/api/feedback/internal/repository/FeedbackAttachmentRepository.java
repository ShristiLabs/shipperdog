package com.shipperdog.api.feedback.internal.repository;

import com.shipperdog.api.feedback.internal.entity.FeedbackAttachmentEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Storage for the attachment metadata rows (V35). A plain
 * {@link JpaRepository} (the V25 child doctrine): the parent feedback
 * row is resolved — release-scoped and visibility-filtered — before
 * any attachment question is asked, so isolation is enforced at the
 * ancestors and never re-derived here.
 */
public interface FeedbackAttachmentRepository extends JpaRepository<FeedbackAttachmentEntity, UUID> {

    /** The report's attachments, name-ordered for the stable detail view. */
    List<FeedbackAttachmentEntity> findByFeedbackIdOrderByFileNameAsc(UUID feedbackId);

    /** The attachment by id within one report (the download route's parent-checked lookup). */
    Optional<FeedbackAttachmentEntity> findByIdAndFeedbackId(UUID id, UUID feedbackId);
}
