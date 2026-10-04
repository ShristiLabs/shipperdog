package com.shipperdog.api.identity.internal.repository;

import com.shipperdog.api.identity.internal.entity.RefreshTokenEntity;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshTokenEntity, UUID> {

    Optional<RefreshTokenEntity> findByTokenHash(String tokenHash);

    @Modifying
    @Query("""
            update RefreshTokenEntity r
               set r.revokedAt = :now, r.updatedAt = :now
             where r.familyId = :familyId and r.revokedAt is null
            """)
    int revokeFamily(@Param("familyId") UUID familyId, @Param("now") Instant now);

    @Modifying
    @Query("""
            update RefreshTokenEntity r
               set r.revokedAt = :now, r.updatedAt = :now
             where r.userId = :userId and r.revokedAt is null
            """)
    int revokeAllForUser(@Param("userId") UUID userId, @Param("now") Instant now);
}
