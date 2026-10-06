package com.shipperdog.api.authorization.internal.engine;

import com.shipperdog.api.authorization.AuthorizationContext;
import com.shipperdog.api.authorization.DenialReason;
import com.shipperdog.api.organization.OrganizationAccess;
import com.shipperdog.api.tenant.TenantAccess;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * PRD 11's second chain member: <em>Is the subject operating inside an
 * active organization?</em> The organization resolves from the chain
 * itself — the addressed tenant's owner when a tenant is addressed,
 * otherwise the addressed application's (or version's) owning
 * organization — and must exist and be ACTIVE. This mirrors the RBAC
 * snapshot's own rule (roles held in DEACTIVATED organizations grant
 * nothing), so organization deactivation strips resource access and
 * management permissions on the same request, without touching any
 * membership row.
 *
 * <p>Platform administrators pass this layer unconditionally: the
 * PRD 7.1 wildcard holds every permission by construction, and the
 * RBAC evaluator grants them the same — the engine keeps one rule for
 * one subject, not two.
 */
@Component
class OrganizationLayer implements AuthorizationLayer {

    private final TenantAccess tenants;
    private final OrganizationAccess organizations;

    OrganizationLayer(TenantAccess tenants, OrganizationAccess organizations) {
        this.tenants = tenants;
        this.organizations = organizations;
    }

    @Override
    public int order() {
        return 200;
    }

    @Override
    public String name() {
        return "ORGANIZATION";
    }

    @Override
    public boolean applicable(EvaluationState state, AuthorizationContext context) {
        return true;
    }

    @Override
    public Verdict evaluate(EvaluationState state, AuthorizationContext context) {
        if (state.roles().platformAdmin()) {
            return Verdict.passed("platform administrator wildcard");
        }
        Optional<UUID> organizationId = resolveOrganization(state, context);
        if (organizationId.isEmpty()) {
            // Nothing on the chain could resolve an organization: the
            // addressed tenant does not exist (an unknown tenant has no
            // owner), or no resource id was addressed at all. Either
            // way the chain cannot proceed — deny as a tenant miss.
            return Verdict.failed(DenialReason.TENANT_MISSING, "no owning organization resolvable");
        }
        Optional<String> status = organizations.statusOf(organizationId.get());
        if (status.isEmpty()) {
            return Verdict.failed(DenialReason.ORGANIZATION_MISSING, "owning organization not found");
        }
        if (!"ACTIVE".equals(status.get())) {
            return Verdict.failed(DenialReason.ORGANIZATION_INACTIVE, "owning organization " + status.get());
        }
        return Verdict.passed("organization active");
    }

    private Optional<UUID> resolveOrganization(EvaluationState state, AuthorizationContext context) {
        if (context.tenantId() != null) {
            return tenants.owningOrganizationId(context.tenantId());
        }
        return state.applicationOwnershipOf(context.applicationId(), context.versionId())
                .map(ownership -> ownership.organizationId());
    }
}
