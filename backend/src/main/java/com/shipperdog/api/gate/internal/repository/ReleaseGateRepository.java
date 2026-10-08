package com.shipperdog.api.gate.internal.repository;

import com.shipperdog.api.gate.internal.entity.ReleaseGateEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Storage for the current gate state per release (V1's
 * {@code release_gates}, expanded by V31 — issue #42, S10-03). A pure
 * child of the seam-served release: the service resolves the release
 * through {@code ReleaseAccess.factsOf} (the scoped port) before any
 * read or write here, so isolation is enforced at the parent (the V25
 * doctrine — no owning-dimension snapshot, no seam repository).
 */
public interface ReleaseGateRepository extends JpaRepository<ReleaseGateEntity, UUID> {

    /** The release's current gate states, display order by type. */
    List<ReleaseGateEntity> findByReleaseIdOrderByGateTypeAsc(UUID releaseId);

    /** The current state of one gate of one release (V1's unique key as the lookup). */
    Optional<ReleaseGateEntity> findByReleaseIdAndGateType(UUID releaseId, String gateType);
}
