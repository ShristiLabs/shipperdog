package com.shipperdog.api.distribution;

import com.shipperdog.api.config.ListQuery;
import com.shipperdog.api.config.PageResponse;
import com.shipperdog.api.distribution.internal.apple.TestFlightGroupService;
import com.shipperdog.api.distribution.internal.registry.DistributionAdapterRegistry;
import com.shipperdog.api.distribution.internal.service.DistributionCredentialService;
import com.shipperdog.api.distribution.internal.service.DistributionCredentialService.BindingCommand;
import com.shipperdog.api.distribution.internal.service.DistributionCredentialService.EffectiveBinding;
import com.shipperdog.api.distribution.internal.service.DistributionCredentialService.StoreAccountView;
import com.shipperdog.api.distribution.internal.service.DistributionDispatchService;
import com.shipperdog.api.distribution.internal.service.DistributionDispatchService.RecordView;
import com.shipperdog.api.identity.AuthenticatedPrincipal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
 * The adapter framework's console surface (issue #61, S13-01): the
 * channel catalog (what is registered and what it can do), the
 * credential-delegation chain's bindings (PRD 25: bind at any level,
 * inherit downward), and the distribution records (the adapter-driven
 * lifecycle with external ids for store reconciliation, AC4).
 *
 * <p>Organization-grain governance (the environments standing): every
 * route addresses an organization the evaluator checks, material
 * enters exactly once through the binding write, and every answer
 * carries references, never material (the never-material doctrine,
 * AC3).
 */
@RestController
@RequestMapping("/api/v1/distribution")
public class DistributionController {

    private final DistributionAdapterRegistry registry;
    private final DistributionCredentialService credentials;
    private final DistributionDispatchService dispatch;
    private final com.shipperdog.api.distribution.internal.apple.TestFlightGroupService testFlightGroups;

    public DistributionController(
            DistributionAdapterRegistry registry,
            DistributionCredentialService credentials,
            DistributionDispatchService dispatch,
            com.shipperdog.api.distribution.internal.apple.TestFlightGroupService testFlightGroups) {
        this.registry = registry;
        this.credentials = credentials;
        this.dispatch = dispatch;
        this.testFlightGroups = testFlightGroups;
    }

    /** The channel catalog: every registered adapter with its capabilities and credential providers. */
    @GetMapping("/channels")
    @PreAuthorize("hasPermission(#organizationId, 'organization', 'org:distribution:read')")
    ChannelCatalogView channels(@RequestParam UUID organizationId) {
        List<ChannelView> channels = registry.catalog().stream()
                .map(entry -> new ChannelView(
                        entry.channelId(),
                        entry.displayName(),
                        entry.description(),
                        entry.capabilities().stream().map(Enum::name).toList(),
                        List.copyOf(entry.credentialProviders())))
                .toList();
        return new ChannelCatalogView(channels);
    }

    /** The organization's credential bindings across the chain's levels (references only, never material). */
    @GetMapping("/credentials")
    @PreAuthorize("hasPermission(#organizationId, 'organization', 'org:distribution:read')")
    PageResponse<StoreAccountView> credentials(
            @RequestParam UUID organizationId,
            @RequestParam(required = false) String limit,
            @RequestParam(required = false) String cursor) {
        ListQuery query = ListQuery.of(limit, cursor);
        List<StoreAccountView> all = credentials.list(organizationId);
        List<StoreAccountView> page = all.subList(
                (int) Math.min(query.offset(), all.size()), (int) Math.min(query.offset() + query.limit(), all.size()));
        return PageResponse.of(page, query.offset() + query.limit() < all.size(), query.offset() + query.limit());
    }

