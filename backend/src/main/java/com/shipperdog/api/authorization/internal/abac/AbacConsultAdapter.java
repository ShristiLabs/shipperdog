package com.shipperdog.api.authorization.internal.abac;

import com.shipperdog.api.authorization.AbacConsult;
import com.shipperdog.api.authorization.internal.abac.AbacPolicyEntity.GoverningAction;
import com.shipperdog.api.environment.EnvironmentAccess;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The promotion consult's binding (issue #70, S14-04): the public
 * {@link AbacConsult} port over the engine — one evaluation, the
 * promotion surface's audited leg. Denials carry the refusal text
 * the promote 409 renders; the engine records the ABAC_POLICY_DENIED
 * audit event (the layered path rides AUTHORIZATION_DECISION instead,
 * so no decision is audited twice).
 */
@Component
class AbacConsultAdapter implements AbacConsult {

    private final AbacPolicyEngine engine;
    private final EnvironmentAccess environments;

    AbacConsultAdapter(AbacPolicyEngine engine, EnvironmentAccess environments) {
        this.engine = engine;
        this.environments = environments;
    }

    @Override
    public Optional<String> promotionDenial(AbacPromotionRequest request) {
        AbacRequest internal = new AbacRequest(
                request.organizationId(),
                request.userId(),
                GoverningAction.PROMOTE,
                request.applicationId(),
                request.environmentId(),
                null,
                request.releaseId(),
                request.ring());
        AbacResult result = engine.evaluate(
                internal, id -> environments.stateOf(id).map(EnvironmentAccess.EnvironmentState::name), "promotion");
        return result.denied() ? Optional.of(result.refusal()) : Optional.empty();
    }
}
