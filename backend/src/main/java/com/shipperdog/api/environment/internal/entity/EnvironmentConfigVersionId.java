package com.shipperdog.api.environment.internal.entity;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * The composite id of {@link EnvironmentConfigVersionEntity}: one
 * environment, one monotonic version (the {@code ReleaseTargetId}
 * shape). Value equality and hashing over both components; the primary
 * key {@code (environment_id, version)} is the append-only ledger's
 * concurrent-writer authority — two racing config writes cannot both
 * claim the next version.
 */
public final class EnvironmentConfigVersionId implements Serializable {

    private UUID environmentId;
    private long version;

    public EnvironmentConfigVersionId() {}

    public EnvironmentConfigVersionId(UUID environmentId, long version) {
        this.environmentId = environmentId;
        this.version = version;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof EnvironmentConfigVersionId that)) {
            return false;
        }
        return version == that.version && Objects.equals(environmentId, that.environmentId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(environmentId, version);
    }
}
