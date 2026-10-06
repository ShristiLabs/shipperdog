package com.shipperdog.api.tester;

import com.shipperdog.api.config.Idempotent;
import com.shipperdog.api.config.ListQuery;
import com.shipperdog.api.config.PageResponse;
import com.shipperdog.api.identity.AuthenticatedPrincipal;
import com.shipperdog.api.tester.internal.entity.TesterGroupEntity;
import com.shipperdog.api.tester.internal.service.TesterGroupService;
import java.time.Instant;
import java.util.List;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tester group API (issue #27, S6-02, PRD 8.9/FR-010): tenant-scoped
 * logical tester collections — the targeting units release targets (E9)
 * and ring eligibility (S10-04) will consume. Group CRUD, idempotent
 * membership add/remove, the testers-per-group and groups-per-tester
 * listings, and tenant-creation seeding of the configurable default
 * groups (PRD's examples, editable like any group).
 *
 * <p>Every route is guarded by {@code app:tester-groups:manage} — the
 * catalog permission the authorization matrix has granted ORG_ADMIN
 * and QA_ENGINEER since issue #5 (the QA Engineer is the story's
 * persona); the catalog deliberately carries no separate read grant
 * yet. Item routes check it at the {@code 'tester-group'} target (the
 * evaluator resolves the group's owning tenant with the owning
 * organization's role overlaid); the collection routes check at the
 * {@code 'tenant'} (creation, tenant listing) or {@code 'tester'}
 * (groups-per-tester) target. Resource-scoped denials render as the
 * concealed 404 a genuine miss produces (PRD 12); a body without its
 * tenantId is denied by the permission check before validation —
 * authorization precedes validation on every guarded endpoint. Lists
 * page with the platform cursor convention; creation and membership
 * addition honor {@code Idempotency-Key} (issue #8).
 */
@RestController
@RequestMapping("/api/v1/tester-groups")
public class TesterGroupController {

    private final TesterGroupService groups;

    TesterGroupController(TesterGroupService groups) {
        this.groups = groups;
    }

    @PostMapping
    @Idempotent
    @PreAuthorize("hasPermission(#request.tenantId(), 'tenant', 'app:tester-groups:manage')")
    ResponseEntity<GroupResponse> create(
            @AuthenticationPrincipal Object principal, @RequestBody CreateRequest request) {
        TesterGroupEntity group = groups.create(requireUserId(principal), request.tenantId(), request.name());
        return ResponseEntity.status(HttpStatus.CREATED).body(GroupResponse.from(group));
    }

    /**
     * The two listing forms: {@code tenantId} lists a tenant's groups,
     * {@code testerId} lists the tester's groups across their
     * organization's tenants. Exactly one is required; the permission
     * check targets whichever is present (authorization precedes the
     * mutual-exclusion validation, the platform convention).
     */
    @GetMapping
    @PreAuthorize(
            "hasPermission(#tenantId != null ? #tenantId : #testerId, #tenantId != null ? 'tenant' : 'tester', 'app:tester-groups:manage')")
    PageResponse<GroupResponse> list(
            @AuthenticationPrincipal Object principal,
            @RequestParam(required = false) UUID tenantId,
            @RequestParam(required = false) UUID testerId,
            @RequestParam(required = false) String limit,
            @RequestParam(required = false) String cursor) {
        if ((tenantId == null) == (testerId == null)) {
            throw new TesterException(
                    TesterException.Code.INVALID_REQUEST, "exactly one of tenantId or testerId is required");
        }
        if (tenantId != null) {
            ListQuery query = ListQuery.of(limit, cursor);
            Page<GroupResponse> page = groups.listForTenant(
                            requireUserId(principal), tenantId, query.offset(), query.limit())
                    .map(GroupResponse::from);
            return PageResponse.of(page.getContent(), page.hasNext(), query.offset() + query.limit());
        }
        ListQuery.of(limit, cursor);
        List<GroupResponse> items = groups.groupsOfTester(testerId).stream()
                .map(GroupResponse::from)
                .toList();
        // The organization-grain answer is one working set (a tester's
        // groups), never an unbounded scan; cursoring stays available on
        // the tenant-grain form where the row count is unbounded.
        return PageResponse.of(items, false, items.size());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasPermission(#id, 'tester-group', 'app:tester-groups:manage')")
    GroupResponse get(@AuthenticationPrincipal Object principal, @PathVariable UUID id) {
        return GroupResponse.from(groups.get(id));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasPermission(#id, 'tester-group', 'app:tester-groups:manage')")
    GroupResponse rename(
            @AuthenticationPrincipal Object principal, @PathVariable UUID id, @RequestBody RenameRequest request) {
        return GroupResponse.from(groups.rename(requireUserId(principal), id, request.name()));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasPermission(#id, 'tester-group', 'app:tester-groups:manage')")
    void delete(@AuthenticationPrincipal Object principal, @PathVariable UUID id) {
        groups.delete(requireUserId(principal), id);
    }

    @GetMapping("/{id}/members")
    @PreAuthorize("hasPermission(#id, 'tester-group', 'app:tester-groups:manage')")
    PageResponse<TesterGroupService.MemberView> listMembers(
            @AuthenticationPrincipal Object principal,
            @PathVariable UUID id,
            @RequestParam(required = false) String limit,
            @RequestParam(required = false) String cursor) {
        ListQuery query = ListQuery.of(limit, cursor);
        Page<TesterGroupService.MemberView> page = groups.listMembers(id, query.offset(), query.limit());
        return PageResponse.of(page.getContent(), page.hasNext(), query.offset() + query.limit());
    }

    /**
     * Adds a tester to the group; idempotent (acceptance criterion 2) —
     * a fresh pair answers {@code 201}, an existing membership answers
     * {@code 200} with nothing re-written or re-audited.
     */
    @PostMapping("/{id}/members")
    @Idempotent
    @PreAuthorize("hasPermission(#id, 'tester-group', 'app:tester-groups:manage')")
    ResponseEntity<MemberAddedResponse> addMember(
            @AuthenticationPrincipal Object principal, @PathVariable UUID id, @RequestBody MemberAddRequest request) {
        if (request.testerId() == null) {
            throw new TesterException(TesterException.Code.INVALID_REQUEST, "testerId is required");
        }
        TesterGroupService.MembershipResult result = groups.addMember(requireUserId(principal), id, request.testerId());
        return ResponseEntity.status(result.changed() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(new MemberAddedResponse(id, request.testerId(), result.changed()));
    }

    /**
     * Removes a tester from the group; idempotent — an absent
     * membership is a no-op success, not an error.
     */
    @DeleteMapping("/{id}/members/{testerId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasPermission(#id, 'tester-group', 'app:tester-groups:manage')")
    void removeMember(@AuthenticationPrincipal Object principal, @PathVariable UUID id, @PathVariable UUID testerId) {
        groups.removeMember(requireUserId(principal), id, testerId);
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

    /** Creation request: the future owner tenant and the group name. */
    public record CreateRequest(UUID tenantId, String name) {}

    /** Rename request: groups are editable, including the seeded defaults. */
    public record RenameRequest(String name) {}

    /** Membership addition request. */
    public record MemberAddRequest(UUID testerId) {}

    /** Group view. */
    public record GroupResponse(UUID id, UUID tenantId, String name, Instant createdAt, Instant updatedAt) {

        static GroupResponse from(TesterGroupEntity group) {
            return new GroupResponse(
                    group.getId(), group.getTenantId(), group.getName(), group.getCreatedAt(), group.getUpdatedAt());
        }
    }

    /** Membership addition outcome; {@code added} distinguishes create from idempotent replay. */
    public record MemberAddedResponse(UUID groupId, UUID testerId, boolean added) {}
}
