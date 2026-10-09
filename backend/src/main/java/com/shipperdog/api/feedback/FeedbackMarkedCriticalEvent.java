package com.shipperdog.api.feedback;

import java.time.Instant;
import java.util.UUID;

/**
 * One report became CRITICAL (issue #47, S11-02, FR-027, PRD 8.13):
 * published in-process the moment triage classifies a report at
 * CRITICAL severity — the quality signal AC5 turns into a
 * notification for the subscribed roles, and the fact the
 * open-critical-bugs metric counts from then on. The {@code
 * FeedbackReceivedEvent} discipline (S12-01's bus relays these when
 * it lands): self-contained facts, identifiers not prose, and the
 * description's content deliberately absent — listeners that need it
 * read the report through the module's surface under their own
 * authority.
 *
 * <p>Published inside the committing transaction through Spring's
 * {@code ApplicationEventPublisher}: in-process listeners (the
 * notification consumer) see the classification with the row that
 * produced it. Re-classifying a report back to CRITICAL after a
 * downgrade publishes again — every becoming-critical is the fact;
 * consumers deduplicate on their own side.
 */
public record FeedbackMarkedCriticalEvent(
        UUID feedbackId,
        UUID releaseId,
        String releaseRef,
        UUID applicationId,
        UUID organizationId,
        UUID submitterTenantId,
        UUID testerId,
        String type,
        Instant occurredAt) {}
