package com.shipperdog.api.config;

import static io.micrometer.observation.tck.TestObservationRegistryAssert.assertThat;

import io.micrometer.observation.Observation;
import io.micrometer.observation.tck.TestObservationRegistry;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit checks for the entity-attribute seam (issue #7): ids land on the
 * current observation as high-cardinality key values (span attributes,
 * never metric tags), and calls outside an observation or with a null id
 * are silent no-ops.
 */
class TraceAttributesTest {

    private final TestObservationRegistry registry = TestObservationRegistry.create();
    private final TraceAttributes trace = new TraceAttributes(registry);

    @Test
    void entityIdsAttachToTheCurrentObservation() {
        UUID organizationId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();

        Observation observation = Observation.createNotStarted("test", registry).start();
        try (var scope = observation.openScope()) {
            trace.organization(organizationId);
            trace.tenant(tenantId);
        }
        observation.stop();

        assertThat(registry)
                .hasObservationWithNameEqualTo("test")
                .that()
                .hasHighCardinalityKeyValue("organization_id", organizationId.toString())
                .hasHighCardinalityKeyValue("tenant_id", tenantId.toString());
    }

    @Test
    void callsWithoutAnActiveObservationAreNoOps() {
        trace.organization(UUID.randomUUID());
        trace.application(null);
        assertThat(registry).doesNotHaveAnyRemainingCurrentObservation();
    }
}
