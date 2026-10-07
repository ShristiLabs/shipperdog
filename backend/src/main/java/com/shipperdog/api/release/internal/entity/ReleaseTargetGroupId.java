package com.shipperdog.api.release.internal.entity;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** The composite primary key of {@code release_target_groups}: release x tenant x group (V24). */
public record ReleaseTargetGroupId(UUID releaseId, UUID tenantId, UUID groupId) implements Serializable {

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ReleaseTargetGroupId id)) {
            return false;
        }
        return Objects.equals(releaseId, id.releaseId)
                && Objects.equals(tenantId, id.tenantId)
                && Objects.equals(groupId, id.groupId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(releaseId, tenantId, groupId);
    }
}
