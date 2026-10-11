package com.shipperdog.api.release.internal.service;

import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventPublisher;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.release.ReleaseDistributionFailures;
import com.shipperdog.api.release.ReleaseTransitionedEvent;
import com.shipperdog.api.release.internal.entity.ReleaseEntity;
import com.shipperdog.api.release.internal.entity.ReleaseStatusHistoryEntity;
import com.shipperdog.api.release.internal.repository.ReleaseRegistryRepository;
import com.shipperdog.api.release.internal.repository.ReleaseStatusHistoryRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The S13-03 failure crossing (issue #63, AC4, PRD 17): the
 * system-driven DEPLOYING to FAILED edge a refused production
 * submission lands a release on. The {@link ReleaseDistributionFailures}
 * port's implementation: the same CAS, timeline row, audit, and event
 * ceremony the operator-driven transitions run (the state machine's
 * table already legalizes the edge), with NO actor — the store
 * refused the submission; nobody chose this crossing — and the
 * provider's actionable reason on the timeline row the history view
 * and the dashboard render.
 *
 * <p>The caller is the distribution module's submission-failure
 * recorder, which runs AFTER the vetoed ACTIVE crossing's transaction
 * rolled back (the release is back on DEPLOYING when this runs, so
 * the edge is legal again); this service never needs to race the
 * crossing itself. Refuse-to-lose, never force: a release that moved
 * on concurrently (an operator crossed it first) keeps its own state
 * and the refusal is logged — the store's verdict about a submission
 * never overrides a person's decision about a release.
 */
@Service
public class ReleaseFailureService implements ReleaseDistributionFailures {

    private static final Logger log = LoggerFactory.getLogger(ReleaseFailureService.class);

    private final ReleaseRegistryRepository releases;
    private final ReleaseStatusHistoryRepository statusHistory;
    private final AuditEventPublisher audit;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    ReleaseFailureService(
            ReleaseRegistryRepository releases,
            ReleaseStatusHistoryRepository statusHistory,
            AuditEventPublisher audit,
            ApplicationEventPublisher events,
            Clock clock) {
        this.releases = releases;
        this.statusHistory = statusHistory;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void failFromDeploying(UUID releaseId, String reason, UUID actorId) {
        // The registry probe, not the scoped repository's findById:
        // the aftermath runs with no tenant context bound, and the
        // scoped read would refuse exactly the crossing it records.
        ReleaseEntity release = releases.probeById(releaseId).orElse(null);
        if (release == null) {
            log.warn("the submission failure crossing found no release {}", releaseId);
            return;
        }
        String honestReason = reason == null || reason.isBlank() ? "the store refused the submission" : reason;
        if (statusHistory.transitionStatusIfCurrent(release.getId(), "DEPLOYING", "FAILED") != 1) {
            // Refuse-to-lose: the release left DEPLOYING before this
            // crossing ran. The store's verdict never forces a state.
            log.warn(
                    "the submission failure crossing skipped release {}: not in DEPLOYING anymore",
                    release.releaseRef());
            return;
        }
        Instant occurredAt = Instant.now(clock);
        // The timeline names a person always: the operator whose
        // crossing the refusal answered, the release's creator when a
        // system-driven crossing has no actor of its own.
        UUID actor = actorId != null ? actorId : release.getCreatedBy();
        statusHistory.save(new ReleaseStatusHistoryEntity(
                release.getId(),
                ReleaseEntity.Status.DEPLOYING,
                ReleaseEntity.Status.FAILED,
                honestReason,
                actor,
                occurredAt));
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("releaseRef", release.releaseRef());
        detail.put("fromStatus", "DEPLOYING");
        detail.put("toStatus", "FAILED");
        detail.put("reason", honestReason);
        audit.publish(AuditEvent.builder(AuditEventType.RELEASE_TRANSITIONED)
                .occurredAt(occurredAt)
                .orgId(release.getOrganizationId())
                .tenantId(release.getTenantId())
                .applicationId(release.getApplicationId())
                .versionId(release.getVersionId())
                .userId(actor)
                .success(true)
                .detail(detail)
                .build());
        events.publishEvent(new ReleaseTransitionedEvent(
                release.getId(),
                release.releaseRef(),
                release.getApplicationId(),
                release.getVersionId(),
                release.getOrganizationId(),
                release.getTenantId(),
                "DEPLOYING",
                "FAILED",
                honestReason,
                actor,
                occurredAt));
    }
}
