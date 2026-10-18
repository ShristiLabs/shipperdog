package com.shipperdog.api.otabundle.internal.repository;

import com.shipperdog.api.otabundle.internal.entity.OtaAppTokenEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Access to {@code ota_app_tokens} (S16-04): a plain repository over
 * organization-owned registry data, NOT the tenant-scoped seam — the
 * V55 table carries no tenant column because the registry must be
 * resolvable by hash from an anonymous device request, before any
 * tenant is known (the {@code SsoSessionCodeRepository} shape; the
 * architecture tests force the seam only over {@code TenantScope}
 * entities). The derived queries are the two sanctioned questions:
 * the filter's hash lookup (cross-organization by necessity — a
 * device presents its token blind, and the registry row IS the
 * resolution) and the console's application-addressed reads, which
 * always constrain organization AND application so a foreign
 * application's tokens are unreachable.
 */
public interface OtaAppTokenRepository extends JpaRepository<OtaAppTokenEntity, UUID> {

    /** The filter's resolution: one row by token hash, the registry's identity. */
    Optional<OtaAppTokenEntity> findByTokenHash(String tokenHash);

    /** The console listing: the application's tokens inside the owning organization. */
    Page<OtaAppTokenEntity> findByApplicationIdAndOrganizationId(
            UUID applicationId, UUID organizationId, Pageable pageable);

    /** The revoke target: one row of the application inside the owning organization. */
    Optional<OtaAppTokenEntity> findByIdAndApplicationIdAndOrganizationId(
            UUID id, UUID applicationId, UUID organizationId);
}
