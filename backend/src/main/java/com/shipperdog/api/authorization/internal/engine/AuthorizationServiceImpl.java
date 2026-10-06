package com.shipperdog.api.authorization.internal.engine;

import com.shipperdog.api.application.ApplicationAccess;
import com.shipperdog.api.artifact.ArtifactAccess;
import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.audit.AuditLogger;
import com.shipperdog.api.authorization.AuthorizationContext;
import com.shipperdog.api.authorization.AuthorizationDecision;
import com.shipperdog.api.authorization.AuthorizationDecision.LayerOutcome;
import com.shipperdog.api.authorization.AuthorizationDecision.Status;
import com.shipperdog.api.authorization.AuthorizationService;
import com.shipperdog.api.authorization.ConcealedResourceAccessDeniedException;
import com.shipperdog.api.authorization.DenialReason;
import com.shipperdog.api.authorization.internal.evaluator.RequestRoleGrants;
import com.shipperdog.api.config.MetricNames;
import com.shipperdog.api.tester.TesterAccess;
import com.shipperdog.api.version.VersionAccess;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * The layered engine's orchestrator (issue #30, S7-01): composes the
 * {@code AuthorizationLayer} beans in PRD 11 order (constructor-sorted
 * by each layer's explicit position — the order is code, not bean
 * registration luck, and a test pins it), walks them for a decision —
 * applicability check, evaluate, short-circuit at the first failure
 * with the remaining layers recorded SKIPPED — and leaves the
 * trail every decision must have:
 *
 * <ul>
 *   <li><strong>Audit</strong> — one {@code AUTHORIZATION_DECISION}
 *       event per decision, ALLOW and DENY alike, carrying the action,
 *       the chain summary, the failing layer and reason when denied,
 *       and (attached by the audit binding) the request's correlation
 *       id (PRD 27, issue #30 acceptance criterion 4).</li>
 *   <li><strong>Timing</strong> — the shared
 *       {@code shipperdog.authorization.decision.duration} meter
 *       tagged {@code engine=layered} and the outcome, beside the RBAC
 *       evaluator's own leg of the same meter: the PRD 42
 *       under-100 ms decision SLO observes both engines. Only the
 *       layer walk is timed; the audit write happens after the clock
 *       stops so it cannot skew the SLO signal (the evaluator's
 *       discipline).</li>
 * </ul>
 *
 * <p>Layers are read-only and never throw (a fault is a denial, not a
 * 500): each layer catches its own resolution failures as verdicts,
 * and an unexpected layer error fails CLOSED through the walk's
 * catch-all — a decision that cannot be made is a decision denied.
 */
@Service
class AuthorizationServiceImpl implements AuthorizationService {

    private final List<AuthorizationLayer> layers;
    private final RequestRoleGrants grants;
    private final ApplicationAccess applications;
    private final VersionAccess versions;
    private final ArtifactAccess artifacts;
    private final TesterAccess testers;
    private final AuditLogger audit;
    private final MeterRegistry meters;

    AuthorizationServiceImpl(
            List<AuthorizationLayer> layers,
            RequestRoleGrants grants,
            ApplicationAccess applications,
            VersionAccess versions,
            ArtifactAccess artifacts,
            TesterAccess testers,
            AuditLogger audit,
            MeterRegistry meters) {
        List<AuthorizationLayer> ordered = new ArrayList<>(layers);
        ordered.sort(Comparator.comparingInt(AuthorizationLayer::order));
        this.layers = List.copyOf(ordered);
        this.grants = grants;
        this.applications = applications;
        this.versions = versions;
        this.artifacts = artifacts;
        this.testers = testers;
        this.audit = audit;
        this.meters = meters;
    }

    @Override
    public AuthorizationDecision decide(AuthorizationContext context) {
        return decide(context, "serve");
    }

    @Override
    public AuthorizationDecision inspect(AuthorizationContext context) {
        return decide(context, "inspect");
    }

    private AuthorizationDecision decide(AuthorizationContext context, String surface) {
        Timer.Sample sample = Timer.start(meters);
        AuthorizationDecision decision = decideFailClosed(context);
        sample.stop(Timer.builder(MetricNames.AUTHORIZATION_DECISION_DURATION)
                .tag("engine", "layered")
                .tag("outcome", decision.allowed() ? "allow" : "deny")
                .register(meters));
        recordDecision(context, decision, surface);
        return decision;
    }

    /** The decision, fail-closed: an engine fault is a denial, never an allow or a 500. */
    private AuthorizationDecision decideFailClosed(AuthorizationContext context) {
        try {
            return walk(context);
        } catch (RuntimeException e) {
            return new AuthorizationDecision(
                    false,
                    DenialReason.ENGINE_FAULT,
                    "ENGINE",
                    layers.stream()
                            .map(layer -> new LayerOutcome(layer.name(), Status.SKIPPED, null, "engine fault"))
                            .toList());
        }
    }

    private AuthorizationDecision walk(AuthorizationContext context) {
        EvaluationState state =
                new EvaluationState(applications, versions, artifacts, testers, grants.snapshot(context.userId()));
        List<LayerOutcome> outcomes = new ArrayList<>(layers.size());
        boolean failed = false;
        for (AuthorizationLayer layer : layers) {
            if (failed) {
                outcomes.add(new LayerOutcome(layer.name(), Status.SKIPPED, null, "short-circuited"));
                continue;
            }
            if (!layer.applicable(state, context)) {
                outcomes.add(new LayerOutcome(layer.name(), Status.SKIPPED, null, "not applicable"));
                continue;
            }
            AuthorizationLayer.Verdict verdict;
            try {
                verdict = layer.evaluate(state, context);
            } catch (RuntimeException e) {
                verdict = AuthorizationLayer.Verdict.failed(DenialReason.ENGINE_FAULT, "layer fault: " + layer.name());
            }
            outcomes.add(verdict.outcomeFor(layer.name()));
            failed = verdict.status() == Status.FAILED;
        }
        LayerOutcome failure = outcomes.stream()
                .filter(outcome -> outcome.status() == Status.FAILED)
                .findFirst()
                .orElse(null);
        if (failure == null) {
            return new AuthorizationDecision(true, null, null, outcomes);
        }
        return new AuthorizationDecision(false, failure.reason(), failure.layer(), outcomes);
    }

    @Override
    public void require(AuthorizationContext context, String concealTargetType) {
        AuthorizationDecision decision = decide(context);
        if (!decision.allowed()) {
            throw new ConcealedResourceAccessDeniedException(concealTargetType);
        }
    }

    private void recordDecision(AuthorizationContext context, AuthorizationDecision decision, String surface) {
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("action", context.action().name());
        detail.put("surface", surface);
        detail.put("outcome", decision.allowed() ? "ALLOW" : "DENY");
        if (!decision.allowed()) {
            detail.put("failingLayer", decision.failingLayer());
            detail.put("reason", decision.reason().name());
        }
        detail.put("chain", chainSummary(decision));
        audit.record(AuditEvent.builder(AuditEventType.AUTHORIZATION_DECISION)
                .userId(context.userId())
                .tenantId(context.tenantId())
                .applicationId(context.applicationId())
                .versionId(context.versionId())
                .artifactId(context.artifactId())
                .success(decision.allowed())
                .detail(detail)
                .build());
    }

    private static String chainSummary(AuthorizationDecision decision) {
        StringBuilder summary = new StringBuilder();
        for (LayerOutcome outcome : decision.chain()) {
            if (!summary.isEmpty()) {
                summary.append(',');
            }
            summary.append(outcome.layer()).append(':').append(outcome.status());
        }
        return summary.toString();
    }
}
