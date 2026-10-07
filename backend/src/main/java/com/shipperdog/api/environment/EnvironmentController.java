package com.shipperdog.api.environment;

import com.shipperdog.api.config.Idempotent;
import com.shipperdog.api.config.ListQuery;
import com.shipperdog.api.config.PageResponse;
import com.shipperdog.api.environment.internal.service.EnvironmentService;
import com.shipperdog.api.environment.internal.service.EnvironmentService.BindingCommand;
import com.shipperdog.api.environment.internal.service.EnvironmentService.ConfigSnapshotView;
import com.shipperdog.api.environment.internal.service.EnvironmentService.ConfigVersionView;
import com.shipperdog.api.environment.internal.service.EnvironmentService.CreateCommand;
import com.shipperdog.api.environment.internal.service.EnvironmentService.CredentialView;
import com.shipperdog.api.environment.internal.service.EnvironmentService.CredentialsView;
import com.shipperdog.api.environment.internal.service.EnvironmentService.EnvironmentView;
import com.shipperdog.api.environment.internal.service.EnvironmentService.TenantView;
import com.shipperdog.api.environment.internal.service.EnvironmentService.TenantsView;
import com.shipperdog.api.environment.internal.service.EnvironmentService.UpdateCommand;
import com.shipperdog.api.environment.internal.service.EnvironmentService.VerifyView;
import com.shipperdog.api.identity.AuthenticatedPrincipal;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The environment API (issue #58, S9-04, FR-025, PRD 8.11/19): the
 * organization-grain deployment contexts a release flows through —
 * Development, QA, UAT, Pilot, Production, plus whatever custom
 * environments an organization adds — each with its own versioned
 * configuration, tenant assignments, and reference-only credential
 * bindings. An environment describes WHAT the application points to; a
 * ring describes WHO receives: orthogonal axes, separate surfaces (the
 * issue's technical note).
 *
 * <p>The collection routes carry the organization in the body/query
 * (the testers-module precedent for organization-grain resources): the
 * permission check targets the organization
 * ({@code org:environments:manage}/{@code org:environments:read}) and
 * denials conceal as the uniform {@code ORGANIZATION_NOT_FOUND}. The
 * item routes address the environment by uuid and check the
 * {@code 'environment'} target — the evaluator hops environment to
 * owning organization, and a denial conceals as
 * {@code ENVIRONMENT_NOT_FOUND}, byte-identical to a genuine miss.
 * Creation honors {@code Idempotency-Key} (the designated-POST
 * registry); the replace-set writes are idempotent by construction
 * (the desired set IS the replay answer), so none of them carries the
 * header.
 */
@RestController
@RequestMapping("/api/v1/environments")
public class EnvironmentController {

    private final EnvironmentService environments;

    EnvironmentController(EnvironmentService environments) {
        this.environments = environments;
    }

    /**
     * Creates a custom environment (AC1): stable code, display name,
     * progression position, optional name-only distribution-channel and
     * approval-policy bindings (their owning stories give the names
     * meaning). One code and one name per organization; born ACTIVE.
     * Returns 201 with the environment.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Idempotent
    @PreAuthorize("hasPermission(#request.organizationId(), 'organization', 'org:environments:manage')")
    EnvironmentView create(@AuthenticationPrincipal Object principal, @RequestBody CreateRequest request) {
        return environments.create(
                requireUserId(principal),
                new CreateCommand(
                        request.organizationId(),
                        request.code(),
                        request.name(),
                        request.position(),
                        request.distributionChannel(),
                        request.approvalPolicy()));
    }

    /**
     * The listing (AC1): the organization's environments — seeded
     * defaults and customs together — in progression order (position,
     * then code), standard limit/cursor pagination, each item carrying
     * its current configuration version.
     */
    @GetMapping
    @PreAuthorize("hasPermission(#organizationId, 'organization', 'org:environments:read')")
    PageResponse<EnvironmentView> list(
            @AuthenticationPrincipal Object principal,
            @RequestParam UUID organizationId,
            @RequestParam(required = false) String limit,
            @RequestParam(required = false) String cursor) {
        ListQuery query = ListQuery.of(limit, cursor);
        Page<EnvironmentView> page =
                environments.list(requireUserId(principal), organizationId, query.offset(), query.limit());
        return PageResponse.of(page.getContent(), page.hasNext(), query.offset() + query.limit());
    }

    /** The environment's detail: the row's facts plus its current configuration version. */
    @GetMapping("/{environmentId}")
    @PreAuthorize("hasPermission(#environmentId, 'environment', 'org:environments:read')")
    EnvironmentView get(@AuthenticationPrincipal Object principal, @PathVariable UUID environmentId) {
        return environments.get(requireUserId(principal), environmentId);
    }

    /**
     * The PATCH update (AC1): name, position, and the two name-only
     * bindings may move; the code never does. A DISABLED environment
     * refuses every mutation except reactivation.
     */
    @PatchMapping("/{environmentId}")
    @PreAuthorize("hasPermission(#environmentId, 'environment', 'org:environments:manage')")
    EnvironmentView update(
            @AuthenticationPrincipal Object principal,
            @PathVariable UUID environmentId,
            @RequestBody UpdateRequest request) {
        return environments.update(
                requireUserId(principal),
                environmentId,
                new UpdateCommand(
                        request.name(), request.position(), request.distributionChannel(), request.approvalPolicy()));
    }

    /**
     * The lifecycle crossing (AC1's deactivate): ACTIVE and DISABLED,
     * reversible both ways, an optional reason. A disabled environment
     * accepts no mutations and may not be named by a new release.
     */
    @PutMapping("/{environmentId}/status")
    @PreAuthorize("hasPermission(#environmentId, 'environment', 'org:environments:manage')")
    EnvironmentView setStatus(
            @AuthenticationPrincipal Object principal,
            @PathVariable UUID environmentId,
            @RequestBody StatusChangeRequest request) {
        return environments.setStatus(requireUserId(principal), environmentId, request.status(), request.reason());
    }

    /**
     * One configuration write (AC2): the body carries the FULL desired
     * key-value set; the service appends it as the ledger's next
     * snapshot version (a key disappears by its absence — history never
     * rewrites). The response is the frozen snapshot: version, the
     * resolved map, and the write's moment.
     */
    @PutMapping("/{environmentId}/config")
    @PreAuthorize("hasPermission(#environmentId, 'environment', 'org:environments:manage')")
    ConfigSnapshotView putConfig(
            @AuthenticationPrincipal Object principal,
            @PathVariable UUID environmentId,
            @RequestBody ConfigRequest request) {
        return environments.putConfig(requireUserId(principal), environmentId, request.entries());
    }

    /**
     * The configuration snapshot (AC2): the current map by default, or
     * the snapshot AT a version ({@code ?version=}) — the feed the
     * S9-03 manifest freezes RESOLVED values from. A never-configured
     * environment answers version 0 with the empty map.
     */
    @GetMapping("/{environmentId}/config")
    @PreAuthorize("hasPermission(#environmentId, 'environment', 'org:environments:read')")
    ConfigSnapshotView config(
            @AuthenticationPrincipal Object principal,
            @PathVariable UUID environmentId,
            @RequestParam(required = false) Long version) {
        return environments.config(requireUserId(principal), environmentId, version);
    }

    /**
     * The version ledger (AC2): one row per configuration write —
     * version, key count, writer, moment — chronological, standard
     * limit/cursor pagination.
     */
    @GetMapping("/{environmentId}/config/versions")
    @PreAuthorize("hasPermission(#environmentId, 'environment', 'org:environments:read')")
    PageResponse<ConfigVersionView> configVersions(
            @AuthenticationPrincipal Object principal,
            @PathVariable UUID environmentId,
            @RequestParam(required = false) String limit,
            @RequestParam(required = false) String cursor) {
        ListQuery query = ListQuery.of(limit, cursor);
        Page<ConfigVersionView> page =
                environments.configVersions(requireUserId(principal), environmentId, query.offset(), query.limit());
        return PageResponse.of(page.getContent(), page.hasNext(), query.offset() + query.limit());
    }

    /**
     * The assignment write (AC4): the body carries the FULL desired
     * tenant set — exactly these tenants may be targeted by releases
     * naming this environment (validated at release creation). Every
     * tenant must be a member of the environment's organization; the
     * empty set admits no tenant (deny-by-default).
     */
    @PutMapping("/{environmentId}/tenants")
    @PreAuthorize("hasPermission(#environmentId, 'environment', 'org:environments:manage')")
    TenantsView putTenants(
            @AuthenticationPrincipal Object principal,
            @PathVariable UUID environmentId,
            @RequestBody TenantsRequest request) {
        return environments.putTenants(requireUserId(principal), environmentId, request.tenantIds());
    }

    /**
     * The assigned tenants (AC4): the allow-list release creation
     * validates targets against, with each tenant's registry summary.
     */
    @GetMapping("/{environmentId}/tenants")
    @PreAuthorize("hasPermission(#environmentId, 'environment', 'org:environments:read')")
    PageResponse<TenantView> tenants(
            @AuthenticationPrincipal Object principal,
            @PathVariable UUID environmentId,
            @RequestParam(required = false) String limit,
            @RequestParam(required = false) String cursor) {
        ListQuery query = ListQuery.of(limit, cursor);
        Page<TenantView> page =
                environments.tenants(requireUserId(principal), environmentId, query.offset(), query.limit());
        return PageResponse.of(page.getContent(), page.hasNext(), query.offset() + query.limit());
    }

    /**
     * The binding write (AC3): the body carries the FULL desired
     * binding set — label plus {@code secret_reference} ONLY. No
     * surface accepts secret material, so the database can never hold
     * any (the schema review's structural proof). Same-label rows keep
     * their slot and re-point their reference (a rotation).
     */
    @PutMapping("/{environmentId}/credentials")
    @PreAuthorize("hasPermission(#environmentId, 'environment', 'org:environments:manage')")
    CredentialsView putCredentials(
            @AuthenticationPrincipal Object principal,
            @PathVariable UUID environmentId,
            @RequestBody CredentialsRequest request) {
        return environments.putCredentials(
                requireUserId(principal),
                environmentId,
                request.credentials() == null
                        ? List.of()
                        : request.credentials().stream()
                                .map(binding -> new BindingCommand(binding.name(), binding.secretReference()))
                                .toList());
    }

    /** The bindings (AC3): labels and references — never material, because none exists to show. */
    @GetMapping("/{environmentId}/credentials")
    @PreAuthorize("hasPermission(#environmentId, 'environment', 'org:environments:read')")
    PageResponse<CredentialView> credentials(
            @AuthenticationPrincipal Object principal,
            @PathVariable UUID environmentId,
            @RequestParam(required = false) String limit,
            @RequestParam(required = false) String cursor) {
        ListQuery query = ListQuery.of(limit, cursor);
        Page<CredentialView> page =
                environments.credentials(requireUserId(principal), environmentId, query.offset(), query.limit());
        return PageResponse.of(page.getContent(), page.hasNext(), query.offset() + query.limit());
    }

    /**
     * The resolution probe (AC5): asks the secret-reference resolver
     * whether the binding's reference resolves, and answers ONLY that —
     * different environments' bindings resolve independently because
     * they carry different references (Production is not QA). Audited:
     * probing credentials is a security-relevant act.
     */
    @PostMapping("/{environmentId}/credentials/{credentialId}/verify")
    @PreAuthorize("hasPermission(#environmentId, 'environment', 'org:environments:manage')")
    VerifyView verifyCredential(
            @AuthenticationPrincipal Object principal,
            @PathVariable UUID environmentId,
            @PathVariable UUID credentialId) {
        return environments.verifyCredential(requireUserId(principal), environmentId, credentialId);
    }

    private static UUID requireUserId(Object principal) {
        if (principal instanceof Jwt jwt) {
            return UUID.fromString(jwt.getSubject());
        }
        if (principal instanceof AuthenticatedPrincipal patPrincipal) {
            return patPrincipal.userId();
        }
        throw new EnvironmentException(EnvironmentException.Code.INVALID_REQUEST, "no authenticated principal");
    }

    /**
     * The creation body (FR-025): the owning organization, the stable
     * kebab code (the manifest's frozen environment value), the display
     * name, the progression position, and the optional name-only
     * channel/policy bindings.
     */
    @Schema(
            description = "The environment creation body: the owning organization, the stable code, the display "
                    + "name, the progression position, and the optional name-only distribution-channel and "
                    + "approval-policy bindings.")
    public record CreateRequest(
            UUID organizationId,
            String code,
            String name,
            Integer position,
            String distributionChannel,
            String approvalPolicy) {}

    /** The PATCH body: null fields stay unchanged; the code is immutable and never appears. */
    @Schema(description = "The environment PATCH body: null fields stay unchanged (the code never moves).")
    public record UpdateRequest(String name, Integer position, String distributionChannel, String approvalPolicy) {}

    /** The lifecycle body: ACTIVE or DISABLED, with an optional reason. */
    @Schema(description = "The environment status body: ACTIVE or DISABLED, with an optional reason.")
    public record StatusChangeRequest(String status, String reason) {}

    /** The configuration body: the FULL desired key-value set (an empty object clears the configuration). */
    @Schema(description = "The environment configuration body: the full desired key-value set.")
    public record ConfigRequest(Map<String, String> entries) {}

    /** The assignment body: the FULL desired tenant set (the empty set admits no tenant). */
    @Schema(description = "The tenant assignment body: the full desired tenant id set.")
    public record TenantsRequest(List<UUID> tenantIds) {}

    /** The binding body: the FULL desired binding set of label plus secret reference. */
    @Schema(description = "The credential binding body: the full desired set of name plus secret-reference pairs.")
    public record CredentialsRequest(List<CredentialBindingRequest> credentials) {}

    /** One binding of the write: the label and the opaque secret reference — never material. */
    public record CredentialBindingRequest(String name, String secretReference) {}
}
