package com.shipperdog.api.tester.internal.service;

import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventPublisher;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.config.CursorPageable;
import com.shipperdog.api.tenant.TenantAccess;
import com.shipperdog.api.tester.TenantTesterMembershipAccess;
import com.shipperdog.api.tester.TesterException;
import com.shipperdog.api.tester.internal.entity.TenantTesterMembershipEntity;
import com.shipperdog.api.tester.internal.entity.TesterEntity;
import com.shipperdog.api.tester.internal.repository.TenantTesterMembershipCatalog;
import com.shipperdog.api.tester.internal.repository.TenantTesterMembershipScopedRepository;
import com.shipperdog.api.tester.internal.repository.TesterRepository;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tenant-tester memberships (issue #28, S6-03, PRD 8.8/13/FR-010):
 * which testers belong to which tenant, with status — the first layer
 * the E7 authorization chain evaluates. The membership row is the
 * PORTAL-access join between the organization-scoped tester person
 * record (S6-01) and the tenant, deliberately separate from
 * {@code tenant_members} (S2-03's role-bearing CONSOLE-user join): a
 * tester needs no tenant role, and a console member is not a tester.
 *
 * <p>Effective access is the two-switches rule (acceptance criterion
 * 2): the person record ACTIVE and the membership ACTIVE —
 * {@link #isEffectivelyActive} is a LIVE query (no cache; the
 * documented staleness TTL is zero), so a suspension or removal
 * reflects on the very next evaluation (acceptance criterion 3).
 *
 * <p>Race discipline (the #26 lesson, the #27 application): link is a
 * NON-transactional orchestrator over
 * {@link TenantTesterMembershipWriteAttempt}'s own transaction, so a
 * lost composite-key race rolls back that attempt alone and the loser
 * is answered with the concurrent winner — the idempotent 200 —
 * re-derived on a fresh transaction
 * ({@link TenantTesterMembershipConflictResolver}).
 *
 * <p>Removal preserves history (acceptance criterion 4): the row is
 * retained REVOKED — never deleted — so historical memberships stay
 * visible in the read-only queries ({@code status=REVOKED} /
 * {@code status=ALL}), and a re-link reactivates the retained row.
 * Deactivated tenants take no NEW links or reactivations (the issue #9
 * affiliation rule) but stay administrable: the SUSPENDED/ACTIVE
 * switch and removal of existing memberships continue.
 */
@Service
public class TenantTesterMembershipService implements TenantTesterMembershipAccess {

    /**
     * The listing order: link time, then the composite key's tester
     * component as the deterministic tiebreaker. Deliberately explicit
     * — the seam's platform default order ends in the derived
     * {@code id} attribute, which Spring Data cannot order for an
     * {@code @IdClass} entity (the derived id path is not a singular
     * bindable attribute); the composite key's real columns serve the
     * same stability guarantee.
     */
    private static final Sort LISTING_ORDER = Sort.by(Sort.Direction.ASC, "createdAt", "testerId");

    private final TenantTesterMembershipScopedRepository memberships;
    private final TenantTesterMembershipCatalog catalog;
    private final TesterRepository testers;
    private final TenantAccess tenants;
    private final TenantTesterMembershipWriteAttempt writes;
    private final TenantTesterMembershipConflictResolver conflicts;
    private final AuditEventPublisher audit;
    private final Clock clock;

    TenantTesterMembershipService(
            TenantTesterMembershipScopedRepository memberships,
            TenantTesterMembershipCatalog catalog,
            TesterRepository testers,
            TenantAccess tenants,
            TenantTesterMembershipWriteAttempt writes,
            TenantTesterMembershipConflictResolver conflicts,
            AuditEventPublisher audit,
            Clock clock) {
        this.memberships = memberships;
        this.catalog = catalog;
        this.testers = testers;
        this.tenants = tenants;
        this.writes = writes;
        this.conflicts = conflicts;
        this.audit = audit;
        this.clock = clock;
    }

    // -------------------------------------------------------------------------
    // Link (acceptance criterion 1)
    // -------------------------------------------------------------------------

    /**
     * Links an ACTIVE tester to the tenant (acceptance criterion 1):
     * a fresh pair is created ACTIVE ({@code 201}); an existing ACTIVE
     * or SUSPENDED membership is returned unchanged ({@code 200},
     * nothing written or audited — the membership switch belongs to
     * the status route); a retained REVOKED row is REACTIVATED
     * ({@code 200}, audited — removal preserved the row, so relinking
     * is an update, not a second row). Only an ACTIVE person record
     * can be linked (a foreign organization's tester is
     * indistinguishable from a missing one). Deliberately NOT
     * transactional — the TesterCreationAttempt discipline (see class
     * documentation).
     */
    public LinkResult link(UUID actorId, UUID tenantId, UUID testerId) {
        requireKnownTenant(tenantId);
        requireNewAffiliationTenant(tenantId);
        TesterEntity tester = requireTesterOfOwningOrganization(tenantId, testerId);
        if (tester.getStatus() != TesterEntity.Status.ACTIVE) {
            throw new TesterException(
                    TesterException.Code.TESTER_NOT_ACTIVE,
                    "tester status is " + tester.getStatus() + "; only ACTIVE testers can be linked");
        }
        TenantTesterMembershipEntity existing =
                memberships.findOneScoped(testerEquals(testerId)).orElse(null);
        if (existing != null) {
            if (existing.getStatus() == TenantTesterMembershipEntity.Status.REVOKED) {
                TenantTesterMembershipEntity reactivated = writes.reactivate(actorId, existing);
                return new LinkResult(viewOf(reactivated), false, true);
            }
            return new LinkResult(viewOf(existing), false, false);
        }
        try {
            TenantTesterMembershipEntity membership = writes.attempt(actorId, tenantId, testerId);
            return new LinkResult(viewOf(membership), true, false);
        } catch (DataIntegrityViolationException e) {
            // A concurrent link of the same pair won the composite key;
            // linking is idempotent, so the winner IS the answer,
            // re-derived on a fresh transaction (this thread holds none
            // open).
            return conflicts
                    .winnerOf(tenantId, testerId)
                    .map(winner -> new LinkResult(viewOf(winner), false, false))
                    .orElseThrow(() -> conflicts.unexplained(tenantId, testerId));
        }
    }

    // -------------------------------------------------------------------------
    // Membership switch and removal (acceptance criteria 3 and 4)
    // -------------------------------------------------------------------------

    /**
     * Suspends or reactivates a membership (acceptance criterion 3):
     * the reversible ACTIVE/SUSPENDED switch, independent of the
     * person record's status (acceptance criterion 1). A suspended
     * membership is excluded from effective access —
     * {@link #isEffectivelyActive} answers false on the next
     * evaluation (live query, no cache). The switch stays possible on
     * a DEACTIVATED tenant (an existing membership is not a new
     * affiliation, the issue #11 tenant-member precedent); a REVOKED
     * row is not administrable — reactivation of a removed membership
     * is a re-link (POST), audited as a creation. A no-op request
     * returns the current view, audited with equal before/after,
     * mirroring tenant updates.
     */
    @Transactional
    public MembershipView changeStatus(
            UUID actorId, UUID tenantId, UUID testerId, TenantTesterMembershipEntity.Status target) {
        TenantTesterMembershipEntity membership = mustFindMembership(tenantId, testerId);
        if (membership.getStatus() == TenantTesterMembershipEntity.Status.REVOKED
                || target == TenantTesterMembershipEntity.Status.REVOKED) {
            throw new TesterException(
                    TesterException.Code.INVALID_TRANSITION,
                    "transition " + membership.getStatus() + " -> " + target
                            + " is not allowed; a removed membership is re-linked via POST");
        }
        TenantTesterMembershipEntity.Status before = membership.getStatus();
        membership.setStatus(target);
        memberships.saveScoped(membership);
        audit.publish(AuditEvent.builder(AuditEventType.TENANT_TESTER_MEMBERSHIP_STATUS_CHANGED)
                .userId(actorId)
                .tenantId(tenantId)
                .occurredAt(Instant.now(clock))
                .success(true)
                .detail(membershipDetail(membership, before, target))
                .build());
        return viewOf(membership);
    }

    /**
     * Removes a membership (acceptance criterion 4): the row is set
     * REVOKED and RETAINED — never deleted — so the history stays
     * visible in the read-only queries and audit; effective access
     * ends on the next evaluation (live query). Idempotent: removing
     * an absent or already-removed membership is a no-op success.
     */
    @Transactional
    public boolean remove(UUID actorId, UUID tenantId, UUID testerId) {
        TenantTesterMembershipEntity membership =
                memberships.findOneScoped(testerEquals(testerId)).orElse(null);
        if (membership == null || membership.getStatus() == TenantTesterMembershipEntity.Status.REVOKED) {
            return false;
        }
        TenantTesterMembershipEntity.Status before = membership.getStatus();
        membership.setStatus(TenantTesterMembershipEntity.Status.REVOKED);
        memberships.saveScoped(membership);
        audit.publish(AuditEvent.builder(AuditEventType.TENANT_TESTER_MEMBERSHIP_REMOVED)
                .userId(actorId)
                .tenantId(tenantId)
                .occurredAt(Instant.now(clock))
                .success(true)
                .detail(membershipDetail(membership, before, TenantTesterMembershipEntity.Status.REVOKED))
                .build());
        return true;
    }

    // -------------------------------------------------------------------------
    // Reads
    // -------------------------------------------------------------------------

    /**
     * The tenant's testers (testers-of-tenant listing, cursor-paged).
     * The DEFAULT answer is the EFFECTIVE view — membership ACTIVE and
     * person record ACTIVE, the tenant-scoped visibility acceptance
     * criterion 3 names; an explicit {@code status} (ACTIVE, SUSPENDED,
     * REVOKED) answers the administrative view of that membership
     * status, and {@code ALL} answers every retained row (the history
     * acceptance criterion 4 makes visible).
     */
    @Transactional(readOnly = true)
    public Page<MembershipView> listTestersOfTenant(UUID tenantId, String status, long offset, int limit) {
        requireKnownTenant(tenantId);
        Specification<TenantTesterMembershipEntity> filter = listingFilter(status);
        Page<TenantTesterMembershipEntity> page =
                memberships.findAllScoped(filter, new CursorPageable((int) offset, limit, LISTING_ORDER));
        Map<UUID, TesterEntity> byId = personRecordsOf(page);
        return page.map(row -> viewOf(row, byId.get(row.getTesterId())));
    }

    /**
     * Single membership read (the four-combination observable): the
     * row with both statuses and the computed effective-access flag.
     */
    @Transactional(readOnly = true)
    public MembershipView getMembership(UUID tenantId, UUID testerId) {
        return viewOf(mustFindMembership(tenantId, testerId));
    }

    /**
     * The tester's tenants (tenants-of-tester listing, read-only):
     * the tester's membership rows across their OWN organization's
     * tenants — an organization-grain read no single tenant context
     * can scope (the groups-per-tester precedent, issue #27). The
     * answer is one working set (a tester's memberships within their
     * organization), history included (REVOKED rows visible —
     * acceptance criterion 4), newest link last.
     */
    @Transactional(readOnly = true)
    public List<TenantsOfTesterView> tenantsOfTester(UUID testerId) {
        TesterEntity tester = testers.findById(testerId)
                .orElseThrow(() -> new TesterException(TesterException.Code.NOT_FOUND, "tester not found"));
        Set<UUID> organizationTenants = Set.copyOf(tenants.tenantIdsOfOrganization(tester.getOrganizationId()));
        List<TenantTesterMembershipEntity> rows =
                catalog.findByTesterIdOrderByCreatedAtAscTenantIdAsc(testerId).stream()
                        .filter(row -> organizationTenants.contains(row.getTenantId()))
                        .toList();
        Map<UUID, TenantAccess.TenantSummary> tenantById = tenants.tenantSummariesOf(
                rows.stream().map(TenantTesterMembershipEntity::getTenantId).toList());
        return rows.stream()
                .map(row -> new TenantsOfTesterView(
                        row.getTenantId(),
                        tenantById.get(row.getTenantId()),
                        row.getStatus().name(),
                        isEffective(tester.getStatus(), row.getStatus()),
                        row.getCreatedAt(),
                        row.getUpdatedAt()))
                .toList();
    }

    // -------------------------------------------------------------------------
    // TenantTesterMembershipAccess: the authorization chain's live surface
    // -------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public boolean isActiveMember(UUID tenantId, UUID testerId) {
        return catalog.findByTenantIdAndTesterId(tenantId, testerId)
                .map(row -> row.getStatus() == TenantTesterMembershipEntity.Status.ACTIVE)
                .orElse(false);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isEffectivelyActive(UUID tenantId, UUID testerId) {
        return catalog.findByTenantIdAndTesterId(tenantId, testerId)
                .filter(row -> row.getStatus() == TenantTesterMembershipEntity.Status.ACTIVE)
                .map(row -> testers.findById(testerId)
                        .map(tester -> tester.getStatus() == TesterEntity.Status.ACTIVE)
                        .orElse(false))
                .orElse(false);
    }

    /**
     * Group-membership precondition (the S6-02 narrowing this story
     * lands): a tester may join a tenant's groups only as an
     * effectively-active member of that tenant — person record ACTIVE
     * and membership ACTIVE. Callers conceal the tester first
     * (foreign/missing organization); a visible-but-unlinked tester
     * answers the explicit conflict below.
     */
    public void requireEffectivelyActiveMember(UUID tenantId, UUID testerId) {
        if (!isEffectivelyActive(tenantId, testerId)) {
            throw new TesterException(
                    TesterException.Code.TESTER_NOT_TENANT_MEMBER, "the tester is not an active member of the tenant");
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Seam-served membership load: unknown and foreign render identically. */
    private TenantTesterMembershipEntity mustFindMembership(UUID tenantId, UUID testerId) {
        return memberships
                .findOneScoped(testerEquals(testerId))
                .orElseThrow(() -> new TesterException(
                        TesterException.Code.MEMBERSHIP_NOT_FOUND,
                        "tenant tester membership not found (" + tenantId + "/" + testerId + ")"));
    }

    /** The tenant must exist (platform-admin wildcard otherwise skips the evaluator's 404). */
    private void requireKnownTenant(UUID tenantId) {
        if (tenants.owningOrganizationId(tenantId).isEmpty()) {
            throw new TesterException(TesterException.Code.NOT_FOUND, "tenant not found");
        }
    }

    /** Deactivated tenants take no new tester affiliations (issue #9 affiliation rule). */
    private void requireNewAffiliationTenant(UUID tenantId) {
        if (!tenants.acceptsNewAffiliations(tenantId)) {
            throw new TesterException(
                    TesterException.Code.MEMBERSHIP_TENANT_DEACTIVATED, "cannot link testers to a deactivated tenant");
        }
    }

    /**
     * The tester must exist within the tenant's owning organization —
     * the S6-01 person-record grain. A foreign organization's tester is
     * indistinguishable from a missing one.
     */
    private TesterEntity requireTesterOfOwningOrganization(UUID tenantId, UUID testerId) {
        TesterEntity tester = testers.findById(testerId)
                .orElseThrow(() -> new TesterException(TesterException.Code.NOT_FOUND, "tester not found"));
        UUID owningOrganization = tenants.owningOrganizationId(tenantId).orElse(null);
        if (owningOrganization == null || !owningOrganization.equals(tester.getOrganizationId())) {
            throw new TesterException(TesterException.Code.NOT_FOUND, "tester not found");
        }
        return tester;
    }

    /**
     * The listing filter: the effective view by default (both switches
     * on — the subquery keeps pagination exact), one membership status,
     * or every retained row.
     */
    private static Specification<TenantTesterMembershipEntity> listingFilter(String status) {
        if (status == null || status.isBlank()) {
            return effectivelyActive();
        }
        String normalized = status.trim().toUpperCase(java.util.Locale.ROOT);
        if ("ALL".equals(normalized)) {
            return Specification.unrestricted();
        }
        TenantTesterMembershipEntity.Status membershipStatus = parseStatus(normalized);
        return (root, query, builder) -> builder.equal(root.get("status"), membershipStatus);
    }

    /**
     * The effective-view predicate: membership ACTIVE and person
     * record ACTIVE, expressed as one query so cursor pages stay exact
     * (an in-memory post-filter would silently shrink pages).
     */
    private static Specification<TenantTesterMembershipEntity> effectivelyActive() {
        return (root, query, builder) -> {
            Subquery<UUID> activeTesters = query.subquery(UUID.class);
            Root<TesterEntity> tester = activeTesters.from(TesterEntity.class);
            activeTesters
                    .select(tester.get("id"))
                    .where(builder.equal(tester.get("status"), TesterEntity.Status.ACTIVE));
            return builder.and(
                    builder.equal(root.get("status"), TenantTesterMembershipEntity.Status.ACTIVE),
                    builder.in(root.get("testerId")).value(activeTesters));
        };
    }

    private static TenantTesterMembershipEntity.Status parseStatus(String status) {
        try {
            return TenantTesterMembershipEntity.Status.valueOf(status);
        } catch (IllegalArgumentException e) {
            throw new TesterException(
                    TesterException.Code.INVALID_REQUEST,
                    "status must be ACTIVE, SUSPENDED, REVOKED, or ALL: " + status);
        }
    }

    /** Person records for a page of memberships, keyed by tester id. */
    private Map<UUID, TesterEntity> personRecordsOf(Page<TenantTesterMembershipEntity> page) {
        return testers
                .findAllById(page.stream()
                        .map(TenantTesterMembershipEntity::getTesterId)
                        .toList())
                .stream()
                .collect(Collectors.toMap(TesterEntity::getId, Function.identity()));
    }

    private MembershipView viewOf(TenantTesterMembershipEntity membership) {
        TesterEntity tester = testers.findById(membership.getTesterId()).orElse(null);
        return viewOf(membership, tester);
    }

    private MembershipView viewOf(TenantTesterMembershipEntity membership, TesterEntity tester) {
        return new MembershipView(
                membership.getTesterId(),
                tester == null ? null : tester.getEmail(),
                tester == null ? null : tester.getFullName(),
                tester == null ? null : tester.getStatus().name(),
                membership.getStatus().name(),
                isEffective(tester == null ? null : tester.getStatus(), membership.getStatus()),
                membership.getCreatedAt(),
                membership.getUpdatedAt());
    }

    /** Both switches on — null (vanished person record) is not effective. */
    private static boolean isEffective(
            TesterEntity.Status testerStatus, TenantTesterMembershipEntity.Status membershipStatus) {
        return testerStatus == TesterEntity.Status.ACTIVE
                && membershipStatus == TenantTesterMembershipEntity.Status.ACTIVE;
    }

    /** Before/after audit detail for a membership transition. */
    private static Map<String, String> membershipDetail(
            TenantTesterMembershipEntity membership,
            TenantTesterMembershipEntity.Status before,
            TenantTesterMembershipEntity.Status after) {
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("tenantId", membership.getTenantId().toString());
        detail.put("testerId", membership.getTesterId().toString());
        detail.put("status.before", before.name());
        detail.put("status.after", after.name());
        return detail;
    }

    /** Membership-by-tester condition, composed with the seam's tenant predicate. */
    private static Specification<TenantTesterMembershipEntity> testerEquals(UUID testerId) {
        return (root, query, builder) -> builder.equal(root.get("testerId"), testerId);
    }

    /** Link outcome: the view, whether a row was created, and whether a retained row changed. */
    public record LinkResult(MembershipView view, boolean created, boolean changed) {}

    /**
     * Tester-per-tenant view: the person record's display fields, both
     * statuses, and the computed effective-access flag (the
     * four-combination observable).
     */
    public record MembershipView(
            UUID testerId,
            String email,
            String fullName,
            String testerStatus,
            String membershipStatus,
            boolean effectiveAccess,
            Instant linkedAt,
            Instant updatedAt) {}

    /** Tenant-per-tester view: the tenant's display summary, the membership state, effectiveness. */
    public record TenantsOfTesterView(
            UUID tenantId,
            TenantAccess.TenantSummary tenant,
            String membershipStatus,
            boolean effectiveAccess,
            Instant linkedAt,
            Instant updatedAt) {}
}
