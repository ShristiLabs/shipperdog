package com.shipperdog.api.authorization.internal.engine;

import com.shipperdog.api.application.ApplicationAccess;
import com.shipperdog.api.authorization.AuthorizationAction;
import com.shipperdog.api.authorization.AuthorizationContext;
import com.shipperdog.api.authorization.DenialReason;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * PRD 11/12's fourth chain member: <em>Does the tenant have the
 * application?</em> — the S3-03 effective-visibility question, asked
 * per addressed application. The layer resolves the application from
 * the context (deriving through the version or artifact when only
 * those are addressed) and checks visibility in the dimension the
 * tenant layer resolved:
 *
 * <ul>
 *   <li><strong>TENANT dimension</strong> — the single-application
 *       form of the sanctioned union: the application is tenant-owned
 *       by that tenant, or organization-shared with an ACTIVE
 *       authorization mapping for it ({@code visibleToTenant}, the
 *       same resolution the catalog listing resolves as a set —
 *       ADR-0002 fourth amendment; distribution decides, never the
 *       other way around). For {@link AuthorizationAction#
 *       LIST_APPLICATIONS} decisions this layer is skipped: the set
 *       form IS the listing the engine's decision authorizes, so the
 *       per-item question does not exist yet.</li>
 *   <li><strong>ORGANIZATION dimension</strong> — the application
 *       must belong to the owning organization the subject holds a
 *       role in (an application of another organization is a
 *       concealed miss).</li>
 *   <li><strong>PLATFORM dimension</strong> — existence only.</li>
 * </ul>
 *
 * <p>Lifecycle is NOT this layer's question: an ARCHIVED application
 * stays readable (its versions "fully readable forever", the version
 * lifecycle's own language); writes are the RBAC surface's 409s. The
 * layer answers reachability, never mutability.
 */
@Component
class ApplicationLayer implements AuthorizationLayer {

    private final ApplicationAccess applications;

    ApplicationLayer(ApplicationAccess applications) {
        this.applications = applications;
    }

    @Override
    public int order() {
        return 400;
    }

    @Override
    public String name() {
        return "APPLICATION";
    }

    @Override
    public boolean applicable(EvaluationState state, AuthorizationContext context) {
        if (context.action() == AuthorizationAction.LIST_APPLICATIONS) {
            return false;
        }
        return state.applicationOwnershipOf(context.applicationId(), context.versionId())
                        .isPresent()
                || context.applicationId() != null;
    }

    @Override
    public Verdict evaluate(EvaluationState state, AuthorizationContext context) {
        Optional<ApplicationAccess.Ownership> ownership =
                state.applicationOwnershipOf(context.applicationId(), context.versionId());
        if (ownership.isEmpty()) {
            return Verdict.failed(DenialReason.APPLICATION_MISSING, "application not found");
        }
        EvaluationState.AccessDimension dimension = state.dimension();
        if (dimension == null) {
            return Verdict.failed(DenialReason.TENANT_ACCESS_DENIED, "no resolved access dimension");
        }
        ApplicationAccess.Ownership app = ownership.get();
        return switch (dimension.kind()) {
            case PLATFORM -> Verdict.passed("platform administrator wildcard");
            case ORGANIZATION ->
                app.organizationId().equals(dimension.organizationId())
                        ? Verdict.passed("application of the subject's organization")
                        : Verdict.failed(DenialReason.APPLICATION_NOT_VISIBLE, "application of another organization");
            case TENANT -> visibleToTenant(app, dimension.tenantId());
        };
    }

    private Verdict visibleToTenant(ApplicationAccess.Ownership app, UUID tenantId) {
        if (tenantId == null) {
            return Verdict.failed(DenialReason.APPLICATION_NOT_VISIBLE, "no tenant dimension for a tenant decision");
        }
        if (tenantId.equals(app.tenantId())) {
            return Verdict.passed("tenant-owned application");
        }
        if (applications.visibleToTenant(app.applicationId(), tenantId)) {
            return Verdict.passed("organization-shared application authorized for the tenant");
        }
        return Verdict.failed(DenialReason.APPLICATION_NOT_VISIBLE, "not visible to the tenant");
    }
}
