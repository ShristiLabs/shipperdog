/**
 * Integrations with external systems: the inbound, signature-verified
 * webhook receivers CI systems and stores deliver to (FR-022, S12-03)
 * and, later, the outbound adapters that ride them.
 *
 * <p>Internal implementation details belong in an {@code .internal.} subpackage
 * here; other modules must not import it (enforced by ArchUnit).
 */
package com.shipperdog.api.integration;
