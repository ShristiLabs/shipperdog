package com.shipperdog.api.authorization.internal.engine;

import com.shipperdog.api.authorization.AuthorizationAction;
import com.shipperdog.api.authorization.AuthorizationContext;
import com.shipperdog.api.authorization.AuthorizationEntitlement;
import com.shipperdog.api.authorization.DenialReason;
import com.shipperdog.api.version.VersionAccess;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * PRD 11/12's fifth chain member: <em>Is the version authorized?</em>
 * — the FR-011 entitlement question plus the version's own lifecycle
 * facts. Three checks, in order:
 *
 * <ul>
 *   <li><strong>Existence and address</strong> — the version exists
 *       and belongs to the addressed application; anything else is
 *       the concealed miss ({@code VERSION_MISSING}).</li>
 *   <li><strong>Lifecycle</strong> — a REVOKED version blocks new
 *       downloads (S4-03, INV-007: revocation never deletes, and
 *       reads stay available forever — INV-006), so
 *       {@link AuthorizationAction#DOWNLOAD} denies on it while
 *       {@link AuthorizationAction#READ_ARTIFACT} passes.</li>
 *   <li><strong>Entitlement</strong> — the FR-011 seam: every
 *       registered {@link AuthorizationEntitlement} contributor is
 *       asked (union semantics). S7-01's single contributor is the
 *       role overlay (console authority); S7-02 registers the
 *       {@code version_access} grant contributor — explicit version,
 *       application-wide, tag-based, latest-N — which is the INV-005
 *       authorization a tester's download requires. With no
 *       contributor entitling the subject, the decision denies here:
 *       deny-by-default is the engine's resting state.</li>
 * </ul>
 */
@Component
class VersionLayer implements AuthorizationLayer {

    private final ObjectProvider<AuthorizationEntitlement> entitlements;

    VersionLayer(ObjectProvider<AuthorizationEntitlement> entitlements) {
        this.entitlements = entitlements;
    }

    @Override
    public int order() {
        return 500;
    }

    @Override
    public String name() {
        return "VERSION";
    }

    @Override
    public boolean applicable(EvaluationState state, AuthorizationContext context) {
        // A version is addressed directly, or an artifact is (an
        // artifact implies its version — resolved below).
        return context.versionId() != null || context.artifactId() != null;
    }

    @Override
    public Verdict evaluate(EvaluationState state, AuthorizationContext context) {
        UUID versionId = context.versionId() != null
                ? context.versionId()
                : state.artifactStateOf(context.artifactId())
                        .map(artifact -> artifact.versionId())
                        .orElse(null);
        if (versionId == null) {
            return Verdict.failed(DenialReason.VERSION_MISSING, "no version addressed");
        }
        Optional<VersionAccess.VersionState> resolved = state.versionStateOf(versionId);
        if (resolved.isEmpty()) {
            return Verdict.failed(DenialReason.VERSION_MISSING, "version not found");
        }
        VersionAccess.VersionState version = resolved.get();
        if (context.applicationId() != null && !context.applicationId().equals(version.applicationId())) {
            return Verdict.failed(DenialReason.VERSION_MISSING, "version belongs to another application");
        }
        if (context.action() == AuthorizationAction.DOWNLOAD && "REVOKED".equals(version.status())) {
            return Verdict.failed(DenialReason.VERSION_REVOKED, "version distribution revoked (INV-007)");
        }
        return entitled(state, context, version);
    }

    private Verdict entitled(EvaluationState state, AuthorizationContext context, VersionAccess.VersionState version) {
        List<AuthorizationEntitlement> contributors =
                entitlements.orderedStream().toList();
        AuthorizationEntitlement.EntitlementQuery query = new AuthorizationEntitlement.EntitlementQuery(
                context.userId(),
                version.organizationId(),
                version.tenantId(),
                version.applicationId(),
                version.versionId(),
                context.action());
        for (AuthorizationEntitlement contributor : contributors) {
            if (contributor.isEntitled(query)) {
                return Verdict.passed("entitled by " + contributor.getClass().getSimpleName());
            }
        }
        return Verdict.failed(
                DenialReason.ENTITLEMENT_MISSING,
                "no entitlement contributor covers the subject (deny-by-default, INV-005)");
    }
}
