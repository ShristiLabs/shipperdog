package com.shipperdog.api.distribution.internal.service;

import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.audit.AuditLogger;
import com.shipperdog.api.distribution.internal.entity.StagedRolloutEventEntity;
import com.shipperdog.api.distribution.internal.entity.StagedRolloutPlanEntity;
import com.shipperdog.api.distribution.internal.repository.StagedRolloutEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The staged rollout's refusal recorder (issue #66, S13-06, AC2 —
 * the #45 discipline the policy consult established): a refused
 * advancement's evidence — the STAGE_BLOCKED timeline row and the
 * audit line — commits in its OWN transaction, so it survives the
 * 409's rollback exactly a refused promotion's evaluation does. The
 * refusal is loud and durable, never a silent stall the operator
 * must reconstruct from logs.
 */
@Service
public class StagedRolloutBlockRecorder {

    private final StagedRolloutEventRepository timeline;
    private final AuditLogger audit;
    private final Clock clock;

    public StagedRolloutBlockRecorder(StagedRolloutEventRepository timeline, AuditLogger audit, Clock clock) {
        this.timeline = timeline;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Records one refusal: the stage the widening would have entered,
     * the refusal's reason (the policy rule's reference and evidence,
     * or the failed dispatches' codes), and the actor.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordBlocked(StagedRolloutPlanEntity plan, int stagePosition, String reason, UUID actorId) {
        String bounded = reason == null || reason.length() <= 512 ? reason : reason.substring(0, 512);
        timeline.save(new StagedRolloutEventEntity(
                plan.getId(),
                StagedRolloutEventEntity.Action.STAGE_BLOCKED,
                stagePosition,
                null,
                bounded,
                actorId,
                Instant.now(clock)));
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("releaseId", plan.getReleaseId().toString());
        detail.put("action", "blocked");
        detail.put("stage", String.valueOf(stagePosition));
        detail.put("reason", bounded == null || bounded.length() <= 380 ? bounded : bounded.substring(0, 380));
        AuditEvent.Builder line = AuditEvent.builder(AuditEventType.STAGED_ROLLOUT_BLOCKED)
                .orgId(plan.getOrganizationId())
                .applicationId(plan.getApplicationId())
                .detail(detail)
                .success(false);
        if (actorId != null) {
            line.userId(actorId);
        }
        audit.record(line.build());
    }
}
