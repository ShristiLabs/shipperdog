package com.shipperdog.api.version;

import java.util.UUID;

/**
 * INV-004's freeze authority (issue #19, S4-03): the guard every
 * artifact-mutating surface must consult before adding artifacts to a
 * version — once a version is in a terminal lifecycle state or has any
 * distribution record, its artifact set is frozen ("released versions
 * cannot have their binary changed"; the story's AC1 makes the trigger
 * any distribution record: an M2 release or an M1 download grant).
 *
 * <p>The check is modeled as this guard rather than direct table checks
 * precisely so it survives the later distribution models: the
 * distribution-record leg is answered by
 * {@link VersionDistributionContributor} implementations that the
 * release (M2) and download-grant (E7, S7-02) modules register when
 * they land — the version module never learns their schemas, and they
 * never duplicate the version module's lifecycle state. In the M1
 * kernel the contributor list is empty, so the lifecycle leg (ARCHIVED
 * or REVOKED status) is the whole freeze condition; E5's upload path
 * (S5-01) wires this guard in unchanged.
 *
 * <p>On rejection the implementation throws the version module's
 * {@link VersionException} with the public code
 * {@code VERSION_IMMUTABLE} (409). Cross-module callers that prefer
 * their own envelope catch and re-render it; the concealment contract
 * is preserved either way — an unknown or out-of-scope version id
 * renders the standard concealed miss before any freeze reasoning
 * runs.
 */
public interface VersionDistributionGuard {

    /**
     * Asserts the version's artifact set is still mutable (INV-004):
     * the version must not be in a terminal lifecycle state and must
     * carry no distribution record. A violation throws the 409
     * {@code VERSION_IMMUTABLE}; an unknown, out-of-scope, or
     * wrong-application version id throws the concealed 404.
     *
     * @param applicationId the application addressing the route
     * @param versionId the version whose artifact set is about to grow
     */
    void requireArtifactsMutable(UUID applicationId, UUID versionId);
}
