package com.shipperdog.api.organization;

import com.shipperdog.api.config.Idempotent;
import com.shipperdog.api.config.ListQuery;
import com.shipperdog.api.config.PageResponse;
import com.shipperdog.api.identity.AuthenticatedPrincipal;
import com.shipperdog.api.organization.internal.entity.OrganizationEntity;
import com.shipperdog.api.organization.internal.service.OrganizationService;
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
import tools.jackson.databind.JsonNode;

/**
 * Organization management API (issues #4, #5 and #8, PRD 19): creation
 * (platform:organizations:create; creator becomes Organization Admin),
 * admin-gated read/update of settings and status, the stored
 * identity-provider configuration record (S14 placeholder), and member
 * listing/role assignment (org:members:*). Since S1-05 every endpoint
 * declares its required permission with {@code @PreAuthorize}; the
 * authorization module's evaluator is the single enforcement point, and
 * resource-scoped denials render as concealed 404s (PRD 12, issue #8).
 * List endpoints page with the platform's {@code limit}/{@code cursor}
 * convention and creation honors {@code Idempotency-Key} (issue #8) —
 * this controller is the demo surface of each convention.
 */
@RestController
@RequestMapping("/api/v1/organizations")
public class OrganizationController {

    private final OrganizationService organizations;

    OrganizationController(OrganizationService organizations) {
        this.organizations = organizations;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Idempotent
    @PreAuthorize("hasPermission(null, 'organization', 'platform:organizations:create')")
    OrganizationResponse create(@AuthenticationPrincipal Object principal, @RequestBody CreateRequest request) {
        return OrganizationResponse.from(
                organizations.create(requireUserId(principal), request.name(), request.externalId()));
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    PageResponse<OrganizationResponse> list(
            @AuthenticationPrincipal Object principal,
            @RequestParam(required = false) String limit,
            @RequestParam(required = false) String cursor) {
        ListQuery query = ListQuery.of(limit, cursor);
        Page<OrganizationResponse> page = organizations
                .list(requireUserId(principal), query.offset(), query.limit())
                .map(OrganizationResponse::from);
        return PageResponse.of(page.getContent(), page.hasNext(), query.offset() + query.limit());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasPermission(#id, 'organization', 'org:organizations:read')")
    OrganizationResponse get(@AuthenticationPrincipal Object principal, @PathVariable UUID id) {
        return OrganizationResponse.from(organizations.get(id));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasPermission(#id, 'organization', 'org:organizations:update')")
    OrganizationResponse update(
            @AuthenticationPrincipal Object principal,
            @PathVariable UUID id,
            @RequestBody OrganizationUpdateRequest request) {
        return OrganizationResponse.from(organizations.update(
                requireUserId(principal), id, request.name(), request.status(), request.externalId()));
    }

    @GetMapping("/{id}/idp-config")
    @PreAuthorize("hasPermission(#id, 'organization', 'org:idp-configs:read')")
    OrganizationService.IdpConfig getIdpConfig(@AuthenticationPrincipal Object principal, @PathVariable UUID id) {
        return organizations.getIdpConfig(id);
    }

    @PutMapping("/{id}/idp-config")
    @PreAuthorize("hasPermission(#id, 'organization', 'org:idp-configs:write')")
    OrganizationService.IdpConfig updateIdpConfig(
            @AuthenticationPrincipal Object principal, @PathVariable UUID id, @RequestBody IdpConfigRequest request) {
        return organizations.updateIdpConfig(requireUserId(principal), id, request.providerType(), request.config());
    }

    @GetMapping("/{id}/members")
    @PreAuthorize("hasPermission(#id, 'organization', 'org:members:read')")
    PageResponse<OrganizationService.MemberView> listMembers(
            @AuthenticationPrincipal Object principal,
            @PathVariable UUID id,
            @RequestParam(required = false) String limit,
            @RequestParam(required = false) String cursor) {
        ListQuery query = ListQuery.of(limit, cursor);
        Page<OrganizationService.MemberView> page = organizations.listMembers(id, query.offset(), query.limit());
        return PageResponse.of(page.getContent(), page.hasNext(), query.offset() + query.limit());
    }

    @PutMapping("/{id}/members/{userId}/role")
    @PreAuthorize("hasPermission(#id, 'organization', 'org:members:manage')")
    OrganizationService.MemberView changeMemberRole(
            @AuthenticationPrincipal Object principal,
            @PathVariable UUID id,
            @PathVariable UUID userId,
            @RequestBody RoleChangeRequest request) {
        return organizations.changeMemberRole(id, userId, parseRole(request.role()), requireUserId(principal));
    }

    @DeleteMapping("/{id}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasPermission(#id, 'organization', 'org:members:manage')")
    void removeMember(@AuthenticationPrincipal Object principal, @PathVariable UUID id, @PathVariable UUID userId) {
        organizations.removeMember(id, userId, requireUserId(principal));
    }

    private static UUID requireUserId(Object principal) {
        if (principal instanceof Jwt jwt) {
            return UUID.fromString(jwt.getSubject());
        }
        if (principal instanceof AuthenticatedPrincipal patPrincipal) {
            return patPrincipal.userId();
        }
        throw new OrganizationException(OrganizationException.Code.INVALID_REQUEST, "no authenticated principal");
    }

    private static OrgRole parseRole(String role) {
        if (role == null) {
            throw new OrganizationException(OrganizationException.Code.INVALID_REQUEST, "role is required");
        }
        try {
            return OrgRole.valueOf(role.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new OrganizationException(OrganizationException.Code.INVALID_REQUEST, "unknown role: " + role.trim());
        }
    }

    private record CreateRequest(String name, String externalId) {}

    /** Partial update: null fields are left unchanged. */
    private record OrganizationUpdateRequest(String name, String status, String externalId) {}

    private record IdpConfigRequest(String providerType, JsonNode config) {}

    /** Target role for a member role assignment (issue #5). */
    public record RoleChangeRequest(String role) {}

    /** Organization view; externalId maps to billing/CRM systems. */
    record OrganizationResponse(
            UUID id, String name, String status, String externalId, Instant createdAt, Instant updatedAt) {

        static OrganizationResponse from(OrganizationEntity org) {
            return new OrganizationResponse(
                    org.getId(),
                    org.getName(),
                    org.getStatus().name(),
                    org.getExternalId(),
                    org.getCreatedAt(),
                    org.getUpdatedAt());
        }
    }
}
