package com.shipperdog.api.config;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Attaches entity context to the current request's trace (issue #7,
 * acceptance criterion 1, PRD section 28): business services call these
 * methods with the ids they already hold, and the ids become span
 * attributes ({@code organization_id}, {@code tenant_id}, ...) on the
 * request's active span — Spring Security wraps dispatch in a
 * {@code secured request} observation, so business code usually runs on
 * that child span rather than the HTTP server span. Micrometer Tracing
 * copies high-cardinality observation key values onto the span at stop.
 *
 * <p>The API deliberately takes {@link UUID} identifiers only: attribute
 * values are entity ids, never names or PII (issue technical notes).
 * High-cardinality key values never reach metric tags, so per-request
 * ids cannot inflate the {@code shipperdog_*} metric cardinality.
 *
 * <p>Every method is a no-op outside an active observation (schedulers,
 * startup), keeping callers free of tracing concerns.
 */
@Component
public class TraceAttributes {

    private final ObservationRegistry observations;

    public TraceAttributes(ObservationRegistry observations) {
        this.observations = observations;
    }

    /** Records the organization the current request operates on. */
    public void organization(UUID organizationId) {
        attribute("organization_id", organizationId);
    }

    /** Records the tenant the current request operates on. */
    public void tenant(UUID tenantId) {
        attribute("tenant_id", tenantId);
    }

    /** Records the application the current request operates on (E3+). */
    public void application(UUID applicationId) {
        attribute("application_id", applicationId);
    }

    /** Records the application version the current request operates on (E4+). */
    public void version(UUID versionId) {
        attribute("version_id", versionId);
    }

    /** Records the artifact the current request operates on (E5+). */
    public void artifact(UUID artifactId) {
        attribute("artifact_id", artifactId);
    }

    /** Records the release the current request operates on (E9+). */
    public void release(UUID releaseId) {
        attribute("release_id", releaseId);
    }

    /** Records the tester the current request operates on (E6+, issue #26). */
    public void tester(UUID testerId) {
        attribute("tester_id", testerId);
    }

    /** Records the tester group the current request operates on (E6+, issue #27). */
    public void testerGroup(UUID groupId) {
        attribute("tester_group_id", groupId);
    }

    private void attribute(String key, UUID id) {
        if (id == null) {
            return;
        }
        Observation current = observations.getCurrentObservation();
        if (current != null) {
            current.highCardinalityKeyValue(KeyValue.of(key, id.toString()));
        }
    }
}
