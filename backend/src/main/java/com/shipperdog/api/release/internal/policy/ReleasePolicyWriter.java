package com.shipperdog.api.release.internal.policy;

import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventPublisher;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.release.PolicyCondition;
import com.shipperdog.api.release.ReleasePromotionPolicy;
import com.shipperdog.api.release.internal.policy.ReleasePolicyEngine.EngineResult;
import com.shipperdog.api.release.internal.policy.ReleasePolicyEngine.RuleOutcome;
import com.shipperdog.api.release.internal.policy.ReleasePolicyEvaluationEntity.Verdict;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The policy engine's transactional writer (issue #45, S10-06, the
 * {@code PromotionWriter} precedent): the promote attempt's governed
 * writes, as ONE transaction that stands alone — the 409 the same
 * attempt may answer rolls nothing of it back (the refused attempt
 * is a governed fact: its evaluation log and the PENDING holds it
 * produced must survive the refusal, exactly as the approval round
 * and the blocked-promotion record do).
 *
 * <p>The writer is deliberately a SEPARATE bean from {@link
 * ReleasePolicyEngine}: the engine is the seam's registrant, and a
 * registrant the promotion engine keys by {@code
 * getClass().getSimpleName()} must not become a transactional proxy
 * — the CGLIB class name would leak into the 409's {@code errors}
 * member as an unstable contributor key. The engine stays a plain
 * component; this writer carries the transactional boundary.
 */
@Component
public class ReleasePolicyWriter {

    private final ReleasePolicyRuleRepository rules;
    private final ReleasePolicyHoldRepository holds;
    private final ReleasePolicyEvaluationRepository evaluations;
    private final ReleasePolicyEvaluationRuleRepository evaluationRules;
    private final AuditEventPublisher audit;
    private final Clock clock;

    ReleasePolicyWriter(
            ReleasePolicyRuleRepository rules,
            ReleasePolicyHoldRepository holds,
            ReleasePolicyEvaluationRepository evaluations,
            ReleasePolicyEvaluationRuleRepository evaluationRules,
            AuditEventPublisher audit,
            Clock clock) {
        this.rules = rules;
        this.holds = holds;
        this.evaluations = evaluations;
        this.evaluationRules = evaluationRules;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Records one promote attempt's evaluation (AC5) — ONE
     * transaction: the evaluation row, every per-rule outcome row in
     * the walked order, and the PENDING holds the fired HOLD rules
     * demand (a lost concurrent insert of the same triple re-reads
     * the winner's row — the (release, rule, ring) hold is one fact,
     * never two), with the audit summary beside.
     */
    @Transactional
    public void recordEvaluation(ReleasePromotionPolicy.PromotionCheck check, EngineResult result) {
        Instant occurredAt = Instant.now(clock);
        ReleasePolicyEvaluationEntity evaluation = evaluations.saveAndFlush(new ReleasePolicyEvaluationEntity(
                check.releaseId(),
                check.organizationId(),
                result.ring(),
                result.verdict(),
                result.winningRuleId(),
                check.actorId(),
                occurredAt));
        for (RuleOutcome outcome : result.outcomes()) {
            evaluationRules.save(new ReleasePolicyEvaluationRuleEntity(
                    evaluation.getId(),
                    outcome.ruleId(),
                    outcome.ruleName(),
                    outcome.position(),
                    outcome.action(),
                    outcome.outcome().name(),
                    bounded(outcome.detail())));
        }
        for (RuleOutcome hold : result.pendingHolds()) {
            if (hold.holdId() != null) {
                continue;
            }
            ReleasePolicyRuleEntity rule = rules.findById(hold.ruleId()).orElse(null);
            if (rule == null) {
                continue;
            }
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put(
                    "condition",
                    PolicyCondition.parse(rule.getCondition(), "condition", new ArrayList<>()) == null
                            ? "unreadable"
                            : rule.getCondition().toString());
            evidence.put("detail", bounded(hold.detail()));
            evidence.put("fromRing", check.fromRing());
            try {
                holds.saveAndFlush(new ReleasePolicyHoldEntity(
                        check.releaseId(), rule.getId(), check.organizationId(), result.ring(), evidence));
            } catch (DataIntegrityViolationException lostRace) {
                // A concurrent attempt recorded the same (release,
                // rule, ring) hold first: one fact, never two.
                holds.findByReleaseIdAndRuleIdAndRing(check.releaseId(), rule.getId(), result.ring());
            }
        }
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("ring", result.ring());
        detail.put("verdict", result.verdict().name());
        List<String> fired = new ArrayList<>();
        for (RuleOutcome outcome : result.outcomes()) {
            if (outcome.fires()) {
                fired.add(outcome.ruleName() + ":" + outcome.action());
            }
        }
        detail.put("fired", fired.isEmpty() ? "-" : String.join(",", fired));
        detail.put("evaluated", String.valueOf(result.outcomes().size()));
        audit.publish(AuditEvent.builder(AuditEventType.RELEASE_POLICY_EVALUATED)
                .occurredAt(occurredAt)
                .userId(check.actorId())
                .orgId(check.organizationId())
                .applicationId(check.applicationId())
                .success(result.verdict() == Verdict.ALLOWED)
                .detail(detail)
                .build());
    }

    private static String bounded(String detail) {
        if (detail == null) {
            return null;
        }
        return detail.length() > 1024 ? detail.substring(0, 1021) + "..." : detail;
    }
}
