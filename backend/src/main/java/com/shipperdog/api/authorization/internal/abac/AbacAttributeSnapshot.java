package com.shipperdog.api.authorization.internal.abac;

import com.shipperdog.api.application.ApplicationAccess;
import com.shipperdog.api.authorization.AbacCondition;
import com.shipperdog.api.authorization.AbacCondition.SecurityStatus.Status;
import com.shipperdog.api.authorization.internal.abac.AbacPolicyEntity.GoverningAction;
import com.shipperdog.api.authorization.internal.evaluator.RequestRoleGrants;
import com.shipperdog.api.gate.ReleaseGateEvaluation;
import com.shipperdog.api.gate.ReleaseGateEvaluation.Evaluation.GateState;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The lazily-resolved attribute snapshot of one ABAC evaluation
 * (issue #70, S14-04): each attribute resolves at most once per
 * evaluation, on first reference, through the modules' public ports —
 * the {@code UserRolesSnapshot} discipline the issue's latency note
 * names (bounded, indexed point lookups; the AC5 budget is met by
 * resolving ONLY what the applicable policies reference).
 *
 * <p>An attribute that cannot resolve from the request's sources
 * answers {@link Optional#empty} — never a guessed value — and the
 * engine answers that unresolvability fail-closed (AC6): a REQUIRE
 * condition fed by it refuses, a DENY condition fed by it does not
 * fire on a guess, and the {@code shipperdog.abac.fail-closed}
 * counter plus the ABAC_EVALUATION_FAULT audit line are the alert.
 */
final class AbacAttributeSnapshot {

    /** The resolved-or-unresolvable standing of one attribute (empty is the honest miss). */
    record Attribute<T>(T value, boolean resolved, String detail) {

        static <T> Attribute<T> of(T value, String detail) {
            return new Attribute<>(value, true, detail);
        }

        static <T> Attribute<T> unresolvable(String why) {
            return new Attribute<>(null, false, why);
        }
    }

    private final AbacRequest request;
    private final RequestRoleGrants grants;
    private final ApplicationAccess applications;
    private final ReleaseGateEvaluation gateEvaluation;
    private final com.shipperdog.api.tester.TesterAccess testers;

    private final ConcurrentHashMap<String, Attribute<?>> resolved = new ConcurrentHashMap<>();

    AbacAttributeSnapshot(
            AbacRequest request,
            RequestRoleGrants grants,
            ApplicationAccess applications,
            ReleaseGateEvaluation gateEvaluation,
            com.shipperdog.api.tester.TesterAccess testers) {
        this.request = request;
        this.grants = grants;
        this.applications = applications;
        this.gateEvaluation = gateEvaluation;
        this.testers = testers;
    }

    /** The subject's role in the resource's owning organization (one role per membership). */
    Attribute<String> subjectOrgRole() {
        return cached("subjectOrgRole", () -> {
            var snapshot = grants.snapshot(request.userId());
            var role = snapshot.orgRole(request.organizationId());
            return role == null
                    ? Attribute.unresolvable("the subject holds no membership in the owning organization")
                    : Attribute.of(role.name(), "subject.orgRole resolved from the live membership");
        });
    }

    /** The platform-administrator wildcard ({@code users.platform_admin}). */
    Attribute<Boolean> subjectPlatformAdmin() {
        return cached(
                "subjectPlatformAdmin",
                () -> Attribute.of(
                        grants.snapshot(request.userId()).platformAdmin(),
                        "subject.platformAdmin from the live snapshot"));
    }

    /** The subject holds a linked tester person record (the tester persona's trait). */
    Attribute<Boolean> subjectLinkedTester() {
        return cached(
                "subjectLinkedTester",
                () -> Attribute.of(
                        testers.testerIdOfUser(request.userId()).isPresent(),
                        "subject.linkedTester from the tester catalog's live probe"));
    }

    /** The addressed application's name (exact — the registry's own string). */
    Attribute<String> applicationName() {
        return cached("applicationName", () -> {
            if (request.applicationId() == null) {
                return Attribute.unresolvable("the request addresses no application");
            }
            return applications
                    .summaryOf(request.applicationId())
                    .map(summary -> Attribute.<String>of(summary.name(), "application.name resolved from the registry"))
                    .orElseGet(() -> Attribute.unresolvable("the addressed application does not resolve"));
        });
    }

    /** The request's pinned platform. */
    Attribute<String> applicationPlatform() {
        return cached("applicationPlatform", () -> {
            if (request.platform() == null) {
                return Attribute.unresolvable("the request pins no platform");
            }
            return Attribute.of(request.platform(), "application.platform from the request");
        });
    }

    /** The ring the decision addresses (a promotion's TARGET ring). */
    Attribute<String> ringCode() {
        return cached("ringCode", () -> {
            if (request.ring() == null) {
                return Attribute.unresolvable("the decision addresses no ring");
            }
            return Attribute.of(request.ring(), "ring from the request");
        });
    }

    /** The environment's name from the request's environment source. */
    Attribute<String> environmentName(EnvironmentNameSource environments) {
        return cached("environmentName", () -> {
            if (request.environmentId() == null) {
                return Attribute.unresolvable("the decision addresses no environment");
            }
            return environments
                    .nameOf(request.environmentId())
                    .map(name -> Attribute.<String>of(name, "environment.name resolved live"))
                    .orElseGet(() -> Attribute.unresolvable("the addressed environment does not resolve"));
        });
    }

    /**
     * The release-security-state (the issue's S10-03 feed): the
     * standing of the evaluation's security-class gates on the
     * addressed release and ring — APPROVED when every security gate
     * passes or its failure carries the audited override (the
     * recorded decision honored, the S10-03 discipline), FAILED when
     * one fails, PENDING when one awaits, NO_DATA when the evaluation
     * carries no security gate at all.
     */
    Attribute<Status> securityStatus() {
        return cached("securityStatus", () -> {
            if (request.releaseId() == null) {
                return Attribute.unresolvable(
                        "the decision addresses no release (the security state is a release attribute)");
            }
            ReleaseGateEvaluation.Evaluation evaluation =
                    gateEvaluation.evaluate(request.releaseId(), request.ring()).orElse(null);
            if (evaluation == null) {
                return Attribute.unresolvable("the addressed release's gates do not evaluate");
            }
            boolean sawSecurityGate = false;
            for (GateState state : evaluation.gates()) {
                if (!AbacCondition.isSecurityGate(state.gateType())) {
                    continue;
                }
                sawSecurityGate = true;
                boolean overriddenFailure =
                        state.overridden() && ("FAILED".equals(state.status()) || "NO_DATA".equals(state.status()));
                if ("FAILED".equals(state.status()) && !overriddenFailure) {
                    return Attribute.of(Status.FAILED, "security gate " + state.gateType() + " FAILED");
                }
                if ("NO_DATA".equals(state.status()) && !overriddenFailure) {
                    return Attribute.of(Status.NO_DATA, "security gate " + state.gateType() + " has no data");
                }
                if ("PENDING".equals(state.status())) {
                    return Attribute.of(Status.PENDING, "security gate " + state.gateType() + " PENDING");
                }
            }
            if (!sawSecurityGate) {
                return Attribute.of(Status.NO_DATA, "the evaluation carries no security gate");
            }
            return Attribute.of(Status.APPROVED, "every security gate passes (overrides honored)");
        });
    }

    /** Whether the request is a promotion consult (release-grain attributes are its to carry). */
    boolean isPromotion() {
        return request.action() == GoverningAction.PROMOTE;
    }

    private <T> Attribute<T> cached(String key, java.util.function.Supplier<Attribute<T>> resolver) {
        return (Attribute<T>) resolved.computeIfAbsent(key, ignored -> resolver.get());
    }

    /** The environment-name port (injected per evaluation — the authorization module owns no environment rows). */
    public interface EnvironmentNameSource {

        /** The environment's name, or empty when unknown. */
        Optional<String> nameOf(UUID environmentId);
    }
}
