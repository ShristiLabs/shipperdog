package com.shipperdog.api.tenant;

import com.shipperdog.api.config.Idempotent;
import com.shipperdog.api.config.ListQuery;
import com.shipperdog.api.config.PageResponse;
import com.shipperdog.api.identity.AuthenticatedPrincipal;
import com.shipperdog.api.tenant.internal.entity.TenantEntity;
import com.shipperdog.api.tenant.internal.service.TenantMemberService;
import com.shipperdog.api.tenant.internal.service.TenantService;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tenant API: lifecycle (issue #9) and membership (issue #5). Lifecycle
 * — creation under an organization, organization-scoped listing with a
 * status filter, and partial update including the reversible
 * ACTIVE/DEACTIVATED transition — is organization-level administration:
 * org:tenants:create/read on the owning organization,
 * tenant:tenants:read/update on the tenant (tenant administrators hold
 * read only). Permissions are declared with {@code @PreAuthorize} and
 * enforced by the authorization module's evaluator as the single
 * enforcement point; resource-scoped denials render as concealed 404s
 * (PRD 12, issue #8). Listing pages with the platform's cursor
 * convention and creation honors {@code Idempotency-Key} (issue #8).
 * Creation requests missing an organizationId are denied by the
 * permission check before the body is read — authorization precedes
 * validation for every guarded endpoint in the platform.
 */
@RestController
@RequestMapping("/api/v1/tenants")
public class TenantController {

    private final TenantService tenants;
    private final TenantMemberService members;

    TenantController(TenantService tenants, TenantMemberService members) {
        this.tenants = tenants;
        this.members = members;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Idempotent
    @PreAuthorize("hasPermission(#request.organizationId(), 'organization', 'org:tenants:create')")
    TenantResponse create(@AuthenticationPrincipal Object principal, @RequestBody CreateRequest request) {
        return TenantResponse.from(tenants.create(
                requireUserId(principal), request.organizationId(), request.name(), request.externalId()));
    }

    @GetMapping
    @PreAuthorize("hasPermission(#organizationId, 'organization', 'org:tenants:read')")
    PageResponse<TenantResponse> list(
            @AuthenticationPrincipal Object principal,
            @RequestParam UUID organizationId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String limit,
            @RequestParam(required = false) String cursor) {
        ListQuery query = ListQuery.of(limit, cursor);
        Page<TenantResponse> page = tenants.list(organizationId, status, query.offset(), query.limit())
                .map(TenantResponse::from);
        return PageResponse.of(page.getContent(), page.hasNext(), query.offset() + query.limit());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasPermission(#id, 'tenant', 'tenant:tenants:read')")
    TenantResponse get(@AuthenticationPrincipal Object principal, @PathVariable UUID id) {
        return TenantResponse.from(tenants.get(id));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasPermission(#id, 'tenant', 'tenant:tenants:update')")
    TenantResponse update(
            @AuthenticationPrincipal Object principal, @PathVariable UUID id, @RequestBody UpdateRequest request) {
        return TenantResponse.from(
                tenants.update(requireUserId(principal), id, request.name(), request.externalId(), request.status()));
    }

    @GetMapping("/{id}/members")
    @PreAuthorize("hasPermission(#id, 'tenant', 'tenant:members:read')")
    PageResponse<TenantMemberService.MemberView> listMembers(
            @AuthenticationPrincipal Object principal,
            @PathVariable UUID id,
            @RequestParam(required = false) String limit,
            @RequestParam(required = false) String cursor) {
        ListQuery query = ListQuery.of(limit, cursor);
        Page<TenantMemberService.MemberView> page = members.listMembers(id, query.offset(), query.limit());
        return PageResponse.of(page.getContent(), page.hasNext(), query.offset() + query.limit());
    }

    @PutMapping("/{id}/members/{userId}/role")
    @PreAuthorize("hasPermission(#id, 'tenant', 'tenant:members:manage')")
    TenantMemberService.MemberView assignRole(
            @AuthenticationPrincipal Object principal,
            @PathVariable UUID id,
            @PathVariable UUID userId,
            @RequestBody RoleChangeRequest request) {
        return members.assignRole(id, userId, parseRole(request.role()), requireUserId(principal));
    }

    @DeleteMapping("/{id}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasPermission(#id, 'tenant', 'tenant:members:manage')")
    void removeMember(@AuthenticationPrincipal Object principal, @PathVariable UUID id, @PathVariable UUID userId) {
        members.removeMember(id, userId, requireUserId(principal));
    }

    private static UUID requireUserId(Object principal) {
        if (principal instanceof Jwt jwt) {
            return UUID.fromString(jwt.getSubject());
        }
        if (principal instanceof AuthenticatedPrincipal patPrincipal) {
            return patPrincipal.userId();
        }
        throw new TenantException(TenantException.Code.INVALID_REQUEST, "no authenticated principal");
    }

    private static TenantRole parseRole(String role) {
        if (role == null) {
            throw new TenantException(TenantException.Code.INVALID_REQUEST, "role is required");
        }
        try {
            return TenantRole.valueOf(role.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new TenantException(TenantException.Code.INVALID_REQUEST, "unknown role: " + role.trim());
        }
    }

    /** Target role for a tenant member assignment (issue #5). */
    public record RoleChangeRequest(String role) {}

    /** Tenant creation body (issue #9); the tenant is born ACTIVE. */
    public record CreateRequest(UUID organizationId, String name, String externalId) {}

    /** Partial update: null fields are left unchanged. */
    public record UpdateRequest(String name, String externalId, String status) {}

    /** Tenant view; externalId maps to the customer's system of record. */
    record TenantResponse(
            UUID id,
            UUID organizationId,
            String name,
            String externalId,
            String status,
            Instant createdAt,
            Instant updatedAt) {

        static TenantResponse from(TenantEntity tenant) {
            return new TenantResponse(
                    tenant.getId(),
                    tenant.getOrganizationId(),
                    tenant.getName(),
                    tenant.getExternalId(),
                    tenant.getStatus().name(),
                    tenant.getCreatedAt(),
                    tenant.getUpdatedAt());
        }
    }
}