    /**
     * The delegation chain's effective view (AC2's matrix surface): for
     * every provider the organization binds, the row that wins at the
     * addressed scope -- environment beats application beats
     * organization, first-defined-wins (PRD 25).
     */
    @GetMapping("/credentials/effective")
    @PreAuthorize("hasPermission(#organizationId, 'organization', 'org:distribution:read')")
    EffectiveChainView effectiveCredentials(
            @RequestParam UUID organizationId,
            @RequestParam(required = false) UUID applicationId,
            @RequestParam(required = false) UUID environmentId) {
        return new EffectiveChainView(credentials.effectiveChain(organizationId, applicationId, environmentId));
    }

    /**
     * The binding write: material enters exactly once, the store keeps
     * it, the row keeps the reference. Binding an already-bound
     * provider at the same level rotates the reference in place.
     */
    @PutMapping("/credentials")
    @PreAuthorize("hasPermission(#request.organizationId(), 'organization', 'org:distribution:manage')")
    StoreAccountView putCredential(
            @AuthenticationPrincipal Object principal, @RequestBody CredentialBindingRequest request) {
        return credentials.bind(
                new BindingCommand(
                        request.organizationId(),
                        request.applicationId(),
                        request.environmentId(),
                        request.provider(),
                        request.name(),
                        request.metadata(),
                        new SecretMaterial(request.secretMaterial())),
                requireUserId(principal));
    }

    /** Removes one binding; the scope falls back to the next level's row (or none). */
    @DeleteMapping("/credentials/{credentialId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasPermission(#organizationId, 'organization', 'org:distribution:manage')")
    void deleteCredential(
            @AuthenticationPrincipal Object principal,
            @PathVariable UUID credentialId,
            @RequestParam UUID organizationId) {
        credentials.delete(credentialId, organizationId, requireUserId(principal));
    }

    /**
     * The resolution probe: whether the binding's reference resolves
     * through the selected store -- never what it resolves to (the #58
     * verify doctrine, now the chain's).
     */
    @PostMapping("/credentials/{credentialId}/verify")
    @PreAuthorize("hasPermission(#organizationId, 'organization', 'org:distribution:manage')")
    VerifyView verifyCredential(
            @AuthenticationPrincipal Object principal,
            @PathVariable UUID credentialId,
            @RequestParam UUID organizationId) {
        return new VerifyView(credentials.verify(credentialId, organizationId, requireUserId(principal)));
    }

    /** One release's distribution records inside the organization (the reconciliation view, AC4). */
    @GetMapping("/records")
    @PreAuthorize("hasPermission(#organizationId, 'organization', 'org:distribution:read')")
    PageResponse<RecordView> records(
            @RequestParam UUID organizationId,
            @RequestParam UUID releaseId,
            @RequestParam(required = false) String limit,
            @RequestParam(required = false) String cursor) {
        ListQuery query = ListQuery.of(limit, cursor);
        List<RecordView> all = dispatch.recordsOf(organizationId, releaseId);
        List<RecordView> page = all.subList(
                (int) Math.min(query.offset(), all.size()), (int) Math.min(query.offset() + query.limit(), all.size()));
        return PageResponse.of(page, query.offset() + query.limit() < all.size(), query.offset() + query.limit());
    }

    /**
     * One record's refresh from its provider (S13-02, AC4): the
     * release dashboard's beta-review answer — the adapter's status
     * observation lands on the record (status, external id, the
     * provider's own state word) and the refreshed view answers.
     */
    @PostMapping("/records/{recordId}/refresh")
    @PreAuthorize("hasPermission(#organizationId, 'organization', 'org:distribution:manage')")
    RecordView refreshRecord(
            @AuthenticationPrincipal Object principal, @PathVariable UUID recordId, @RequestParam UUID organizationId) {
        return dispatch.refresh(organizationId, recordId, requireUserId(principal));
    }

