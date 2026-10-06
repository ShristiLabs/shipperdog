package com.shipperdog.api.authorization.internal.engine;

import com.shipperdog.api.authorization.AuthorizationContext;
import com.shipperdog.api.authorization.DenialReason;
import com.shipperdog.api.tester.TenantTesterMembershipAccess;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * PRD 11's third chain member: <em>Does the subject have tenant
 * access?</em> — the layer that decides HOW the subject reaches the
 * chain's resources, because a subject arrives in one of three
 * dimensions:
 *
 * <ul>
 *   <li><strong>PLATFORM</strong> — platform administrators (the PRD
 *       7.1 wildcard; reads stay scoped everywhere else, but the
 *       engine, like the RBAC evaluator, never denies them here).</li>
 *   <li><strong>TENANT</strong> — either the tester persona: a linked
 *       tester person record whose access is EFFECTIVE (the S6-03
 *       two-switches rule, a LIVE query — person record ACTIVE and
 *       membership ACTIVE, so a suspension or removal denies on the
 *       very next evaluation), or the console persona: a tenant role
 *       or the owning organization's overlay role in the snapshot
 *       (the RBAC union rule). A tester reaching this dimension holds
 *       no role at all — the membership IS the access.</li>
 *   <li><strong>ORGANIZATION</strong> — organization-shared
 *       applications (S3-02/PRD 14) addressed without a tenant: an
 *       organization role reaches them; a pure tester does not, because
 *       a tester's access is tenant-scoped by construction (the
 *       membership rows live in tenants).</li>
 * </ul>
 *
 * <p>The resolved dimension is recorded on the evaluation state for
 * the application layer's visibility question. The tester persona is
 * checked first and the role persona second: nothing forbids a user
 * being both, and either path legitimately grants tenant access.
 */
@Component
class TenantLayer implements AuthorizationLayer {

    private final TenantTesterMembershipAccess testerMemberships;

    TenantLayer(TenantTesterMembershipAccess testerMemberships) {
        this.testerMemberships = testerMemberships;
    }

    @Override
    public int order() {
        return 300;
    }

    @Override
    public String name() {
        return "TENANT";
    }

    @Override
    public boolean applicable(EvaluationState state, AuthorizationContext context) {
        return true;
    }

    @Override
    public Verdict evaluate(EvaluationState state, AuthorizationContext context) {
        if (state.roles().platformAdmin()) {
            state.recordDimension(EvaluationState.AccessDimension.platform());
            return Verdict.passed("platform administrator wildcard");
        }
        UUID tenantId = resolveTenant(state, context);
        if (tenantId == null) {
            return decideAtOrganization(state, context);
        }
        return decideAtTenant(state, context, tenantId);
    }

    private Verdict decideAtTenant(EvaluationState state, AuthorizationContext context, UUID tenantId) {
        Optional<UUID> testerId = state.testerIdOfSubject(context.userId());
        if (testerId.isPresent()) {
            UUID testerOrganization = state.roles().owningOrganization(tenantId).orElse(null);
            boolean sameOrganization = testerOrganization != null
                    && state.organizationOfTester(testerId.get()).stream()
                            .anyMatch(org -> org.equals(testerOrganization));
            if (sameOrganization && testerMemberships.isEffectivelyActive(tenantId, testerId.get())) {
                state.recordDimension(new EvaluationState.AccessDimension(
                        EvaluationState.AccessDimension.Kind.TENANT, testerOrganization, tenantId));
                return Verdict.passed("effective tester membership (two switches on)");
            }
        }
        if (holdsTenantDimensionRole(state, tenantId)) {
            UUID organizationId = state.roles().owningOrganization(tenantId).orElse(null);
            state.recordDimension(new EvaluationState.AccessDimension(
                    EvaluationState.AccessDimension.Kind.TENANT, organizationId, tenantId));
            return Verdict.passed("tenant or owning-organization role");
        }
        return Verdict.failed(DenialReason.TENANT_ACCESS_DENIED, "no effective membership and no role in the tenant");
    }

    private Verdict decideAtOrganization(EvaluationState state, AuthorizationContext context) {
        UUID organizationId = state.applicationOwnershipOf(context.applicationId(), context.versionId())
                .map(ownership -> ownership.organizationId())
                .orElse(null);
        if (organizationId != null && state.roles().orgRole(organizationId) != null) {
            state.recordDimension(new EvaluationState.AccessDimension(
                    EvaluationState.AccessDimension.Kind.ORGANIZATION, organizationId, null));
            return Verdict.passed("organization role (organization-shared application)");
        }
        return Verdict.failed(DenialReason.TENANT_ACCESS_DENIED, "no organization role and no tenant addressed");
    }

    private boolean holdsTenantDimensionRole(EvaluationState state, UUID tenantId) {
        if (state.roles().tenantRole(tenantId) != null) {
            return true;
        }
        return state.roles()
                .owningOrganization(tenantId)
                .map(state.roles()::orgRole)
                .map(role -> role != null)
                .orElse(false);
    }

    private UUID resolveTenant(EvaluationState state, AuthorizationContext context) {
        if (context.tenantId() != null) {
            return context.tenantId();
        }
        return state.applicationOwnershipOf(context.applicationId(), context.versionId())
                .map(ownership -> ownership.tenantId())
                .orElse(null);
    }
}
