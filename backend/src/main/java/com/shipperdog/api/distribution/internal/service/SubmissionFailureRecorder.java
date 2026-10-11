package com.shipperdog.api.distribution.internal.service;

import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.audit.AuditLogger;
import com.shipperdog.api.distribution.internal.entity.ChannelDistributionEntity;
import com.shipperdog.api.distribution.internal.repository.ChannelDistributionRepository;
import com.shipperdog.api.release.ReleaseDistributionFailures;
import com.shipperdog.api.release.ReleaseTransitionedEvent;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The vetoed submission's aftermath (issue #63, S13-03, AC4): the
 * fresh-transaction recorder the dispatch listener registers before it
 * throws to roll the ACTIVE crossing back. Once the rollback landed,
 * THIS service writes what the crossing would have kept — the FAILED
 * distribution records with the taxonomy code AND the provider's
 * actionable reason (the {@code failure_detail} the console renders),
 * the dispatch audit line — and carries the release across PRD 17's
 * DEPLOYING to FAILED edge with the reason on the timeline row
 * (through the {@link ReleaseDistributionFailures} port).
 *
 * <p>Every leg is contained: a recorder fault is logged, never
 * rethrown — the operator's transition request already answered with
 * the provider's problem, and the aftermath must not convert a
 * recorded failure into a lost one. The aftermath runs outside every
 * bound scope the crossing ran in, so the CALLER captures the
 * artifact slots at veto time; this service reads nothing scoped.
 */
@Service
public class SubmissionFailureRecorder {

    private static final Logger log = LoggerFactory.getLogger(SubmissionFailureRecorder.class);

    private final ChannelDistributionRepository records;
    private final ReleaseDistributionFailures failures;
    private final AuditLogger audit;
    private final Clock clock;

    SubmissionFailureRecorder(
            ChannelDistributionRepository records,
            ReleaseDistributionFailures failures,
            AuditLogger audit,
            Clock clock) {
        this.records = records;
        this.failures = failures;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Records the refused submission's aftermath in its own
     * transaction: FAILED records with the actionable detail, the
     * dispatch audit, and the release's DEPLOYING to FAILED crossing.
     *
     * @param artifactIds the release's artifact slots, captured by the caller inside the crossing
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(
            ReleaseTransitionedEvent event,
            String channel,
            List<UUID> artifactIds,
            String failureCode,
            String failureDetail) {
        try {
            Instant now = Instant.now(clock);
            List<ChannelDistributionEntity> rows = new ArrayList<>();
            for (UUID artifactId : artifactIds) {
                rows.add(records.findByReleaseIdAndArtifactIdAndChannel(event.releaseId(), artifactId, channel)
                        .orElseGet(() -> new ChannelDistributionEntity(
                                event.releaseId(), artifactId, event.organizationId(), channel)));
            }
            rows.forEach(row ->
                    row.report(ChannelDistributionEntity.AdapterReport.failed(failureCode, failureDetail), 0, now));
            records.saveAll(rows);
            Map<String, String> detail = new LinkedHashMap<>();
            detail.put("channel", channel);
            detail.put("outcome", failureCode);
            detail.put("reason", failureDetail == null ? "" : failureDetail);
            detail.put("releaseRef", event.releaseRef() == null ? "" : event.releaseRef());
            audit.record(AuditEvent.builder(AuditEventType.DISTRIBUTION_DISPATCHED)
                    .orgId(event.organizationId())
                    .applicationId(event.applicationId())
                    .versionId(event.versionId())
                    .detail(detail)
                    .success(false)
                    .build());
            failures.failFromDeploying(
                    event.releaseId(),
                    "the store refused the submission: "
                            + (failureDetail == null || failureDetail.isBlank() ? failureCode : failureDetail),
                    event.actorId());
        } catch (RuntimeException e) {
            // Containment: the aftermath is best-effort after the
            // operator's answer already carried the provider's problem.
            log.error("the refused submission's aftermath could not be recorded for release {}", event.releaseRef(), e);
        }
    }
}
