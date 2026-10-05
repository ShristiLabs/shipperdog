package com.shipperdog.api.tenant;

import com.shipperdog.api.config.ListQuery;
import com.shipperdog.api.config.PageResponse;
import com.shipperdog.api.identity.AuthenticatedPrincipal;
import com.shipperdog.api.tenant.internal.service.TenantMemberService;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tenant membership API (issue #5): listing a tenant's members and
 * assigning TENANT_ADMIN/TENANT_TESTER roles. Tenant CRUD arrives with E2
 * (issues #9-#12). Permissions are declared with {@code @PreAuthorize}
 * and enforced by the authorization module's evaluator as the single
 * enforcement point: tenant:members:read/manage is granted to the
 * tenant's administrators, to organization administrators of the owning
 * organization (union across scopes), and to platform administrators.
 * Resource-scoped denials render as concealed 404s and member listing
 * pages with the platform's cursor convention (PRD 12, issue #8).
 */
@RestController
@RequestMapping("/api/v1/tenants")
public class TenantController {

    private final TenantMemberService members;

    TenantController(TenantMemberService members) {
        this.members = members;
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
}
