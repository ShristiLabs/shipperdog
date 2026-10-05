package com.shipperdog.api.application;

import com.shipperdog.api.application.internal.service.TenantApplicationService;
import com.shipperdog.api.config.ListQuery;
import com.shipperdog.api.config.PageResponse;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The tenant-owned applications listing (issue #11, FR-002 "assign
 * applications" read side, PRD 8.2): {@code GET /tenants/{id}/
 * applications} answers which applications the tenant holds. The route
 * addresses a tenant, so the tenant module's interceptor binds the
 * tenant context and the application repository's seam filters the
 * query; the permission (tenant:applications:read) keeps the surface
 * read-only for tenant testers while tenant and organization
 * administrators see the same listing. Application management itself
 * (create, edit, archive) is E3 (S3-01) and will carry its own
 * controller; shared-application authorization is S3-03.
 */
@RestController
@RequestMapping("/api/v1/tenants/{id}/applications")
public class TenantApplicationsController {

    private final TenantApplicationService applications;

    TenantApplicationsController(TenantApplicationService applications) {
        this.applications = applications;
    }

    @GetMapping
    @PreAuthorize("hasPermission(#id, 'tenant', 'tenant:applications:read')")
    PageResponse<TenantApplicationService.ApplicationView> list(
            @PathVariable UUID id,
            @RequestParam(required = false) String limit,
            @RequestParam(required = false) String cursor) {
        ListQuery query = ListQuery.of(limit, cursor);
        Page<TenantApplicationService.ApplicationView> page =
                applications.listTenantOwned(id, query.offset(), query.limit());
        return PageResponse.of(page.getContent(), page.hasNext(), query.offset() + query.limit());
    }
}
