package com.shipperdog.api.config.idempotency;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Storage for the idempotency ledger (issue #8). */
public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKeyEntity, UUID> {

    /**
     * Claims a key slot atomically: 1 when this call created the row, 0 when
     * an earlier request already holds it. The ON CONFLICT DO NOTHING form
     * keeps the claim a single statement — no exception, no rollback-only
     * transaction, no race window between check and insert.
     */
    @Modifying
    @Query(
            value = "insert into idempotency_keys "
                    + "(principal_id, key_hash, method, request_path, state, expires_at) "
                    + "values (:principalId, :keyHash, :method, :requestPath, 'PROCESSING', :expiresAt) "
                    + "on conflict (principal_id, key_hash, method, request_path) do nothing",
            nativeQuery = true)
    int insertIfAbsent(
            @Param("principalId") UUID principalId,
            @Param("keyHash") String keyHash,
            @Param("method") String method,
            @Param("requestPath") String requestPath,
            @Param("expiresAt") Instant expiresAt);

    /** Deletes one row only while it is still expired; 0 when a peer re-claimed it first. */
    @Modifying
    @Query("delete from IdempotencyKeyEntity k where k.id = :id and k.expiresAt < :now")
    int deleteIfExpired(@Param("id") UUID id, @Param("now") Instant now);

    Optional<IdempotencyKeyEntity> findByPrincipalIdAndKeyHashAndMethodAndRequestPath(
            UUID principalId, String keyHash, String method, String requestPath);

    @Modifying
    @Query("delete from IdempotencyKeyEntity k where k.expiresAt < :now")
    int deleteExpiredBefore(Instant now);
}
