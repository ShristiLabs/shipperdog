package com.shipperdog.api.authorization.internal.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.shipperdog.api.application.ApplicationAccess;
import com.shipperdog.api.authorization.AuthorizationDistributionPolicy;
import com.shipperdog.api.authorization.AuthorizationEntitlement;
import com.shipperdog.api.authorization.internal.abac.AbacPolicyEngine;
import com.shipperdog.api.authorization.internal.cache.DecisionInputs;
import com.shipperdog.api.environment.EnvironmentAccess;
import com.shipperdog.api.identity.UserDirectory;
import com.shipperdog.api.organization.OrganizationAccess;
import com.shipperdog.api.tenant.TenantAccess;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The engine's ordering contract (issue #30's review advisory, folded
 * by issue #31): the PRD 11 chain IS the layer {@code order()} values,
 * and the orchestrator sorts by them — a duplicate position would make
 * two layers' relative order bean-registration luck. This guard pins
 * every shipped layer's position distinct and in chain order, so a
 * future layer cannot accidentally reuse a slot.
 */
class LayerOrderUniquenessTest {

    @Test
    void everyLayerHoldsADistinctPositionInChainOrder() {
        AuthorizationLayer[] layers = {
            new UserLayer(mock(UserDirectory.class)),
            new OrganizationLayer(mock(TenantAccess.class), mock(OrganizationAccess.class)),
            new TenantLayer(mock(DecisionInputs.class)),
            new ApplicationLayer(mock(ApplicationAccess.class)),
            new VersionLayer(mockProvider()),
            new ArtifactLayer(),
            new DistributionLayer(mockPolicyProvider()),
            new AbacLayer(mock(AbacPolicyEngine.class), mock(EnvironmentAccess.class)),
        };
        List<Integer> positions = new java.util.ArrayList<>();
        for (AuthorizationLayer layer : layers) {
            assertThat(positions)
                    .as("%s must hold a position no other layer holds", layer.name())
                    .doesNotContain(layer.order());
            positions.add(layer.order());
        }
        assertThat(positions).isSorted().as("the chain order is the PRD 11 walk, top to bottom");
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<AuthorizationEntitlement> mockProvider() {
        return mock(ObjectProvider.class);
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<AuthorizationDistributionPolicy> mockPolicyProvider() {
        return mock(ObjectProvider.class);
    }
}
