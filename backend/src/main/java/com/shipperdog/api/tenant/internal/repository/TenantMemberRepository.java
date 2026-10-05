package com.shipperdog.api.tenant.internal.repository;

import com.shipperdog.api.tenant.CrossTenantByDesign;
import com.shipperdog.api.tenant.TenantRole;
import com.shipperdog.api.tenant.internal.entity.TenantMemberEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The authorization engine's view of {@code tenant_members} (issue #5):
 * role resolution queries across tenants by design — "which roles does
 * this user hold anywhere" powers the evaluator — so this repository is
 * explicitly exempt from the tenant-scoped seam (issue #10) and must
 * never serve endpoint data paths; those use
 * {@link TenantMemberScopedRepository}.
 */
@CrossTenantByDesign
public interface TenantMemberRepository extends JpaRepository<TenantMemberEntity, UUID> {

    /** Single-tenant role lookup (TenantAccess.roleInTenant; evaluator path). */
    Optional<TenantMemberEntity> findByTenantIdAndUserId(UUID tenantId, UUID userId);

    boolean existsByTenantIdAndUserId(UUID tenantId, UUID userId);

    long countByTenantIdAndRole(UUID tenantId, TenantRole role);

    /** All memberships of a user across tenants (snapshot loading, issue #5). */
    List<TenantMemberEntity> findByUserId(UUID userId);
}
