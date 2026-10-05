package com.shipperdog.api.application.internal.service;

import com.shipperdog.api.application.internal.entity.ApplicationEntity;
import com.shipperdog.api.application.internal.entity.ApplicationEntity.OwnershipScope;
import com.shipperdog.api.application.internal.entity.TenantApplicationAuthorizationEntity;
import com.shipperdog.api.application.internal.repository.ApplicationRegistryRepository;
import com.shipperdog.api.config.CursorPageable;
import com.shipperdog.api.tenant.TenantAccess;
import com.shipperdog.api.tenant.TenantException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The tenant application listing (issue #11, FR-002 "assign applications"
 * read side): which applications a tenant holds. Since S3-03 (issue #15,
 * AC4) this is the platform's single <strong>effective-visibility
 * resolution</strong>: a tenant sees an application exactly when the
 * application is tenant-owned by it OR organization-shared and carrying
 * an ACTIVE authorization for it (PRD 15 — distribution decides, never
 * the other way around). The union query lives on the registry guard
 * (ADR-0002 fourth amendment: one arm of the union is null-tenant
 * organization rows, invisible to any tenant-scoped query), and THIS
 * method is the one tested resolution point the portal/catalog uses —
 * the E7 authorization chain (PRD 11/12) and E8 consume it rather than
 * re-deriving visibility. The write side (grant/revoke of the mapping)
 * is {@link TenantApplicationAuthorizationService}; application
 * management itself is the registry surface (S3-01/S3-02).
 *
 * <p>Visibility is opt-in by construction (INV-001/INV-005): a shared
 * application is absent from every tenant's listing until an
 * organization administrator or application owner explicitly authorizes
 * that tenant, and de-authorization removes it immediately — the
 * resolution reads the mapping's status, so no stale grant survives a
 * revocation.
 */
@Service
public class TenantApplicationService {

    private final ApplicationRegistryRepository registry;
    private final TenantAccess tenants;

    TenantApplicationService(ApplicationRegistryRepository registry, TenantAccess tenants) {
        this.registry = registry;
        this.tenants = tenants;
    }

    /**
     * The effective-visibility listing (issue #15, AC4): the
     * applications visible to the tenant — its tenant-owned applications
     * plus the organization-shared applications the tenant is authorized
     * for (ACTIVE grants only; revoked grants never resolve). Caller
     * permission (tenant:applications:read — tenant administrators,
     * tenant testers, and the owning organization's administrators) is
     * enforced at the controller; an unknown tenant is the module's
     * standard miss for the platform administrators who can reach here.
     */
    @Transactional(readOnly = true)
    public Page<ApplicationView> listVisibleToTenant(UUID tenantId, long offset, int limit) {
        UUID organizationId = tenants.owningOrganizationId(tenantId).orElseThrow(() -> notFoundTenant());
        return registry.findVisibleToTenant(
                        organizationId,
                        tenantId,
                        OwnershipScope.ORGANIZATION,
                        TenantApplicationAuthorizationEntity.Status.ACTIVE,
                        new CursorPageable(offset, limit))
                .map(ApplicationView::from);
    }

    /**
     * Application view for tenant administrators and testers; both arms
     * of the resolution share it. The ownership discriminator
     * (org-owned vs tenant-owned) is not part of this surface — the
     * tenant sees the application either way, and the registry view
     * (S3-01) carries the discriminator for organization roles. Carries
     * the registry fields (description, platforms) since S3-01.
     */
    public record ApplicationView(
            UUID id,
            String name,
            String description,
            List<String> platforms,
            String bundleId,
            String packageName,
            String status,
            Instant createdAt,
            Instant updatedAt) {

        static ApplicationView from(ApplicationEntity application) {
            return new ApplicationView(
                    application.getId(),
                    application.getName(),
                    application.getDescription(),
                    application.getPlatforms(),
                    application.getBundleId(),
                    application.getPackageName(),
                    application.getStatus().name(),
                    application.getCreatedAt(),
                    application.getUpdatedAt());
        }
    }

    private static TenantException notFoundTenant() {
        return new TenantException(TenantException.Code.NOT_FOUND, "tenant not found");
    }
}
