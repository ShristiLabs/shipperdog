package com.shipperdog.api.environment.internal.entity;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * The composite id of {@link EnvironmentTenantEntity}: one environment,
 * one tenant (the {@code ReleaseTargetId} shape). The primary key
 * {@code (environment_id, tenant_id)} is the duplicate authority the
 * assignment write's insert-if-absent composes with.
 */
public final class EnvironmentTenantId implements Serializable {

    private UUID environmentId;
    private UUID tenantId;

    public EnvironmentTenantId() {}

    public EnvironmentTenantId(UUID environmentId, UUID tenantId) {
        this.environmentId = environmentId;
        this.tenantId = tenantId;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof EnvironmentTenantId that)) {
            return false;
        }
        return Objects.equals(environmentId, that.environmentId) && Objects.equals(tenantId, that.tenantId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(environmentId, tenantId);
    }
}
