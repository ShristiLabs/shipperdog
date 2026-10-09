package com.shipperdog.api.release.internal.policy;

import com.shipperdog.api.gate.ReleaseGateEvaluation;
import com.shipperdog.api.gate.ReleaseGateEvaluation.Evaluation.GateState;
import com.shipperdog.api.release.PolicyCondition;
import com.shipperdog.api.release.PolicyCondition.AllOf;
import com.shipperdog.api.release.PolicyCondition.AnyOf;
import com.shipperdog.api.release.PolicyCondition.ApprovalCondition;
import com.shipperdog.api.release.PolicyCondition.ArtifactCondition;
import com.shipperdog.api.release.PolicyCondition.GateCondition;
import com.shipperdog.api.release.PolicyCondition.MetricCondition;
import com.shipperdog.api.release.PolicyMetric;
import com.shipperdog.api.release.PromotionApprovalSource;
import com.shipperdog.api.release.ReleasePolicyMetricSource;
import com.shipperdog.api.release.ReleasePromotionPolicy;
import com.shipperdog.api.release.internal.entity.ReleaseArtifactEntity;
import com.shipperdog.api.release.internal.entity.ReleaseEntity;
import com.shipperdog.api.release.internal.entity.RingEntity;
import com.shipperdog.api.release.internal.policy.ReleasePolicyEvaluationEntity.Verdict;
import com.shipperdog.api.release.internal.policy.ReleasePolicyHoldEntity.Status;
import com.shipperdog.api.release.internal.policy.ReleasePolicyRuleEntity.Action;
import com.shipperdog.api.release.internal.repository.ReleaseArtifactRepository;
import com.shipperdog.api.release.internal.repository.ReleaseScopedRepository;
import com.shipperdog.api.release.internal.repository.RingRepository;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * The declarative policy engine (issue #45, S10-06, PRD 33, FR-026's
 * fail-closed ethos): the FIRST registered {@link
 * ReleasePromotionPolicy} contributor — standing BLOCK and HOLD rules
 * authored as data, evaluated deterministically at the promotion
 * precheck, logged per attempt. The engine also carries the
 * skip-relaxation knob: the organization's named skip allowances are
 * the seam's documented widening path (#44's default posture stands
 * for every organization that never edits them).
 *
 * <p><strong>The decision (the issue's ACs).</strong> Rules evaluate
 * in the defined order — position, then name — and every evaluated
 * rule logs its outcome: {@code NOT_MET}, {@code FIRED}, {@code
 * NO_DATA} (the explicit fail-closed outcome of a metric condition
 * analytics cannot answer, AC4), or {@code ACKNOWLEDGED} (a fired
 * HOLD suppressed by a standing acknowledge). The FIRST BLOCK rule to
 * fire wins (AC5): the promotion 409 carries its reference and
 * evidence. A fired HOLD that no privileged actor has acknowledged
 * holds the hop the same 409 way (AC2) — the acknowledge is its own
 * audited act, and the acknowledged decision stands for later hops of
 * the same release into the same ring. An ALLOW rule evaluates and
 * logs but decides nothing in v1: it is the documented seam V3's
 * automated promotion will act on, deliberately not built here.
 *
 * <p><strong>One evaluation, three surfaces, one log row</strong>
 * (the #44 discipline): the precheck read and the hop's
 * in-transaction revalidation run this evaluation as PURE reads; only
 * the promote ATTEMPT writes — the evaluation row, its per-rule
 * outcomes, and the new PENDING holds, in ONE transaction whose
 * facts survive the 409 the same attempt may answer (the approval
 * round precedent: the refused attempt is a governed fact, not a
 * discarded one).
 *
 * <p><strong>Fail closed, always.</strong> A rule whose stored
 * condition cannot be parsed (impossible through the write surface's
 * validation; reachable only by storage drift) evaluates as FIRED —
 * a rule the engine cannot answer never silently permits. A metric
 * with no data is never a zero and never a pass. An absent gate
 * never fires a failure condition (its subject is not part of the
 * ring's evaluation — the absence is named, not guessed at).
 */
@Component
public class ReleasePolicyEngine implements ReleasePromotionPolicy {

    /** One rule's evaluated standing. */
    enum Outcome {
        NOT_MET,
        FIRED,
        NO_DATA,
        ACKNOWLEDGED
    }

    /** The three-valued truth a condition tree evaluates to (NO_DATA is the fail-closed dominant). */
    private enum Truth {
        TRUE,
        FALSE,
        NO_DATA
    }

    /** The engine's complete answer for one hop: the verdict, the winning rule, every outcome, the pending holds. */
    record EngineResult(
            String ring,
            Verdict verdict,
            UUID winningRuleId,
            String winningRuleName,
            List<RuleOutcome> outcomes,
            List<RuleOutcome> pendingHolds) {}

    /** One rule's outcome with its evidence detail. */
    record RuleOutcome(
            UUID ruleId, String ruleName, int position, String action, Outcome outcome, String detail, UUID holdId) {

        boolean fires() {
            return outcome == Outcome.FIRED || outcome == Outcome.NO_DATA;
        }
    }

    private final ReleasePolicyRuleRepository rules;
    private final ReleasePolicyHoldRepository holds;
    private final ReleasePolicySettingsRepository settings;
    private final ReleaseGateEvaluation gateEvaluation;
    private final PromotionApprovalSource approvalLeg;
    private final ObjectProvider<ReleasePolicyMetricSource> metricSources;
    private final ReleaseArtifactRepository artifacts;
    private final ReleaseScopedRepository releases;
    private final RingRepository rings;
    private final ReleasePolicyWriter writer;

    ReleasePolicyEngine(
            ReleasePolicyRuleRepository rules,
            ReleasePolicyHoldRepository holds,
            ReleasePolicySettingsRepository settings,
            ReleaseGateEvaluation gateEvaluation,
            PromotionApprovalSource approvalLeg,
            ObjectProvider<ReleasePolicyMetricSource> metricSources,
            ReleaseArtifactRepository artifacts,
            ReleaseScopedRepository releases,
            RingRepository rings,
            ReleasePolicyWriter writer) {
        this.rules = rules;
        this.holds = holds;
        this.settings = settings;
        this.gateEvaluation = gateEvaluation;
        this.approvalLeg = approvalLeg;
        this.metricSources = metricSources;
        this.artifacts = artifacts;
        this.releases = releases;
        this.rings = rings;
        this.writer = writer;
    }

    // -------------------------------------------------------------------------
    // The seam: the promotion consult and the skip admission
    // -------------------------------------------------------------------------

    /**
     * The promotion precheck's consult (AC2): evaluates every enabled
     * rule scoped to the hop, in the defined order, and answers the
     * refusal the 409 renders — the first BLOCK rule's reference and
     * evidence, the pending holds beside it. On the ATTEMPT surface
     * the evaluation logs first (one row, its per-rule outcomes, the
     * new PENDING holds — a transaction the refusal's rollback never
     * touches).
     */
    @Override
    public Optional<String> denial(PromotionCheck check) {
        EngineResult result =
                evaluate(check.releaseId(), check.organizationId(), check.applicationId(), check.toRing());
        if (check.purpose() == Purpose.ATTEMPT) {
            writer.recordEvaluation(check, result);
        }
        if (result.verdict() == Verdict.ALLOWED) {
            return Optional.empty();
        }
        StringBuilder refusal = new StringBuilder();
        if (result.verdict() == Verdict.BLOCKED) {
            refusal.append("policy rule '")
                    .append(result.winningRuleName())
                    .append("' BLOCK: ")
                    .append(detailOf(result, result.winningRuleId()));
        } else {
            RuleOutcome first = result.pendingHolds().get(0);
            refusal.append("held for review by policy rule '")
                    .append(first.ruleName())
                    .append("' (HOLD_FOR_REVIEW): ")
                    .append(first.detail());
        }
        for (RuleOutcome hold : result.pendingHolds()) {
            refusal.append("; hold of rule '")
                    .append(hold.ruleName())
                    .append("' pending acknowledge (evidence: ")
                    .append(hold.detail())
                    .append(")");
        }
        if (result.verdict() == Verdict.HELD) {
            refusal.append("; a privileged acknowledge (policy-holds route) records the decision and proceeds");
        }
        return Optional.of(refusal.toString());
    }

    /**
     * The skip-relaxation knob (the seam's documented widening path):
     * the organization's settings name the rings a promotion may
     * carry BEYOND the next. Absent settings — every organization
     * that never edited them — answer the #44 default: refuse.
     */
    @Override
    public boolean admitsSkip(PromotionCheck check) {
        return settings.findByOrganizationId(check.organizationId())
                .map(row -> row.getSkipAllowances() != null
                        && row.getSkipAllowances().contains(check.toRing()))
                .orElse(false);
    }

    // -------------------------------------------------------------------------
    // The evaluation: one deterministic walk over the scoped rules
    // -------------------------------------------------------------------------

    /**
     * The one evaluation every surface shares (the promote act, the
     * precheck read, the dry-run, the hop's in-transaction
     * revalidation): the enabled rules scoped to the hop, walked in
     * the defined order, each answered with its outcome and evidence.
     * Pure — no writes; the ATTEMPT surface records beside it.
     */
    public EngineResult evaluate(UUID releaseId, UUID organizationId, UUID applicationId, String toRing) {
        ReleaseEntity release = releases.findScoped(releaseId).orElse(null);
        UUID environmentId = release == null ? null : release.getEnvironmentId();
        Set<String> platforms = platformsOf(releaseId);
        Map<String, GateState> gateStates = gateStatesOf(releaseId, toRing);
        PromotionApprovalSource.Standing approval = approvalStandingOf(releaseId, organizationId, toRing);
        Map<PolicyMetric, Optional<Double>> metricCache = new EnumMap<>(PolicyMetric.class);
        List<RuleOutcome> outcomes = new ArrayList<>();
        List<RuleOutcome> pendingHolds = new ArrayList<>();
        UUID winningRuleId = null;
        String winningRuleName = null;
        Verdict verdict = Verdict.ALLOWED;
        for (ReleasePolicyRuleEntity rule : rules.findByOrganizationIdOrderByPositionAscNameAsc(organizationId)) {
            if (!rule.isEnabled() || !scopedTo(rule, applicationId, environmentId, toRing)) {
                continue;
            }
            RuleOutcome outcome = evaluateRule(rule, releaseId, gateStates, approval, metricCache, platforms);
            outcomes.add(outcome);
            if (outcome.action().equals(Action.HOLD_FOR_REVIEW.name()) && outcome.fires()) {
                ReleasePolicyHoldEntity hold = holds.findByReleaseIdAndRuleIdAndRing(releaseId, rule.getId(), toRing)
                        .orElse(null);
                if (hold != null && hold.getStatus() == Status.ACKNOWLEDGED) {
                    outcomes.set(outcomes.size() - 1, acknowledged(outcome));
                } else {
                    RuleOutcome withHold = new RuleOutcome(
                            outcome.ruleId(),
                            outcome.ruleName(),
                            outcome.position(),
                            outcome.action(),
                            outcome.outcome(),
                            outcome.detail(),
                            hold == null ? null : hold.getId());
                    outcomes.set(outcomes.size() - 1, withHold);
                    pendingHolds.add(withHold);
                }
            }
            if (verdict == Verdict.ALLOWED && outcome.action().equals(Action.BLOCK.name()) && outcome.fires()) {
                // First BLOCK wins (AC5); later BLOCK rules still log.
                verdict = Verdict.BLOCKED;
                winningRuleId = rule.getId();
                winningRuleName = rule.getName();
            }
        }
        if (verdict == Verdict.ALLOWED && !pendingHolds.isEmpty()) {
            verdict = Verdict.HELD;
        }
        return new EngineResult(toRing, verdict, winningRuleId, winningRuleName, List.copyOf(outcomes), pendingHolds);
    }

    private RuleOutcome evaluateRule(
            ReleasePolicyRuleEntity rule,
            UUID releaseId,
            Map<String, GateState> gateStates,
            PromotionApprovalSource.Standing approval,
            Map<PolicyMetric, Optional<Double>> metricCache,
            Set<String> platforms) {
        List<String> parseErrors = new ArrayList<>();
        PolicyCondition condition = PolicyCondition.parse(rule.getCondition(), "condition", parseErrors);
        String detail;
        Truth truth;
        if (condition == null) {
            // Storage drift the write surface's validation makes
            // impossible; the fail-closed answer is FIRED — a rule the
            // engine cannot answer never silently permits.
            truth = Truth.TRUE;
            detail = "the stored condition could not be read (fail closed): " + parseErrors;
        } else {
            Evaluation evaluation = new Evaluation(releaseId, gateStates, approval, metricCache, platforms);
            Answer answer = evaluateNode(condition, evaluation);
            truth = answer.truth();
            detail = answer.detail();
        }
        Outcome outcome = switch (truth) {
            case TRUE -> Outcome.FIRED;
            case FALSE -> Outcome.NOT_MET;
            case NO_DATA -> Outcome.NO_DATA;
        };
        return new RuleOutcome(
                rule.getId(),
                rule.getName(),
                rule.getPosition(),
                rule.getAction().name(),
                outcome,
                detail,
                null);
    }

    private static RuleOutcome acknowledged(RuleOutcome outcome) {
        return new RuleOutcome(
                outcome.ruleId(),
                outcome.ruleName(),
                outcome.position(),
                outcome.action(),
                Outcome.ACKNOWLEDGED,
                outcome.detail(),
                outcome.holdId());
    }

    /** Whether the rule's scope matches this hop (the ring scope matches the ring being ENTERED). */
    private static boolean scopedTo(
            ReleasePolicyRuleEntity rule, UUID applicationId, UUID environmentId, String toRing) {
        return switch (rule.getScope()) {
            case ORGANIZATION -> true;
            case APPLICATION ->
                rule.getApplicationId() != null && rule.getApplicationId().equals(applicationId);
            case RING -> toRing.equals(rule.getRing());
            case ENVIRONMENT -> environmentId != null && environmentId.equals(rule.getEnvironmentId());
        };
    }

    // -------------------------------------------------------------------------
    // The condition tree's arithmetic (three-valued, fail-closed)
    // -------------------------------------------------------------------------

    private record Answer(Truth truth, String detail) {}

    private final class Evaluation {
        private final UUID releaseId;
        private final Map<String, GateState> gateStates;
        private final PromotionApprovalSource.Standing approval;
        private final Map<PolicyMetric, Optional<Double>> metricCache;
        private final Set<String> platforms;

        private Evaluation(
                UUID releaseId,
                Map<String, GateState> gateStates,
                PromotionApprovalSource.Standing approval,
                Map<PolicyMetric, Optional<Double>> metricCache,
                Set<String> platforms) {
            this.releaseId = releaseId;
            this.gateStates = gateStates;
            this.approval = approval;
            this.metricCache = metricCache;
            this.platforms = platforms;
        }
    }

    private Answer evaluateNode(PolicyCondition node, Evaluation evaluation) {
        return switch (node) {
            case AllOf all -> combine(all.children(), evaluation, true);
            case AnyOf any -> combine(any.children(), evaluation, false);
            case MetricCondition metric -> metricAnswer(metric, evaluation);
            case GateCondition gate -> gateAnswer(gate, evaluation);
            case ApprovalCondition approvalValue ->
                new Answer(
                        (evaluation.approval.blocks() == approvalValue.expected()) ? Truth.TRUE : Truth.FALSE,
                        approvalDetail(evaluation.approval));
            case ArtifactCondition artifact ->
                new Answer(
                        evaluation.platforms.contains(artifact.platform()) ? Truth.TRUE : Truth.FALSE,
                        "platforms shipped: "
                                + (evaluation.platforms.isEmpty() ? "none" : String.join(", ", evaluation.platforms)));
        };
    }

    private Answer combine(List<PolicyCondition> children, Evaluation evaluation, boolean all) {
        List<Answer> answers = new ArrayList<>();
        for (PolicyCondition child : children) {
            answers.add(evaluateNode(child, evaluation));
        }
        boolean anyFalse = answers.stream().anyMatch(answer -> answer.truth() == Truth.FALSE);
        boolean anyTrue = answers.stream().anyMatch(answer -> answer.truth() == Truth.TRUE);
        boolean anyNoData = answers.stream().anyMatch(answer -> answer.truth() == Truth.NO_DATA);
        Truth truth;
        if (all) {
            // allOf: a definitive FALSE answers FALSE; else any NO_DATA dominates (fail closed).
            truth = anyFalse ? Truth.FALSE : anyNoData ? Truth.NO_DATA : Truth.TRUE;
        } else {
            // anyOf: a definitive TRUE answers TRUE; else any NO_DATA dominates (fail closed).
            truth = anyTrue ? Truth.TRUE : anyNoData ? Truth.NO_DATA : Truth.FALSE;
        }
        StringBuilder detail = new StringBuilder();
        for (int i = 0; i < children.size(); i++) {
            if (!detail.isEmpty()) {
                detail.append(all ? " AND " : " OR ");
            }
            detail.append(children.get(i).describe())
                    .append(" -> ")
                    .append(truthText(answers.get(i).truth()));
        }
        return new Answer(truth, detail.toString());
    }

    private Answer metricAnswer(MetricCondition metric, Evaluation evaluation) {
        Optional<Double> value = evaluation.metricCache.computeIfAbsent(metric.metric(), kind -> {
            // First DEFINITIVE answer wins (S11-02): a source that
            // owns a metric answers non-empty for every release it is
            // asked about (the feedback-backed OPEN_CRITICAL_BUGS
            // registrant), while the sources that merely hold no data
            // yet answer empty and step aside — an empty answer means
            // "this source cannot speak to it", never "the value is
            // zero" (the seam's own contract).
            for (ReleasePolicyMetricSource source : ReleasePolicyEngine.this.metricSources) {
                Optional<Double> answered = source.metricOf(evaluation.releaseId, kind);
                if (answered.isPresent()) {
                    return answered;
                }
            }
            return Optional.empty();
        });
        if (value.isEmpty()) {
            return new Answer(
                    Truth.NO_DATA,
                    "metric " + metric.metric().name()
                            + ": no data (fail closed; analytics has no value for this release)");
        }
        double observed = value.get();
        boolean matches = metric.operator().matches(observed, metric.value());
        return new Answer(
                matches ? Truth.TRUE : Truth.FALSE,
                "metric " + metric.metric().name() + " = " + observed + " "
                        + metric.operator().symbol() + " " + metric.value() + (matches ? " (met)" : " (not met)"));
    }

    private Answer gateAnswer(GateCondition gate, Evaluation evaluation) {
        GateState state = evaluation.gateStates.get(gate.gateType());
        if (state == null) {
            return new Answer(
                    Truth.FALSE,
                    "gate " + gate.gateType() + " is not part of the target ring's evaluation (condition not met)");
        }
        if (state.overridden()
                && (gate.status() == PolicyCondition.GateStatus.FAILED
                        || gate.status() == PolicyCondition.GateStatus.NO_DATA)) {
            // The audited override is the recorded decision that the
            // gate does not block (the S10-03 discipline): this
            // engine does not re-litigate it with a standing rule.
            return new Answer(
                    Truth.FALSE,
                    "gate " + gate.gateType() + " is " + state.status() + " but overridden by " + state.overriddenBy()
                            + " (recorded decision honored)");
        }
        boolean matches = gate.status().name().equals(state.status());
        String evidence = state.evidenceUrl() == null ? "" : " (evidence: " + state.evidenceUrl() + ")";
        return new Answer(
                matches ? Truth.TRUE : Truth.FALSE, "gate " + gate.gateType() + " is " + state.status() + evidence);
    }

    private static String approvalDetail(PromotionApprovalSource.Standing approval) {
        return switch (approval.requirement()) {
            case NOT_REQUIRED -> "approval: the target ring demands no approval policy";
            case SATISFIED ->
                "approval: policy '" + approval.policyName() + "' round " + approval.roundNumber() + " satisfied";
            default ->
                "approval: policy '" + approval.policyName() + "' round "
                        + (approval.roundNumber() == null ? "-" : approval.roundNumber()) + " is "
                        + approval.requirement().name();
        };
    }

    private static String truthText(Truth truth) {
        return switch (truth) {
            case TRUE -> "met";
            case FALSE -> "not met";
            case NO_DATA -> "no data (fail closed)";
        };
    }

    // -------------------------------------------------------------------------
    // Facts: gates, approvals, metrics, platforms — read through the module's own ports
    // -------------------------------------------------------------------------

    private Map<String, GateState> gateStatesOf(UUID releaseId, String toRing) {
        Map<String, GateState> states = new LinkedHashMap<>();
        ReleaseGateEvaluation.Evaluation evaluation =
                gateEvaluation.evaluate(releaseId, toRing).orElse(null);
        if (evaluation != null) {
            for (GateState state : evaluation.gates()) {
                states.put(state.gateType(), state);
            }
        }
        return states;
    }

    private PromotionApprovalSource.Standing approvalStandingOf(UUID releaseId, UUID organizationId, String toRing) {
        RingEntity ring = rings.byCode(toRing, organizationId).orElse(null);
        if (ring == null) {
            return new PromotionApprovalSource.Standing(
                    PromotionApprovalSource.Standing.Requirement.NOT_REQUIRED, null, null, null, Map.of());
        }
        return approvalLeg.standingOf(releaseId, organizationId, ring.getId());
    }

    private Set<String> platformsOf(UUID releaseId) {
        Set<String> platforms = new LinkedHashSet<>();
        for (ReleaseArtifactEntity artifact : artifacts.findByReleaseIdOrderByPlatformAscArtifactIdAsc(releaseId)) {
            if (artifact.getPlatform() != null) {
                // The condition vocabulary is lowercase (the parse's
                // normalization); the stored platform enum is not.
                platforms.add(artifact.getPlatform().toLowerCase(java.util.Locale.ROOT));
            }
        }
        return platforms;
    }

    private static String detailOf(EngineResult result, UUID ruleId) {
        for (RuleOutcome outcome : result.outcomes()) {
            if (outcome.ruleId().equals(ruleId)) {
                return outcome.detail();
            }
        }
        return "-";
    }

    private static String bounded(String detail) {
        if (detail == null) {
            return null;
        }
        return detail.length() > 1024 ? detail.substring(0, 1021) + "..." : detail;
    }
}
