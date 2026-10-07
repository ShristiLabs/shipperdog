package com.shipperdog.api.release.internal.repository;

import com.shipperdog.api.release.internal.entity.RingEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Storage for the ring reference rows (V24). A plain {@link JpaRepository}
 * by design — rings are organization-grain reference data (the tenth
 * amendment's pattern): a system ring carries no organization at all and
 * a custom ring (E10) carries only the organization dimension, so there
 * is no tenant dimension for the seam's predicate to filter by. Every
 * question is an explicit organization-scoped query.
 */
public interface RingRepository extends JpaRepository<RingEntity, UUID> {

    /**
     * Resolves a ring by code within the organization's visible
     * vocabulary: the organization's own ring when it defined the code,
     * else the system default. Empty when neither exists — the creation
     * ring validation's miss (issue AC4).
     */
    @Query("select r from RingEntity r where r.code = :code "
            + "and (r.organizationId is null or r.organizationId = :organizationId) "
            + "order by r.organizationId nulls last")
    List<RingEntity> resolveByCode(@Param("code") String code, @Param("organizationId") UUID organizationId);

    /** Convenience form of {@link #resolveByCode}: the first (organization-preferred) match. */
    default Optional<RingEntity> byCode(String code, UUID organizationId) {
        List<RingEntity> matches = resolveByCode(code, organizationId);
        return matches.isEmpty() ? Optional.empty() : Optional.of(matches.get(0));
    }
}
