package com.shipperdog.api.environment;

import java.util.UUID;

/**
 * The environment module's provisioning callback for the organization
 * module (issue #58, S9-04): every newly created organization is seeded
 * with FR-025's five default environments (Development, QA, UAT, Pilot,
 * Production — editable afterwards like any environment, and the
 * organization can add custom ones). The organization module calls this
 * inside the organization-creation transaction, so a seeding failure
 * fails the whole creation — an organization never lands
 * half-provisioned (the S6-02 {@code TesterGroupProvisioning}
 * precedent). Implementation lives in the module's {@code .internal.}
 * packages; the organization module must use only this interface
 * (enforced by ArchUnit).
 */
public interface EnvironmentProvisioning {

    /**
     * Seeds the default environments under the freshly created
     * organization, inside the caller's transaction. Idempotent by
     * code: a default whose code is already taken in the organization
     * is skipped, never duplicated.
     *
     * @param organizationId the just-created organization (environments
     *        are organization-grain)
     * @param actorId        the creating administrator (audit trail)
     */
    void seedDefaultEnvironments(UUID organizationId, UUID actorId);
}
