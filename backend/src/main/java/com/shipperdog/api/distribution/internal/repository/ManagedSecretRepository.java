package com.shipperdog.api.distribution.internal.repository;

import com.shipperdog.api.distribution.internal.entity.ManagedSecretEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Storage for the envelope store's ciphertext (V43, ADR-0008). A
 * plain {@link JpaRepository} keyed by the opaque reference; the
 * store is the only reader and writer (never an API surface).
 */
public interface ManagedSecretRepository extends JpaRepository<ManagedSecretEntity, UUID> {

    /** One stored secret by its reference; the resolution half of the store. */
    Optional<ManagedSecretEntity> findBySecretReference(String secretReference);

    /** Whether the reference is already stored (the reference-minting collision check). */
    boolean existsBySecretReference(String secretReference);
}
