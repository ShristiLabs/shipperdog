package com.shipperdog.api.application;

import com.shipperdog.api.application.internal.service.ApplicationService;
import com.shipperdog.api.config.Idempotent;
import com.shipperdog.api.identity.AuthenticatedPrincipal;
import java.util.List;
import java.util.UUID;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The application registry API (issue #13, S3-01, FR-003): registration
 * of tenant-owned applications with their platform set and per-platform
 * identifiers, reads, and partial update carrying the reversible
 * ACTIVE/ARCHIVED lifecycle. {@code app:} permissions resolve through
 * the application's owning tenant/organization union (creation checks
 * {@code app:applications:write} at the future owner tenant, so every
 * creator holds an organization-level role); resource-scoped denials
 * render as concealed 404s (PRD 12) and errors use the problem+json
 * envelope (issue #8). Creation honors {@code Idempotency-Key}. The
 * tenant-scoped listing lives under
 * {@code GET /api/v1/tenants/{id}/applications} (S2-03) and stays this
 * story's list surface; ownership scope (S3-02), shared applications
 * (S3-03), the console UI (S3-04), and versions (E4) are later stories.
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
    @PreAuthorize("hasPermission(#request.tenantId(), 'tenant', 'app:applications:write')")
    ApplicationService.ApplicationView create(
            @AuthenticationPrincipal Object principal, @RequestBody CreateRequest request) {
        return applications.create(
                requireUserId(principal),
                request.tenantId(),
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

    @PatchMapping("/{id}")
    @PreAuthorize("hasPermission(#id, 'application', 'app:applications:write')")
    ApplicationService.ApplicationView update(
            @AuthenticationPrincipal Object principal, @PathVariable UUID id, @RequestBody UpdateRequest request) {
        return applications.update(
                requireUserId(principal),
                id,
                request.name(),
                request.description(),
                request.platforms(),
                request.bundleId(),
                request.packageName(),
                request.status());
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
     * Registration body (issue #13): the future owner tenant, the name,
     * an optional description, the platform set (at least one of
     * IOS/ANDROID), and the chosen platforms' identifiers — bundleId
     * exactly when IOS is chosen, packageName exactly when ANDROID is.
     */
    public record CreateRequest(
            UUID tenantId,
            String name,
            String description,
            List<String> platforms,
            String bundleId,
            String packageName) {}

    /**
     * Partial update: null fields are left unchanged; an empty
     * description clears it; removing a platform retires its identifier.
     */
    public record UpdateRequest(
            String name,
            String description,
            List<String> platforms,
            String bundleId,
            String packageName,
            String status) {}
}
