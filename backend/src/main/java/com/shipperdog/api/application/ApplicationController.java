package com.shipperdog.api.application;

import com.shipperdog.api.application.internal.service.ApplicationService;
import com.shipperdog.api.config.Idempotent;
import com.shipperdog.api.config.ListQuery;
import com.shipperdog.api.config.PageResponse;
import com.shipperdog.api.identity.AuthenticatedPrincipal;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The application registry API (issues #13 + #14, S3-01/S3-02, FR-003):
 * registration of applications with their platform set and per-platform
 * identifiers, reads, and partial update carrying the reversible
 * ACTIVE/ARCHIVED lifecycle and — since S3-02 (PRD 14) — the ownership
 * scope and its audited transitions. An application is born TENANT-owned
 * (the default; the owning organization is derived from the owner
 * tenant) or ORGANIZATION-shared (the owning organization is stated and
 * no tenant is). {@code app:} permissions resolve through the owning
 * tenant/organization union (creation checks
 * {@code app:applications:write} at the future owner tenant for
 * TENANT-owned registrations and at the owning organization for
 * ORGANIZATION-shared ones — every creator holds an organization-level
 * role either way); resource-scoped denials render as concealed 404s
 * (PRD 12) and errors use the problem+json envelope (issue #8).
 * Creation honors {@code Idempotency-Key}. The tenant-scoped listing
 * lives under {@code GET /api/v1/tenants/{id}/applications} (S2-03) and
 * resolves effective visibility since S3-03 (tenant-owned plus
 * authorized shared); the organization-wide administration listing
 * ({@code GET /api/v1/applications?organizationId=...}) is S3-04's
 * console reachability surface. The shared-application authorization
 * mapping is {@link ApplicationTenantAuthorizationController} (S3-03).
 * Versions (E4) are later stories.
 */
@RestController
@RequestMapping("/api/v1/applications")
public class ApplicationController {

    private final ApplicationService applications;

    ApplicationController(ApplicationService applications) {
        this.applications = applications;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Idempotent
    @PreAuthorize("hasPermission(#request.tenantId() != null ? #request.tenantId() : #request.organizationId(),"
            + " #request.tenantId() != null ? 'tenant' : 'organization', 'app:applications:write')")
    ApplicationService.ApplicationView create(
            @AuthenticationPrincipal Object principal, @RequestBody CreateRequest request) {
        return applications.create(
                requireUserId(principal),
                request.ownershipScope(),
                request.tenantId(),
                request.organizationId(),
                request.name(),
                request.description(),
                request.platforms(),
                request.bundleId(),
                request.packageName());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasPermission(#id, 'application', 'app:applications:read')")
    ApplicationService.ApplicationView get(@AuthenticationPrincipal Object principal, @PathVariable UUID id) {
        return applications.get(id);
    }

    /**
     * The organization-wide registry listing (issue #16, S3-04): every
     * application of the organization in both ownership scopes — the
     * console's administration surface, and the reachability path to a
     * shared application before its first tenant authorization (every
     * effective-visibility listing hides it until granted, PRD 15).
     * Organization-addressed read for organization roles; denials
     * conceal as the standard 404 envelope (PRD 12).
     */
    @GetMapping
    @PreAuthorize("hasPermission(#organizationId, 'organization', 'app:applications:read')")
    PageResponse<ApplicationService.ApplicationView> list(
            @AuthenticationPrincipal Object principal,
            @RequestParam UUID organizationId,
            @RequestParam(required = false) String limit,
            @RequestParam(required = false) String cursor) {
        ListQuery query = ListQuery.of(limit, cursor);
        Page<ApplicationService.ApplicationView> page =
                applications.listByOrganization(organizationId, query.offset(), query.limit());
        return PageResponse.of(page.getContent(), page.hasNext(), query.offset() + query.limit());
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasPermission(#id, 'application', 'app:applications:write')")
    ApplicationService.ApplicationView update(
            @AuthenticationPrincipal Object principal,
            @PathVariable UUID id,
            @RequestBody ApplicationUpdateRequest request) {
        return applications.update(
                requireUserId(principal),
                id,
                request.name(),
                request.description(),
                request.platforms(),
                request.bundleId(),
                request.packageName(),
                request.status(),
                request.otaEnabled(),
                request.ownershipScope(),
                request.tenantId());
    }

    private static UUID requireUserId(Object principal) {
        if (principal instanceof Jwt jwt) {
            return UUID.fromString(jwt.getSubject());
        }
        if (principal instanceof AuthenticatedPrincipal patPrincipal) {
            return patPrincipal.userId();
        }
        throw new ApplicationException(ApplicationException.Code.INVALID_REQUEST, "no authenticated principal");
    }

    /**
     * Registration body (issues #13 + #14): the ownership scope
     * ({@code TENANT} default or {@code ORGANIZATION}), the owner — the
     * future owner tenant for TENANT scope, the owning organization for
     * ORGANIZATION scope — the name, an optional description, the
     * platform set (at least one of IOS/ANDROID), and the chosen
     * platforms' identifiers: bundleId exactly when IOS is chosen,
     * packageName exactly when ANDROID is.
     */
    public record CreateRequest(
            String ownershipScope,
            UUID tenantId,
            UUID organizationId,
            String name,
            String description,
            List<String> platforms,
            String bundleId,
            String packageName) {}

    /**
     * Partial update: null fields are left unchanged; an empty
     * description clears it; removing a platform retires its identifier.
     * Since S16-15 an optional {@code otaEnabled} boolean carries the
     * OTA opt-in toggle (V55's {@code applications.ota_enabled}) — the
     * same application-manage write grain as every sibling field, the
     * console's OTA-enable switch its only caller. A changed
     * {@code ownershipScope} requests the audited scope
     * transition (issue #14): converting to TENANT requires the new
     * owner {@code tenantId}, converting to ORGANIZATION requires none;
     * a {@code tenantId} without a scope change is rejected
     * (re-owning between tenants is not a registry operation).
     */
    public record ApplicationUpdateRequest(
            String name,
            String description,
            List<String> platforms,
            String bundleId,
            String packageName,
            String status,
            Boolean otaEnabled,
            String ownershipScope,
            UUID tenantId) {}
}
