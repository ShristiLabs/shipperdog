package com.shipperdog.api.application.internal.service;

import com.shipperdog.api.application.internal.entity.ApplicationEntity;
import com.shipperdog.api.application.internal.repository.ApplicationScopedRepository;
import com.shipperdog.api.config.CursorPageable;
import com.shipperdog.api.tenant.TenantAccess;
import com.shipperdog.api.tenant.TenantException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The tenant-owned application listing (issue #11, FR-002 "assign
 * applications" read side): which applications a tenant holds. The
 * listing runs through the tenant-scoped seam (issue #10), so it is
 * filtered to the request's tenant context by construction — rows of
 * other tenants and organization-owned rows (null tenant) can never
 * appear. Shared applications (assigned from outside the tenant)
 * arrive with S3-03 and will extend this surface; the write side
 * (creating applications and assigning ownership) is E3 (S3-01/S3-02).
 */
@Service
public class TenantApplicationService {

    private final ApplicationScopedRepository applications;
    private final TenantAccess tenants;

    TenantApplicationService(ApplicationScopedRepository applications, TenantAccess tenants) {
        this.applications = applications;
        this.tenants = tenants;
    }

    /**
     * Lists the tenant-owned applications, paged (issue #8). Caller
     * permission (tenant:applications:read — tenant administrators,
     * tenant testers, and the owning organization's administrators) is
     * enforced at the controller; an unknown tenant is the module's
     * standard miss for the platform administrators who can reach here.
     */
    @Transactional(readOnly = true)
    public Page<ApplicationView> listTenantOwned(UUID tenantId, long offset, int limit) {
        if (tenants.owningOrganizationId(tenantId).isEmpty()) {
            throw new TenantException(TenantException.Code.NOT_FOUND, "tenant not found");
        }
        return applications
                .findAllScoped(Specification.unrestricted(), new CursorPageable((int) offset, limit))
                .map(ApplicationView::from);
    }

    /**
     * Application view for tenant administrators and testers; the
     * ownership discriminator (org-owned vs tenant-owned) is not part
     * of this surface — everything listed is tenant-owned by
     * construction. Carries the registry fields (description,
     * platforms) since S3-01.
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
}