    /** The organization's TestFlight group links (S13-02, AC2: the mapping view). */
    @GetMapping("/testflight/groups")
    @PreAuthorize("hasPermission(#organizationId, 'organization', 'org:distribution:read')")
    PageResponse<TestFlightGroupService.LinkView> testFlightGroups(
            @RequestParam UUID organizationId,
            @RequestParam(required = false) String limit,
            @RequestParam(required = false) String cursor) {
        ListQuery query = ListQuery.of(limit, cursor);
        List<TestFlightGroupService.LinkView> all = testFlightGroups.list(organizationId);
        List<TestFlightGroupService.LinkView> page = all.subList(
                (int) Math.min(query.offset(), all.size()), (int) Math.min(query.offset() + query.limit(), all.size()));
        return PageResponse.of(page, query.offset() + query.limit() < all.size(), query.offset() + query.limit());
    }

    /**
     * Links one tester group to an ASC beta group (AC2): an existing
     * same-named external group wins, the create-on-demand option
     * mints one, and the link lands with its first idempotent
     * membership push.
     */
    @PutMapping("/testflight/groups")
    @PreAuthorize("hasPermission(#request.organizationId(), 'organization', 'org:distribution:manage')")
    TestFlightGroupService.LinkView putTestFlightGroup(
            @AuthenticationPrincipal Object principal, @RequestBody TestFlightGroupLinkRequest request) {
        return testFlightGroups.link(
                request.organizationId(),
                request.applicationId(),
                request.groupId(),
                Boolean.TRUE.equals(request.createOnDemand()),
                requireUserId(principal));
    }

    /** The idempotent re-sync of one link (AC5): pushes membership, reports drift, removes nothing provider-side. */
    @PostMapping("/testflight/groups/{linkId}/sync")
    @PreAuthorize("hasPermission(#organizationId, 'organization', 'org:distribution:manage')")
    TestFlightGroupService.SyncView syncTestFlightGroup(
            @AuthenticationPrincipal Object principal, @PathVariable UUID linkId, @RequestParam UUID organizationId) {
        return testFlightGroups.sync(linkId, organizationId, requireUserId(principal));
    }

    /** Removes one link (the ShipperDog side only; the ASC group stays). */
    @DeleteMapping("/testflight/groups/{linkId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasPermission(#organizationId, 'organization', 'org:distribution:manage')")
    void deleteTestFlightGroup(
            @AuthenticationPrincipal Object principal, @PathVariable UUID linkId, @RequestParam UUID organizationId) {
        testFlightGroups.unlink(linkId, organizationId, requireUserId(principal));
    }

    private static UUID requireUserId(Object principal) {
        if (principal instanceof org.springframework.security.oauth2.jwt.Jwt jwt) {
            return UUID.fromString(jwt.getSubject());
        }
        if (principal instanceof AuthenticatedPrincipal patPrincipal) {
            return patPrincipal.userId();
        }
        throw new DistributionException(DistributionException.Code.INVALID_REQUEST, "no authenticated principal");
    }

    /** The catalog answer: the registered channels (a wrapper shape, never a bare array). */
    record ChannelCatalogView(List<ChannelView> channels) {}

    /** The effective-chain answer (a wrapper shape, never a bare array). */
    record EffectiveChainView(List<EffectiveBinding> chain) {}

    /** One catalog row. */
    record ChannelView(
            String channelId,
            String displayName,
            String description,
            List<String> capabilities,
            List<String> credentialProviders) {}

    /**
     * The binding write's body: the level's parents (organization
     * always; application/environment as the level names them), the
     * provider, the label, non-secret metadata, and the material --
     * which no answer ever echoes.
     */
    record CredentialBindingRequest(
            UUID organizationId,
            UUID applicationId,
            UUID environmentId,
            String provider,
            String name,
            JsonNode metadata,
            String secretMaterial) {}

    /** The verify probe's answer: resolved, nothing else. */
    record VerifyView(boolean resolved) {}

    /**
     * The group-link write's body (S13-02): the organization, the
     * application whose ASC app the group belongs to, the tester
     * group, and the create-on-demand option (mint the beta group
     * when no same-named external one exists).
     */
    record TestFlightGroupLinkRequest(UUID organizationId, UUID applicationId, UUID groupId, Boolean createOnDemand) {}
}
