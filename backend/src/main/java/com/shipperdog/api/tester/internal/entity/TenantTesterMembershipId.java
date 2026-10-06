package com.shipperdog.api.tester.internal.entity;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Composite identity of {@code tenant_tester_memberships} (V1 baseline,
 * PRD 13): a membership IS the {@code (tenant_id, tester_id)} pair — the
 * table's primary key — which makes duplicate links a database-refused
 * impossibility and re-linking naturally idempotent (issue #28,
 * acceptance criterion 1).
 */
public record TenantTesterMembershipId(UUID tenantId, UUID testerId) implements Serializable {

    public TenantTesterMembershipId {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(testerId, "testerId");
    }
}
