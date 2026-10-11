package com.shipperdog.api.tenant.internal.repository;

import com.shipperdog.api.tenant.CrossTenantByDesign;
import com.shipperdog.api.tenant.TenantRole;
import com.shipperdog.api.tenant.internal.entity.TenantMemberEntity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The authorization engine's view of {@code tenant_members} (issue #5):
 * role resolution queries across tenants by design — "which roles does
 * this user hold anywhere" powers the evaluator — so this repository is
 * explicitly exempt from the tenant-scoped seam (issue #10) and must
 * never serve endpoint data paths; those use
 * {@link TenantMemberScopedRepository}. Its writes are the cross-module
 * lifecycle legs: the SCIM deprovisioning cascade's bulk suspension
 * (S14-03) runs as a modifying query exactly like the tester
 * deactivation's — one statement, explicitly setting {@code updated_at}
 * (bulk updates bypass the entity lifecycle callbacks).
 */
@CrossTenantByDesign
public interface TenantMemberRepository extends JpaRepository<TenantMemberEntity, UUID> {

    /** Single-tenant role lookup (TenantAccess.roleInTenant; evaluator path). */
    Optional<TenantMemberEntity> findByTenantIdAndUserId(UUID tenantId, UUID userId);

    boolean existsByTenantIdAndUserId(UUID tenantId, UUID userId);

    long countByTenantIdAndRole(UUID tenantId, TenantRole role);

    /** All memberships of a user across tenants (snapshot loading, issue #5). */
    List<TenantMemberEntity> findByUserId(UUID userId);

    /**
     * Suspends every ACTIVE membership of the user across ALL tenants
     * (the S6-04 cascade behind SCIM deprovisioning, S14-03): one
     * statement, returning the number of rows flipped.
     */
    @Modifying(flushAutomatically = true)
    @Query("update TenantMemberEntity m"
            + " set m.status = :suspended, m.updatedAt = :now"
            + " where m.userId = :userId and m.status = :active")
    int suspendActiveByUserId(
            @Param("userId") UUID userId,
            @Param("active") TenantMemberEntity.Status active,
            @Param("suspended") TenantMemberEntity.Status suspended,
            @Param("now") Instant now);
}
