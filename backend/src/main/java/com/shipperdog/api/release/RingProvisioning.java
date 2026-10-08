package com.shipperdog.api.release;

import java.util.UUID;

/**
 * The release module's provisioning hook for the organization module
 * (issue #43, S10-04): FR-017's five default rings materialize
 * organization-grain inside the organization-creation transaction, the
 * {@code EnvironmentProvisioning} precedent (S9-04) — a seeding failure
 * fails the whole creation, seeds are idempotent, and no audit event
 * records them (the seeds are part of the creation, not edits of it).
 * Implementation lives in the release module's {@code .internal.}
 * packages; cross-module callers must use only this interface
 * (enforced by ArchUnit).
 */
public interface RingProvisioning {

    /**
     * Seeds FR-017's five defaults (Engineering, QA, Tenant Pilot, UAT,
     * Production) under the freshly created organization. Idempotent per
     * code: an organization that already defined a code keeps its own.
     */
    void seedDefaultRings(UUID organizationId, UUID actorId);
}
