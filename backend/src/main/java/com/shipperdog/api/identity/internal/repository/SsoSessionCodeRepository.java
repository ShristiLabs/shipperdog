package com.shipperdog.api.identity.internal.repository;

import com.shipperdog.api.identity.internal.entity.SsoSessionCodeEntity;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SsoSessionCodeRepository extends JpaRepository<SsoSessionCodeEntity, UUID> {

    Optional<SsoSessionCodeEntity> findByCodeHash(String codeHash);

    long deleteByExpiresAtBefore(Instant cutoff);

    /**
     * The single-use consume as one atomic statement: exactly one
     * concurrent exchange wins the code (S14-01 review finding -- a
     * check-then-save read would let a racing replay through).
     */
    @Modifying
    @Query("UPDATE SsoSessionCodeEntity c SET c.consumedAt = :now "
            + "WHERE c.codeHash = :codeHash AND c.consumedAt IS NULL AND c.expiresAt > :now")
    int consumeByCodeHash(@Param("codeHash") String codeHash, @Param("now") Instant now);
}
