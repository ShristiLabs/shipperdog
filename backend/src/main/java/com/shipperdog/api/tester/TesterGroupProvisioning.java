package com.shipperdog.api.tester;

import java.util.UUID;

/**
 * The tester module's provisioning callback for the tenant module
 * (issue #27, S6-02): every newly created tenant is seeded with the
 * configurable default tester groups (the PRD 8.9 examples — Internal
 * QA, Tenant QA, UAT, Pilot, Executive Beta, External Beta — editable
 * afterwards like any group). The tenant module calls this inside the
 * tenant-creation transaction, so a seeding failure fails the whole
 * creation — a tenant never lands half-provisioned. Implementation
 * lives in the module's {@code .internal.} packages; the tenant module
 * must use only this interface (enforced by ArchUnit).
 */
public interface TesterGroupProvisioning {

    /**
     * Seeds the configured default groups under the freshly created
     * tenant, inside the caller's transaction.
     *
     * @param tenantId the just-created tenant (groups are tenant-scoped)
     * @param actorId  the creating administrator (audit trail)
     */
    void seedDefaultGroups(UUID tenantId, UUID actorId);
}
