package com.shipperdog.api.version.internal.entity;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * The composite identity of a {@link VersionTagEntity} row (V13): the
 * version it tags and the tag itself — a tag is present at most once
 * per version, which is exactly what the {@code version_tags} primary
 * key pins.
 */
public class VersionTagId implements Serializable {

    private UUID versionId;

    private String tag;

    /** Constructed and populated reflectively by the JPA provider. */
    public VersionTagId() {}

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof VersionTagId that)) {
            return false;
        }
        return Objects.equals(versionId, that.versionId) && Objects.equals(tag, that.tag);
    }

    @Override
    public int hashCode() {
        return Objects.hash(versionId, tag);
    }
}
