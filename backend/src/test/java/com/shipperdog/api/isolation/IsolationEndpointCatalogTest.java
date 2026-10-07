package com.shipperdog.api.isolation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The completeness guard of the endpoint registry (S7-05, issue #34):
 * every GET route the SERVED OpenAPI spec declares must either be in
 * {@link IsolationEndpointRegistry}'s catalog (and therefore inside
 * the cross-tenant matrix) or on the explicit, justified allowlist. A
 * new read endpoint cannot silently escape the isolation matrix — the
 * suite fails the moment it appears in the spec unregistered, which is
 * the "new endpoints auto-join the matrix" technical note made
 * mechanical (issue acceptance criterion 1's "every endpoint", held
 * true by construction instead of by memory).
 */
class IsolationEndpointCatalogTest extends IsolationScenarioHarness {

    @Test
    void everyServedReadRouteIsInTheMatrixOrExplicitlyAllowlisted() {
        JsonNode spec = get(null, "/v3/api-docs", 200);
        Set<String> specGetRoutes = new LinkedHashSet<>();
        spec.get("paths").propertyNames().forEach(path -> {
            JsonNode operations = spec.get("paths").get(path);
            if (operations.has("get")) {
                specGetRoutes.add("GET " + path);
            }
        });

        Set<String> cataloged = new LinkedHashSet<>();
        for (IsolationEndpointRegistry.Entry entry : IsolationEndpointRegistry.readSurfaces()) {
            cataloged.add(entry.method() + " " + entry.template());
        }

        List<String> unregistered = new ArrayList<>();
        for (String route : specGetRoutes) {
            if (!cataloged.contains(route)
                    && !IsolationEndpointRegistry.allowlistedGetRoutes().contains(route)) {
                unregistered.add(route);
            }
        }
        assertThat(unregistered)
                .as(
                        "GET routes served but absent from the isolation matrix registry; register each in"
                                + " IsolationEndpointRegistry.readSurfaces() (or allowlist with justification): %s",
                        unregistered)
                .isEmpty();

        // The reverse direction: a catalog entry that no longer exists
        // in the spec is stale registry weight — remove it (the matrix
        // would otherwise probe dead routes and rot).
        List<String> stale = new ArrayList<>();
        for (String route : cataloged) {
            if (!specGetRoutes.contains(route)) {
                stale.add(route);
            }
        }
        assertThat(stale)
                .as("catalog entries whose routes no longer exist in the served spec: %s", stale)
                .isEmpty();
    }
}
