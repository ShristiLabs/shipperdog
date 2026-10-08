package com.shipperdog.api.workflow.internal.service;

import com.shipperdog.api.release.ReleaseTransitionPolicy;
import com.shipperdog.api.workflow.internal.entity.ApprovalWorkflowStepEntity.GroupMode;
import com.shipperdog.api.workflow.internal.entity.ReleaseApprovalEntity;
import com.shipperdog.api.workflow.internal.entity.ReleaseApprovalRoundEntity;
import com.shipperdog.api.workflow.internal.repository.ReleaseApprovalRepository;
import com.shipperdog.api.workflow.internal.repository.ReleaseApprovalRoundRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The approval-completion policy (issue #40, S10-01, AC4; the group
 * semantics since #41, S10-02): the first registered
 * {@link ReleaseTransitionPolicy} contributor — the seam's javadoc
 * named this exact registration ("E10/S10-01's approval evaluation
 * ... the crossing APPROVAL_PENDING to APPROVED"). The crossing is
 * denied while the release's latest approval round is PENDING (the
 * chain has not finished) or REJECTED (progression is blocked — the
 * rework path is a new round, never an un-reject), and permitted when
 * the round is SATISFIED — every group satisfied per its mode (ALL:
 * every step signed; ANY: one signature recorded, siblings skipped;
 * the chain FR-016 demanded) — or when NO round exists: approval
 * workflows are CONFIGURABLE, and an organization that defined none
 * keeps the ungated machine S9-02 shipped (a seam that denied by
 * default would block the machine this story exists to build).
 *
 * <p>Reads run inside the transition's committing transaction (the
 * seam consults every contributor before the CAS commits): the round
 * statuses this policy sees are the committed facts the crossing
 * serializes against. A definition created after a release crossed
 * into APPROVAL_PENDING never retro-opens its gate — the workflow
 * applies from the crossing that instantiated its round, the recorded
 * resolution.
 */
@Component
public class ReleaseApprovalPolicy implements ReleaseTransitionPolicy {

    private final ReleaseApprovalRoundRepository rounds;
    private final ReleaseApprovalRepository approvals;

    ReleaseApprovalPolicy(ReleaseApprovalRoundRepository rounds, ReleaseApprovalRepository approvals) {
        this.rounds = rounds;
        this.approvals = approvals;
    }

    /**
     * Permits every crossing except APPROVAL_PENDING to APPROVED,
     * where the latest round must be SATISFIED (or absent). The
     * denial is the human-readable refusal rendered in the 422's
     * {@code errors} member keyed by this contributor's name — naming
     * the round, its status, and the step the chain waits on, so the
     * operator reading the 422 knows exactly what is missing.
     */
    @Override
    public Optional<String> denial(TransitionCheck check) {
        if (!"APPROVED".equals(check.toStatus())) {
            return Optional.empty();
        }
        if (!"APPROVAL_PENDING".equals(check.fromStatus())) {
            // Only APPROVAL_PENDING to APPROVED is legal for this
            // target; the machine's own table answers anything else.
            return Optional.empty();
        }
        ReleaseApprovalRoundEntity round = rounds.findFirstByReleaseIdOrderByRoundNumberDesc(check.releaseId())
                .orElse(null);
        if (round == null) {
            // No workflow resolved at the crossing: approvals were not
            // configured for this release, and the crossing is ungated.
            return Optional.empty();
        }
        return switch (round.getStatus()) {
            case SATISFIED -> Optional.empty();
            case REJECTED ->
                Optional.of("approval round " + round.getRoundNumber()
                        + " was rejected; restart approvals after rework before crossing to APPROVED");
            case PENDING -> Optional.of(pendingDenial(check.releaseId(), round));
        };
    }

    /**
     * The pending chain's refusal: what the round waits on, per the
     * evaluator. A single waiting step keeps the S10-01 wording
     * verbatim ("awaits step N of M (TYPE)" — every sequential round
     * reads exactly as before); a parallel group's simultaneous
     * demands name the group, its mode, and every waiting step (the
     * dashboard's waiting-on list, the policy's own).
     */
    private String pendingDenial(UUID releaseId, ReleaseApprovalRoundEntity round) {
        List<ReleaseApprovalEntity> chain = approvals.findByRoundIdOrderByPositionAsc(round.getId());
        ApprovalRoundEvaluator.Evaluation evaluation = ApprovalRoundEvaluator.evaluate(
                chain.stream().map(ApprovalRoundEvaluator.Step::of).toList());
        List<ReleaseApprovalEntity> waiting = chain.stream()
                .filter(step -> evaluation.decidablePositions().contains(step.getPosition()))
                .toList();
        if (waiting.isEmpty()) {
            // A PENDING round with no decidable step is mid-decision
            // (the last decision's terminal crossing has not committed
            // yet) — the serialized answer.
            return "approval round " + round.getRoundNumber() + " is completing";
        }
        if (waiting.size() == 1) {
            ReleaseApprovalEntity step = waiting.get(0);
            return "approval round " + round.getRoundNumber() + " awaits step " + step.getPosition() + " of "
                    + chain.size() + " (" + step.getApprovalType().name() + ")";
        }
        String demands = waiting.stream()
                .map(step -> step.getApprovalType().name())
                .toList()
                .toString();
        String modeNote = evaluation.activeGroupMode() == GroupMode.ANY
                ? "ANY, first approval satisfies"
                : "ALL, every step required";
        return "approval round " + round.getRoundNumber() + " awaits group " + evaluation.activeGroupPosition() + " ("
                + modeNote + "): " + demands;
    }
}
