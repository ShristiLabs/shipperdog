package com.shipperdog.api.authorization.internal.engine;

import com.shipperdog.api.application.ApplicationAccess;
import com.shipperdog.api.authorization.AuthorizationAction;
import com.shipperdog.api.authorization.AuthorizationContext;
import com.shipperdog.api.authorization.DenialReason;
import com.shipperdog.api.authorization.internal.abac.AbacPolicyEngine;
import com.shipperdog.api.authorization.internal.abac.AbacPolicyEntity.GoverningAction;
import com.shipperdog.api.authorization.internal.abac.AbacRequest;
import com.shipperdog.api.authorization.internal.abac.AbacResult;
import com.shipperdog.api.environment.EnvironmentAccess;
import com.shipperdog.api.version.VersionAccess;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The ABAC refinement layer of the layered engine (issue #70,
 * S14-04, PRD 25/38): the chain's FINAL member — position 800, after
 * DISTRIBUTION — where the organization's attribute policies get the
 * last word. The composition is deny-override by construction (AC2):
 * a layer can only FAIL (deny) or pass; this one passing grants
 * nothing the earlier layers did not already establish, and it
 * failing narrows what they allowed — ABAC never widens.
 *
 * <p>Applicability is the zero-cost fast path: organizations with no
 * enabled policy see the layer SKIPPED (one cached probe, no policy
 * load, no attribute resolution — the AC5 budget is untouched for
 * every organization that never authors a policy). When policies
 * exist, only the ones governing the decision's action and matching
 * its selectors evaluate, and only the attributes they reference
 * resolve (the snapshot's lazy discipline).
 *
 * <p>The denial's DETAIL is the decision explanation AC3 names: the
 * winning rule's identity, version, and the evaluated attributes —
 * exactly what the inspection API's chain view returns and the
 * AUTHORIZATION_DECISION audit event carries (the layered path rides
 * that standing event; the engine emits no duplicate).
 */
@Component
class AbacLayer implements AuthorizationLayer {

    private final AbacPolicyEngine engine;
    private final EnvironmentAccess environments;

    AbacLayer(AbacPolicyEngine engine, EnvironmentAccess environments) {
        this.engine = engine;
        this.environments = environments;
    }

    @Override
    public int order() {
        // After DISTRIBUTION (700): the attribute policies refine the
        // fully-resolved chain, never pre-empt it.
        return 800;
    }

    @Override
    public String name() {
        return "ABAC";
    }

    @Override
    public boolean applicable(EvaluationState state, AuthorizationContext context) {
        // The chain's owning organization (resolved by the layers
        // above): no organization means nothing to refine, and no
        // enabled policies means the layer sits out entirely — the
        // fast path every policy-less organization takes.
        UUID organizationId = organizationOf(state, context);
        return organizationId != null && engine.anyEnabledPolicy(organizationId);
    }

    @Override
    public AuthorizationLayer.Verdict evaluate(EvaluationState state, AuthorizationContext context) {
        UUID organizationId = organizationOf(state, context);
        if (organizationId == null) {
            return AuthorizationLayer.Verdict.passed("no owning organization to refine");
        }
        AbacRequest request = new AbacRequest(
                organizationId,
                context.userId(),
                governingActionOf(context.action()),
                context.applicationId(),
                // Engine contexts carry no environment dimension (the
                // release-grain attributes are the promotion consult's
                // to carry); a policy referencing them on an engine
                // action is refused at write.
                null,
                context.platform(),
                null,
                null);
        AbacResult result = engine.evaluateForLayers(
                request, id -> environments.stateOf(id).map(EnvironmentAccess.EnvironmentState::name));
        if (!result.denied()) {
            return AuthorizationLayer.Verdict.passed("no applicable ABAC policy denied the request");
        }
        return AuthorizationLayer.Verdict.failed(
                result.fault() ? DenialReason.ENGINE_FAULT : DenialReason.ABAC_POLICY_DENIED,
                "abac " + result.refusal());
    }

    /** The decision's engine action in the policy vocabulary. */
    private static GoverningAction governingActionOf(AuthorizationAction action) {
        return switch (action) {
            case LIST_APPLICATIONS -> GoverningAction.LIST_APPLICATIONS;
            case READ_ARTIFACT -> GoverningAction.READ_ARTIFACT;
            case DOWNLOAD -> GoverningAction.DOWNLOAD;
        };
    }

    /**
     * The chain's owning organization: the addressed application's,
     * the addressed version's, the addressed tenant's, or — when
     * none is addressed — the tenant layer's recorded dimension.
     * Null when the chain carries none (the layer then abstains).
     */
    private static UUID organizationOf(EvaluationState state, AuthorizationContext context) {
        if (context.applicationId() != null) {
            return state.applicationOwnershipOf(context.applicationId(), context.versionId())
                    .map(ApplicationAccess.Ownership::organizationId)
                    .orElse(null);
        }
        if (context.versionId() != null) {
            return state.versionStateOf(context.versionId())
                    .map(VersionAccess.VersionState::organizationId)
                    .orElse(null);
        }
        if (context.tenantId() != null) {
            return state.roles().owningOrganization(context.tenantId()).orElse(null);
        }
        EvaluationState.AccessDimension dimension = state.dimension();
        return dimension == null ? null : dimension.organizationId();
    }
}
