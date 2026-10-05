package com.shipperdog.api.tenant.internal.repository;

import com.shipperdog.api.tenant.TenantRole;
import com.shipperdog.api.tenant.internal.entity.TenantMemberEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TenantMemberRepository extends JpaRepository<TenantMemberEntity, UUID> {

    Page<TenantMemberEntity> findByTenantIdOrderByCreatedAtAscIdAsc(UUID tenantId, Pageable pageable);

    Optional<TenantMemberEntity> findByTenantIdAndUserId(UUID tenantId, UUID userId);

    boolean existsByTenantIdAndUserId(UUID tenantId, UUID userId);

    long countByTenantIdAndRole(UUID tenantId, TenantRole role);

    List<TenantMemberEntity> findByUserId(UUID userId);
}
