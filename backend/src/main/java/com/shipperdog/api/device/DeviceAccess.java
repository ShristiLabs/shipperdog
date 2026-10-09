package com.shipperdog.api.device;

import java.util.Optional;
import java.util.UUID;

/**
 * The device module's public surface for the feedback module (issue
 * #48, S11-03): the registration-link facts a feedback submission
 * validates its {@code device_id} against, and the context tuple the
 * report's detail resolves once the link is stored. Implementation
 * lives in the module's {@code .internal.} packages; cross-module
 * callers must use only this interface (enforced by ArchUnit).
 *
 * <p>The facts deliberately carry no identifier: the UDID/serial is
 * semi-sensitive (the issue's privacy note — admin-only visibility),
 * and the feedback surfaces that consume this port are read by every
 * {@code app:feedback:read} holder, a wider circle than the tenant
 * administrators the identifier is visible to.
 */
public interface DeviceAccess {

    /**
     * The device's link facts: the owning tester and tenant, the
     * status (an ACTIVE device alone is linkable), and the platform /
     * model / OS context the report surfaces display. Empty when no
     * such device exists.
     */
    Optional<DeviceFacts> factsOf(UUID deviceId);

    /** The projection {@link #factsOf} answers: everything but the identifier. */
    record DeviceFacts(
            UUID id, UUID testerId, UUID tenantId, String status, String platform, String model, String osVersion) {}
}
