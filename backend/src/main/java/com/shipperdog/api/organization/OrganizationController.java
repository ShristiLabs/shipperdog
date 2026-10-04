package com.shipperdog.api.organization;

import com.shipperdog.api.identity.AuthenticatedPrincipal;
import com.shipperdog.api.organization.internal.entity.OrganizationEntity;
import com.shipperdog.api.organization.internal.service.OrganizationService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * Organization management API (issue #4, PRD 19): creation (platform
 * administrators; creator becomes Organization Admin), admin-gated
 * read/update of settings and status, and the stored identity-provider
 * configuration record (S14 placeholder). Authentication is enforced by
 * the identity security chain; role checks live in the service.
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
    OrganizationResponse create(@AuthenticationPrincipal Object principal, @RequestBody CreateRequest request) {
        return OrganizationResponse.from(
                organizations.create(requireUserId(principal), request.name(), request.externalId()));
    }

    @GetMapping
    List<OrganizationResponse> list(@AuthenticationPrincipal Object principal) {
        return organizations.list(requireUserId(principal)).stream()
                .map(OrganizationResponse::from)
                .toList();
    }

    @GetMapping("/{id}")
    OrganizationResponse get(@AuthenticationPrincipal Object principal, @PathVariable UUID id) {
        return OrganizationResponse.from(organizations.get(requireUserId(principal), id));
    }

    @PutMapping("/{id}")
    OrganizationResponse update(
            @AuthenticationPrincipal Object principal, @PathVariable UUID id, @RequestBody UpdateRequest request) {
        return OrganizationResponse.from(organizations.update(
                requireUserId(principal), id, request.name(), request.status(), request.externalId()));
    }

    @GetMapping("/{id}/idp-config")
    OrganizationService.IdpConfig getIdpConfig(@AuthenticationPrincipal Object principal, @PathVariable UUID id) {
        return organizations.getIdpConfig(requireUserId(principal), id);
    }

    @PutMapping("/{id}/idp-config")
    OrganizationService.IdpConfig updateIdpConfig(
            @AuthenticationPrincipal Object principal, @PathVariable UUID id, @RequestBody IdpConfigRequest request) {
        return organizations.updateIdpConfig(requireUserId(principal), id, request.providerType(), request.config());
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

    private record CreateRequest(String name, String externalId) {}

    /** Partial update: null fields are left unchanged. */
    private record UpdateRequest(String name, String status, String externalId) {}

    private record IdpConfigRequest(String providerType, JsonNode config) {}

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
