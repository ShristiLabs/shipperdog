package com.shipperdog.api.release.internal.entity;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** The composite primary key of {@code release_artifacts}: release x artifact (V24). */
public record ReleaseArtifactId(UUID releaseId, UUID artifactId) implements Serializable {

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ReleaseArtifactId id)) {
            return false;
        }
        return Objects.equals(releaseId, id.releaseId) && Objects.equals(artifactId, id.artifactId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(releaseId, artifactId);
    }
}
