package com.shipperdog.api.feedback;

import java.time.Instant;
import java.util.UUID;

/**
 * One landed tester feedback report, published in-process (issue #46,
 * S11-01, FR-027, PRD 8.13): the event hook every later consumer of
 * the feedback stream subscribes to. S12-01's bus relays these onto
 * the messaging backbone when it lands; S11-02's triage composes on
 * the same facts without touching the feedback module again; the
 * release policy engine's OPEN_CRITICAL_BUGS metric (S10-06's
 * fail-closed seam, filled by S11-04) will count BUG reports that
 * began life as exactly this event.
 *
 * <p>Carried facts are the submission's complete self-contained
 * record (the {@code ReleasePromotedEvent} discipline): the report's
 * uuid, the release it evaluates with its quotable ref, the
 * application and version, the artifact when the tester named the
 * build they were looking at, the owning organization and the
 * SUBMITTING tenant (the release target the report came from — the
 * dimension the attachment prefix and the tenant filter key on), the
 * tester, the report type (the stable string vocabulary, never the
 * internal enum), how many attachments landed, and the occurrence
 * time. The description's content is deliberately absent — listeners
 * that need it read the report through the module's surface, and the
 * bus relays identifiers, not prose (PII minimization, PRD 8.13).
 *
 * <p>Published inside the committing transaction through Spring's
 * {@code ApplicationEventPublisher}: in-process listeners see the
 * report with the rows that produced it.
 */
public record FeedbackReceivedEvent(
        UUID feedbackId,
        UUID releaseId,
        String releaseRef,
        UUID applicationId,
        UUID versionId,
        UUID artifactId,
        UUID organizationId,
        UUID submitterTenantId,
        UUID testerId,
        String type,
        int attachmentCount,
        Instant occurredAt) {}
