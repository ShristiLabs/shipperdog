package com.shipperdog.api.authorization.internal.abac;

import com.shipperdog.api.application.ApplicationAccess;
import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.audit.AuditLogger;
import com.shipperdog.api.authorization.AbacCondition;
import com.shipperdog.api.authorization.AbacCondition.AllOf;
import com.shipperdog.api.authorization.AbacCondition.AnyOf;
import com.shipperdog.api.authorization.AbacCondition.ApplicationName;
import com.shipperdog.api.authorization.AbacCondition.ApplicationPlatform;
import com.shipperdog.api.authorization.AbacCondition.EnvironmentName;
import com.shipperdog.api.authorization.AbacCondition.RingCode;
import com.shipperdog.api.authorization.AbacCondition.SecurityStatus;
import com.shipperdog.api.authorization.AbacCondition.SubjectLinkedTester;
import com.shipperdog.api.authorization.AbacCondition.SubjectOrgRole;
import com.shipperdog.api.authorization.AbacCondition.SubjectPlatformAdmin;
import com.shipperdog.api.authorization.internal.abac.AbacAttributeSnapshot.Attribute;
import com.shipperdog.api.authorization.internal.abac.AbacAttributeSnapshot.EnvironmentNameSource;
import com.shipperdog.api.authorization.internal.abac.AbacPolicyCache.CompiledPolicy;
import com.shipperdog.api.authorization.internal.evaluator.RequestRoleGrants;
import com.shipperdog.api.config.MetricNames;
import com.shipperdog.api.gate.ReleaseGateEvaluation;
import com.shipperdog.api.tester.TesterAccess;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The ABAC evaluation engine (issue #70, S14-04, PRD 25/33/38): the
 * deny-override refinement layer over the RBAC base — the
 * organization's attribute policies, compiled once per TTL window,
 * evaluated deterministically over the request's lazily-resolved
 * attribute snapshot.
 *
 * <p><strong>The composition (AC2).</strong> The engine can only
 * NARROW: it answers either ABSTAIN (no applicable policy — the base
 * decision stands untouched) or DENIED. There is no allow outcome
 * and no code path that could synthesize one — a policy no RBAC
 * grant backs authorizes nothing, exactly as the issue demands.
 *
 * <p><strong>The decision.</strong> Applicable policies — enabled,
 * governing the request's action, selectors matching (null = any) —
 * evaluate in the defined order (position, then name). A {@code
 * DENY} policy denies when its condition holds; a {@code REQUIRE}
 * policy denies when its condition does NOT hold for a request its
 * selectors match (PRD 25's "Can promote IF ..." shape authored
 * exactly). The FIRST denying policy in order wins the explanation;
 * every evaluated policy's trace is part of it.
 *
 * <p><strong>Fail closed, always (AC6).</strong> An attribute the
 * request's sources cannot resolve is never a guess: the evaluation
 * answers UNRESOLVED and the engine DENIES — the {@code
 * shipperdog.abac.fail-closed} counter and the ABAC_EVALUATION_FAULT
 * audit line are the alert. A stored condition that cannot be parsed
 * (impossible through the write surface's validation; storage drift
 * only) denies the same way — a policy the engine cannot answer
 * never silently permits. An unexpected engine fault denies too,
 * never a 500 on a decision path.
 */
@Component
public class AbacPolicyEngine {

    /** The three-valued truth a condition tree evaluates to (UNRESOLVED is the fail-closed dominant). */
    private enum Truth {
        MET,
        NOT_MET,
        UNRESOLVED
    }

    /** One condition node's answer with its per-attribute trace (the explanation's building block). */
    private record Answer(Truth truth, StringBuilder trace) {}

    private final AbacPolicyCache cache;
    private final RequestRoleGrants grants;
    private final ApplicationAccess applications;
    private final ReleaseGateEvaluation gateEvaluation;
    private final TesterAccess testers;
    private final AuditLogger audit;
    private final MeterRegistry meters;

    AbacPolicyEngine(
            AbacPolicyCache cache,
            RequestRoleGrants grants,
            ApplicationAccess applications,
            ReleaseGateEvaluation gateEvaluation,
            TesterAccess testers,
            AuditLogger audit,
            MeterRegistry meters) {
        this.cache = cache;
        this.grants = grants;
        this.applications = applications;
        this.gateEvaluation = gateEvaluation;
        this.testers = testers;
        this.audit = audit;
        this.meters = meters;
    }

    /** Whether the organization holds any enabled policy (the layer's zero-cost fast-path probe). */
    public boolean anyEnabledPolicy(UUID organizationId) {
        return cache.anyEnabled(organizationId);
    }

    /**
     * The layered engine's consult (S7-03 path): the same evaluation,
     * never separately audited — the AUTHORIZATION_DECISION event the
     * orchestrator already emits carries failingLayer=ABAC with this
     * result's explanation in the chain detail.
     */
    public AbacResult evaluateForLayers(AbacRequest request, EnvironmentNameSource environments) {
        return evaluate(request, environments, null);
    }

    /**
     * The evaluation every surface shares: deterministic, read-only,
     * fail-closed. The {@code auditedSurface} names a NON-layered
     * caller (the promotion consult) whose denial the engine records
     * as its own ABAC_POLICY_DENIED event — the layered path passes
     * null and rides the standing AUTHORIZATION_DECISION trail
     * instead, so no decision is audited twice.
     */
    public AbacResult evaluate(AbacRequest request, EnvironmentNameSource environments, String auditedSurface) {
        try {
            return evaluatePolicies(request, environments, auditedSurface);
        } catch (RuntimeException fault) {
            // The engine itself faulted: deny, never a 500 on a decision path.
            failClosed("engine");
            AbacResult result =
                    AbacResult.denied(null, "-", 0, true, "the ABAC engine could not evaluate (fail closed)");
            if (auditedSurface != null) {
                auditFault(request, auditedSurface, result.explanation());
            }
            return result;
        }
    }

    private AbacResult evaluatePolicies(
            AbacRequest request, EnvironmentNameSource environments, String auditedSurface) {
        List<CompiledPolicy> policies = cache.enabledOf(request.organizationId());
        List<CompiledPolicy> applicable = policies.stream()
                .filter(policy -> policy.action() == request.action())
                .filter(policy ->
                        policy.applicationId() == null || policy.applicationId().equals(request.applicationId()))
                .filter(policy ->
                        policy.environmentId() == null || policy.environmentId().equals(request.environmentId()))
                .filter(policy -> policy.ring() == null || policy.ring().equals(request.ring()))
                .toList();
        if (applicable.isEmpty()) {
            return AbacResult.abstain();
        }
        AbacAttributeSnapshot snapshot =
                new AbacAttributeSnapshot(request, grants, applications, gateEvaluation, testers);
        AbacResult denial = null;
        boolean sawUnresolved = false;
        StringBuilder explanation = new StringBuilder();
        for (CompiledPolicy policy : applicable) {
            if (policy.condition() == null) {
                // Storage drift the write surface's validation makes
                // impossible; the fail-closed answer is a denial — a
                // policy the engine cannot answer never permits.
                failClosed("condition");
                denial = firstOf(
                        denial,
                        AbacResult.denied(
                                policy.id(),
                                policy.name(),
                                policy.version(),
                                true,
                                "the stored condition could not be read (fail closed)"));
                if (!explanation.isEmpty()) {
                    explanation.append("; ");
                }
                explanation
                        .append(policy.name())
                        .append(" (v")
                        .append(policy.version())
                        .append(", ")
                        .append(policy.effect().name())
                        .append("): the stored condition could not be read (fail closed)");
                continue;
            }
            Answer answer = evaluateNode(policy.condition(), snapshot, environments);
            boolean denies = switch (policy.effect()) {
                case DENY -> answer.truth() == Truth.MET || answer.truth() == Truth.UNRESOLVED;
                // REQUIRE: the condition must hold; NOT_MET refuses,
                // and UNRESOLVED refuses fail-closed (AC6).
                case REQUIRE -> answer.truth() != Truth.MET;
            };
            if (answer.truth() == Truth.UNRESOLVED) {
                sawUnresolved = true;
                failClosed("attribute");
            }
            if (denies && denial == null) {
                StringBuilder rendered = new StringBuilder();
                rendered.append(policy.effect().name()).append(' ');
                rendered.append(answer.trace());
                denial = AbacResult.denied(policy.id(), policy.name(), policy.version(), false, rendered.toString());
            }
            if (!explanation.isEmpty()) {
                explanation.append("; ");
            }
            explanation
                    .append(policy.name())
                    .append(" (v")
                    .append(policy.version())
                    .append(", ")
                    .append(policy.effect().name())
                    .append("): ")
                    .append(answer.trace());
        }
        if (denial == null) {
            if (sawUnresolved && auditedSurface != null) {
                // A DENY policy that could not fire on an unresolved
                // attribute: no denial, but the fault is still the
                // alert-worthy event (AC6).
                auditFault(request, auditedSurface, explanation.toString());
            }
            return AbacResult.abstain();
        }
        if (sawUnresolved && auditedSurface != null) {
            auditFault(request, auditedSurface, explanation.toString());
        }
        // Enrich the authored refusal with the full evaluated trace;
        // a fault denial keeps its fault standing (the layer renders
        // ENGINE_FAULT, the consult renders the fail-closed text).
        AbacResult enriched = AbacResult.denied(
                denial.ruleId(), denial.ruleName(), denial.version(), denial.fault(), explanation.toString());
        if (auditedSurface != null) {
            Map<String, String> detail = new LinkedHashMap<>();
            detail.put("rule", enriched.ruleName());
            detail.put("version", String.valueOf(enriched.version()));
            detail.put("action", request.action().name());
            detail.put("explanation", bounded(enriched.explanation()));
            audit.record(AuditEvent.builder(AuditEventType.ABAC_POLICY_DENIED)
                    .userId(request.userId())
                    .orgId(request.organizationId())
                    .applicationId(request.applicationId())
                    .success(false)
                    .detail(detail)
                    .build());
        }
        return enriched;
    }

    // -------------------------------------------------------------------------
    // The condition tree's arithmetic (three-valued, fail-closed)
    // -------------------------------------------------------------------------

    private Answer evaluateNode(
            AbacCondition node, AbacAttributeSnapshot snapshot, EnvironmentNameSource environments) {
        return switch (node) {
            case AllOf all -> combine(all.children(), snapshot, environments, true);
            case AnyOf any -> combine(any.children(), snapshot, environments, false);
            case SubjectOrgRole atom -> stringAnswer(snapshot.subjectOrgRole(), atom.role(), "subject.orgRole");
            case SubjectPlatformAdmin atom ->
                booleanAnswer(snapshot.subjectPlatformAdmin(), atom.expected(), "subject.platformAdmin");
            case SubjectLinkedTester atom ->
                booleanAnswer(snapshot.subjectLinkedTester(), atom.expected(), "subject.linkedTester");
            case ApplicationName atom -> stringAnswer(snapshot.applicationName(), atom.name(), "application.name");
            case ApplicationPlatform atom ->
                stringAnswer(snapshot.applicationPlatform(), atom.platform(), "application.platform");
            case EnvironmentName atom ->
                stringAnswer(snapshot.environmentName(environments), atom.name(), "environment.name");
            case RingCode atom -> stringAnswer(snapshot.ringCode(), atom.ring(), "ring");
            case SecurityStatus atom -> stringAnswer(snapshot.securityStatus(), atom.status(), "security.status");
        };
    }

    private Answer combine(
            List<AbacCondition> children,
            AbacAttributeSnapshot snapshot,
            EnvironmentNameSource environments,
            boolean all) {
        // The three-valued arithmetic (the ReleasePolicyEngine
        // discipline): a definitive answer wins, else UNRESOLVED
        // dominates (fail closed) — a combinator that cannot be
        // answered never permits.
        List<Answer> answers = children.stream()
                .map(child -> evaluateNode(child, snapshot, environments))
                .toList();
        boolean anyMet = answers.stream().anyMatch(answer -> answer.truth() == Truth.MET);
        boolean anyNotMet = answers.stream().anyMatch(answer -> answer.truth() == Truth.NOT_MET);
        boolean anyUnresolved = answers.stream().anyMatch(answer -> answer.truth() == Truth.UNRESOLVED);
        Truth truth;
        if (all) {
            truth = anyNotMet ? Truth.NOT_MET : anyUnresolved ? Truth.UNRESOLVED : Truth.MET;
        } else {
            truth = anyMet ? Truth.MET : anyUnresolved ? Truth.UNRESOLVED : Truth.NOT_MET;
        }
        // The trace carries each child's RESOLVED attribute values
        // (the atom's own rendering), not just its authored shape —
        // the decision explanation AC3 demands.
        StringBuilder trace = new StringBuilder();
        for (int i = 0; i < children.size(); i++) {
            if (!trace.isEmpty()) {
                trace.append(all ? " AND " : " OR ");
            }
            trace.append(answers.get(i).trace());
        }
        return new Answer(truth, trace);
    }

    private static <T> Answer stringAnswer(Attribute<T> attribute, T expected, String name) {
        if (!attribute.resolved()) {
            return new Answer(
                    Truth.UNRESOLVED,
                    new StringBuilder(name).append(" unresolvable: ").append(attribute.detail()));
        }
        boolean matches = attribute.value().equals(expected);
        return new Answer(
                matches ? Truth.MET : Truth.NOT_MET,
                new StringBuilder(name)
                        .append(" = ")
                        .append(attribute.value())
                        .append(matches ? " (met)" : " (not " + expected + ")"));
    }

    private static Answer booleanAnswer(Attribute<Boolean> attribute, boolean expected, String name) {
        if (!attribute.resolved()) {
            return new Answer(
                    Truth.UNRESOLVED,
                    new StringBuilder(name).append(" unresolvable: ").append(attribute.detail()));
        }
        boolean matches = attribute.value() == expected;
        return new Answer(
                matches ? Truth.MET : Truth.NOT_MET,
                new StringBuilder(name)
                        .append(" = ")
                        .append(attribute.value())
                        .append(matches ? " (met)" : " (not met)"));
    }

    private static String truthText(Truth truth) {
        return switch (truth) {
            case MET -> "met";
            case NOT_MET -> "not met";
            case UNRESOLVED -> "unresolvable";
        };
    }

    private static AbacResult firstOf(AbacResult current, AbacResult candidate) {
        return current == null ? candidate : current;
    }

    private void failClosed(String cause) {
        meters.counter(MetricNames.ABAC_FAIL_CLOSED, "cause", cause).increment();
    }

    private void auditFault(AbacRequest request, String surface, String explanation) {
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("surface", surface);
        detail.put("action", request.action().name());
        detail.put("explanation", bounded(explanation));
        audit.record(AuditEvent.builder(AuditEventType.ABAC_EVALUATION_FAULT)
                .userId(request.userId())
                .orgId(request.organizationId())
                .applicationId(request.applicationId())
                .success(false)
                .detail(detail)
                .build());
    }

    private static String bounded(String text) {
        return text.length() > 1024 ? text.substring(0, 1021) + "..." : text;
    }
}
