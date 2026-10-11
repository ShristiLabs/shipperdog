package com.shipperdog.api.identity.internal.repository;

import com.shipperdog.api.identity.internal.entity.SamlAuthorizationRequestEntity;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SamlAuthorizationRequestRepository extends JpaRepository<SamlAuthorizationRequestEntity, UUID> {

    Optional<SamlAuthorizationRequestEntity> findByRequestIdHash(String requestIdHash);

    long deleteByExpiresAtBefore(Instant cutoff);

    /**
     * The single-use consume as one atomic statement (the OIDC state
     * row's doctrine): exactly one concurrent ACS callback wins the
     * request; a racing replay loses without a check-then-save window.
     */
    @Modifying
    @Query("UPDATE SamlAuthorizationRequestEntity r SET r.consumedAt = :now "
            + "WHERE r.requestIdHash = :requestIdHash AND r.consumedAt IS NULL AND r.expiresAt > :now")
    int consumeByRequestIdHash(@Param("requestIdHash") String requestIdHash, @Param("now") Instant now);
}
