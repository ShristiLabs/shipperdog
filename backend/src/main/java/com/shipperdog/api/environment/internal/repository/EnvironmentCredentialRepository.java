package com.shipperdog.api.environment.internal.repository;

import com.shipperdog.api.environment.internal.entity.EnvironmentCredentialEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Storage for the credential bindings (V27). A plain
 * {@link JpaRepository} — the plain-child doctrine again: references
 * only, resolved through the environment first. The binding write
 * replaces the set (same-name rows keep their slot and re-point their
 * reference; removed names go; new names arrive).
 */
public interface EnvironmentCredentialRepository extends JpaRepository<EnvironmentCredentialEntity, UUID> {

    /** The bindings of one environment, name-ordered for the stable view. */
    List<EnvironmentCredentialEntity> findByEnvironmentIdOrderByNameAsc(UUID environmentId);

    /** The paginated read of one environment's bindings (the list-endpoint convention). */
    Page<EnvironmentCredentialEntity> findByEnvironmentId(UUID environmentId, Pageable pageable);

    /** One binding of the verify probe. */
    Optional<EnvironmentCredentialEntity> findByIdAndEnvironmentId(UUID id, UUID environmentId);

    /** Deletes the removed half of a replace-set binding write (never individually addressed). */
    void deleteByEnvironmentIdAndIdIn(UUID environmentId, List<UUID> ids);
}
