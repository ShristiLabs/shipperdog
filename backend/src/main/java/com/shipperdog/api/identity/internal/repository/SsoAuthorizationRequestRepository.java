package com.shipperdog.api.identity.internal.repository;

import com.shipperdog.api.identity.internal.entity.SsoAuthorizationRequestEntity;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SsoAuthorizationRequestRepository extends JpaRepository<SsoAuthorizationRequestEntity, UUID> {

    Optional<SsoAuthorizationRequestEntity> findByStateHash(String stateHash);

    long deleteByExpiresAtBefore(Instant cutoff);

    /**
     * The single-use consume as one atomic statement: exactly one
     * concurrent callback wins the state (S14-01 review finding -- a
     * check-then-save read would let a racing replay through).
     */
    @Modifying
    @Query("UPDATE SsoAuthorizationRequestEntity r SET r.consumedAt = :now "
            + "WHERE r.stateHash = :stateHash AND r.consumedAt IS NULL AND r.expiresAt > :now")
    int consumeByStateHash(@Param("stateHash") String stateHash, @Param("now") Instant now);
}
