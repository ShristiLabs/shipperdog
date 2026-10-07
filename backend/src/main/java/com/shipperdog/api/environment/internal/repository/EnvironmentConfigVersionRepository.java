package com.shipperdog.api.environment.internal.repository;

import com.shipperdog.api.environment.internal.entity.EnvironmentConfigVersionEntity;
import com.shipperdog.api.environment.internal.entity.EnvironmentConfigVersionId;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Storage for the configuration snapshot ledger (V27). Insert and read
 * only — the append-only discipline ({@code ReleaseManifestRepository}'s
 * shape): no update, no delete, ever. The composite primary key
 * {@code (environment_id, version)} is the concurrent-writer authority:
 * two racing config writes cannot both claim the next version, and the
 * service's bounded retry translates the loser.
 */
public interface EnvironmentConfigVersionRepository
        extends JpaRepository<EnvironmentConfigVersionEntity, EnvironmentConfigVersionId> {

    /** The current snapshot: the highest version row, or empty when never configured (version 0). */
    Optional<EnvironmentConfigVersionEntity> findFirstByEnvironmentIdOrderByVersionDesc(UUID environmentId);

    /** One historical snapshot (the by-version read, AC2). */
    Optional<EnvironmentConfigVersionEntity> findByEnvironmentIdAndVersion(UUID environmentId, long version);

    /** The version ledger listing (chronological, the history-read convention). */
    Page<EnvironmentConfigVersionEntity> findByEnvironmentIdOrderByVersionAsc(UUID environmentId, Pageable pageable);

    /** The next version of a write: the current highest plus one (the composite key guards the race). */
    default long nextVersionOf(UUID environmentId) {
        return findFirstByEnvironmentIdOrderByVersionDesc(environmentId)
                .map(row -> row.getVersion() + 1)
                .orElse(1L);
    }
}
