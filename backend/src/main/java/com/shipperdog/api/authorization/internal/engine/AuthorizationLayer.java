package com.shipperdog.api.authorization.internal.engine;

import com.shipperdog.api.authorization.AuthorizationContext;
import com.shipperdog.api.authorization.AuthorizationDecision.LayerOutcome;
import com.shipperdog.api.authorization.AuthorizationDecision.Status;

/**
 * One PRD 11 chain member of the layered engine (issue #30, S7-01):
 * composable Spring beans the engine composes IN ORDER — User,
 * Organization, Tenant, Application, Version, Artifact, Distribution
 * (each layer class carries its {@link #order()} so the composition is
 * explicit and testable, the issue's technical note). A layer either
 * {@link Verdict#passed passes} (with an inspectable detail line),
 * {@link Verdict#failed fails} (short-circuiting the chain with a
 * reason code), or is not applicable to the context — answered by
 * {@link #applicable} — in which case the engine records it SKIPPED
 * and moves on ("a request should pass through every applicable
 * authorization layer").
 *
 * <p>Layers orchestrate only: they resolve their inputs through the
 * modules' public ports and the running {@link EvaluationState}, and
 * they never embed business rules — entitlements arrive through
 * {@code AuthorizationEntitlement} contributors and mappings through
 * the application module's visibility resolution.
 */
public interface AuthorizationLayer {

    /** The chain position (PRD 11 order); spaced by 100 for inserts. */
    int order();

    /** The layer's trace name (the {@code failingLayer} of a denial). */
    String name();

    /** Whether the context addresses this layer's question. */
    boolean applicable(EvaluationState state, AuthorizationContext context);

    /** Evaluates the layer; read-only, never throws (deny, not fault). */
    Verdict evaluate(EvaluationState state, AuthorizationContext context);

    /** A layer's answer: pass or deny-with-reason, plus a detail line for the trace. */
    record Verdict(Status status, com.shipperdog.api.authorization.DenialReason reason, String detail) {

        static Verdict passed(String detail) {
            return new Verdict(Status.PASSED, null, detail);
        }

        static Verdict failed(com.shipperdog.api.authorization.DenialReason reason, String detail) {
            return new Verdict(Status.FAILED, reason, detail);
        }

        LayerOutcome outcomeFor(String layer) {
            return new LayerOutcome(layer, status, reason, detail);
        }
    }
}
