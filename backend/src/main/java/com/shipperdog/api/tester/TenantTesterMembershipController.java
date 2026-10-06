package com.shipperdog.api.tester;

import com.shipperdog.api.config.Idempotent;
import com.shipperdog.api.config.ListQuery;
import com.shipperdog.api.config.PageResponse;
import com.shipperdog.api.identity.AuthenticatedPrincipal;
import com.shipperdog.api.tester.internal.entity.TenantTesterMembershipEntity;
import com.shipperdog.api.tester.internal.service.TenantTesterMembershipService;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tenant-tester membership API (issue #28, S6-03, PRD 8.8/13): the
 * portal-access join between testers and tenants — link, suspend,
 * remove (history-preserving), and both listings. Implemented by the
 * tester module (the membership is tester-domain data; the person
 * record and the group surface live there) while the ROUTES ride the
 * tenant path ({@code /api/v1/tenants/{id}/testers}), where the tenant
 * module's route interceptor binds the tenant context the isolation
 * seam enforces for every membership read and write. The
 * tenants-of-tester query rides the tester path at the organization
 * grain.
 *
 * <p>Permissions: {@code tenant:testers:read} and
 * {@code tenant:testers:manage} (new with this story, the
 * {@code tenant:members:*} vocabulary analog) — held by TENANT_ADMIN
 * and, through the owning-organization overlay, ORG_ADMIN; the
 * tenants-of-tester listing checks {@code org:testers:read} at the
 * {@code 'tester'} target (the evaluator resolves the person record's
 * owning organization — the groups-per-tester precedent). The two
 * membership vocabularies stay separate on purpose: {@code
 * tenant:members:*} governs the role-bearing console join (S2-03),
 * {@code tenant:testers:*} the portal join. Resource-scoped denials
 * render as the concealed 404 a genuine miss produces (PRD 12).
 * Listing pages with the platform cursor convention; linking honors
 * {@code Idempotency-Key} (issue #8).
 */
@RestController
public class TenantTesterMembershipController {

    private final TenantTesterMembershipService memberships;

    TenantTesterMembershipController(TenantTesterMembershipService memberships) {
        this.memberships = memberships;
    }

    /**
     * Links an ACTIVE tester to the tenant (acceptance criterion 1);
     * idempotent — a fresh pair answers {@code 201}, an existing
     * ACTIVE/SUSPENDED membership answers {@code 200} unchanged, and a
     * retained REVOKED row is reactivated ({@code 200}).
     */
    @PostMapping("/api/v1/tenants/{id}/testers")
    @Idempotent
    @PreAuthorize("hasPermission(#id, 'tenant', 'tenant:testers:manage')")
    ResponseEntity<TesterAddedResponse> link(
            @AuthenticationPrincipal Object principal, @PathVariable UUID id, @RequestBody LinkRequest request) {
        if (request.testerId() == null) {
            throw new TesterException(TesterException.Code.INVALID_REQUEST, "testerId is required");
        }
        TenantTesterMembershipService.LinkResult result =
                memberships.link(requireUserId(principal), id, request.testerId());
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(new TesterAddedResponse(
                        id,
                        request.testerId(),
                        result.created(),
                        result.changed(),
                        result.view().membershipStatus(),
                        result.view().effectiveAccess()));
    }

    /**
     * The tenant's testers. The default answer is the EFFECTIVE view
     * (person record ACTIVE and membership ACTIVE — tenant-scoped
     * visibility); {@code status=ACTIVE|SUSPENDED|REVOKED} answers the
     * administrative view of that membership status and
     * {@code status=ALL} every retained row (the history removal
     * preserves).
     */
    @GetMapping("/api/v1/tenants/{id}/testers")
    @PreAuthorize("hasPermission(#id, 'tenant', 'tenant:testers:read')")
    PageResponse<TenantTesterMembershipService.MembershipView> listTesters(
            @AuthenticationPrincipal Object principal,
            @PathVariable UUID id,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String limit,
            @RequestParam(required = false) String cursor) {
        ListQuery query = ListQuery.of(limit, cursor);
        Page<TenantTesterMembershipService.MembershipView> page =
                memberships.listTestersOfTenant(id, status, query.offset(), query.limit());
        return PageResponse.of(page.getContent(), page.hasNext(), query.offset() + query.limit());
    }

    /**
     * One membership: both statuses and the computed effective-access
     * flag — the four-combination observable (acceptance criterion 2).
     */
    @GetMapping("/api/v1/tenants/{id}/testers/{testerId}")
    @PreAuthorize("hasPermission(#id, 'tenant', 'tenant:testers:read')")
    TenantTesterMembershipService.MembershipView getMembership(
            @AuthenticationPrincipal Object principal, @PathVariable UUID id, @PathVariable UUID testerId) {
        return memberships.getMembership(id, testerId);
    }

    /**
     * Suspends or reactivates a membership (acceptance criterion 3):
     * the reversible switch, independent of the person record's status;
     * effectiveness ends on the next evaluation (live query, no
     * cache).
     */
    @PutMapping("/api/v1/tenants/{id}/testers/{testerId}/status")
    @PreAuthorize("hasPermission(#id, 'tenant', 'tenant:testers:manage')")
    TenantTesterMembershipService.MembershipView changeStatus(
            @AuthenticationPrincipal Object principal,
            @PathVariable UUID id,
            @PathVariable UUID testerId,
            @RequestBody StatusChangeRequest request) {
        return memberships.changeStatus(requireUserId(principal), id, testerId, parseStatus(request.status()));
    }

    /**
     * Removes a membership (acceptance criterion 4): the row is
     * retained REVOKED — never deleted — so the history stays visible
     * ({@code status=REVOKED} / {@code status=ALL}); idempotent.
     */
    @DeleteMapping("/api/v1/tenants/{id}/testers/{testerId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasPermission(#id, 'tenant', 'tenant:testers:manage')")
    void remove(@AuthenticationPrincipal Object principal, @PathVariable UUID id, @PathVariable UUID testerId) {
        memberships.remove(requireUserId(principal), id, testerId);
    }

    /**
     * The tester's tenants (tenants-of-tester listing, read-only): the
     * tester's memberships across their OWN organization's tenants,
     * history included — an organization-grain read checked at the
     * {@code 'tester'} target.
     */
    @GetMapping("/api/v1/testers/{id}/tenants")
    @PreAuthorize("hasPermission(#id, 'tester', 'org:testers:read')")
    PageResponse<TenantTesterMembershipService.TenantsOfTesterView> tenantsOfTester(
            @AuthenticationPrincipal Object principal,
            @PathVariable UUID id,
            @RequestParam(required = false) String limit,
            @RequestParam(required = false) String cursor) {
        ListQuery.of(limit, cursor);
        List<TenantTesterMembershipService.TenantsOfTesterView> items = memberships.tenantsOfTester(id);
        // The organization-grain answer is one working set (a tester's
        // memberships within their organization), never an unbounded
        // scan; cursoring stays available on the tenant-grain form
        // where the row count is unbounded (the issue #27 precedent).
        return PageResponse.of(items, false, items.size());
    }

    private static UUID requireUserId(Object principal) {
        if (principal instanceof Jwt jwt) {
            return UUID.fromString(jwt.getSubject());
        }
        if (principal instanceof AuthenticatedPrincipal patPrincipal) {
            return patPrincipal.userId();
        }
        throw new TesterException(TesterException.Code.INVALID_REQUEST, "no authenticated principal");
    }

    private static TenantTesterMembershipEntity.Status parseStatus(String status) {
        if (status == null) {
            throw new TesterException(TesterException.Code.INVALID_REQUEST, "status is required");
        }
        try {
            return TenantTesterMembershipEntity.Status.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new TesterException(
                    TesterException.Code.INVALID_REQUEST, "status must be ACTIVE or SUSPENDED: " + status.trim());
        }
    }

    /** Link request: the tester to attach. */
    public record LinkRequest(UUID testerId) {}

    /** Membership status transition body: ACTIVE or SUSPENDED. */
    public record StatusChangeRequest(String status) {}

    /** Link outcome; {@code linked} distinguishes create from idempotent replay or reactivation. */
    public record TesterAddedResponse(
            UUID tenantId,
            UUID testerId,
            boolean linked,
            boolean reactivated,
            String membershipStatus,
            boolean effectiveAccess) {}
}
