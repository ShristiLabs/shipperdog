package com.shipperdog.api.release.internal.entity;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** The composite primary key of {@code release_targets}: release x tenant (V1). */
public record ReleaseTargetId(UUID releaseId, UUID tenantId) implements Serializable {

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ReleaseTargetId id)) {
            return false;
        }
        return Objects.equals(releaseId, id.releaseId) && Objects.equals(tenantId, id.tenantId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(releaseId, tenantId);
    }
}
