package com.shipperdog.api.tester.internal.service;

import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventPublisher;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.config.CursorPageable;
import com.shipperdog.api.config.TraceAttributes;
import com.shipperdog.api.tenant.TenantAccess;
import com.shipperdog.api.tenant.TenantContext;
import com.shipperdog.api.tenant.TenantScopeExecutor;
import com.shipperdog.api.tester.TesterException;
import com.shipperdog.api.tester.TesterGroupAccess;
import com.shipperdog.api.tester.TesterGroupProvisioning;
import com.shipperdog.api.tester.internal.config.TesterProperties;
import com.shipperdog.api.tester.internal.entity.TesterEntity;
import com.shipperdog.api.tester.internal.entity.TesterGroupEntity;
import com.shipperdog.api.tester.internal.entity.TesterGroupMemberEntity;
import com.shipperdog.api.tester.internal.repository.TesterGroupCatalog;
import com.shipperdog.api.tester.internal.repository.TesterGroupMemberRepository;
import com.shipperdog.api.tester.internal.repository.TesterGroupScopedRepository;
import com.shipperdog.api.tester.internal.repository.TesterRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tester groups (issue #27, S6-02, PRD 8.9/FR-010): logical,
 * tenant-scoped tester collections — the targeting units release
 * targets (E9) and ring eligibility (S10-04) will consume, seeded with
 * the PRD's example groups on tenant creation. Groups ride the tenant
 * isolation seam (ADR-0002 tenth amendment's reserved landing): every
 * endpoint read and write runs through {@link TesterGroupScopedRepository}
 * under the request's tenant context, so a foreign tenant's group is
 * indistinguishable from a missing one. The junction
 * {@code tester_group_members} carries no tenant column — its tenant
 * dimension is the parent group's — so the service reaches members
 * only THROUGH a group the seam already served (the eleventh
 * amendment's via-parent rule).
 *
 * <p>Race discipline (issue #26's lesson, applied to membership
 * writes): addMember is a NON-transactional orchestrator over
 * {@link TesterGroupMembershipAttempt}'s own transaction, so a lost
 * composite-key race rolls back that attempt alone and the loser is
 * answered with the concurrent winner — the idempotent 200 of
 * acceptance criterion 2 — re-derived on a fresh transaction. Group
 * creation races answer the precise 409 instead (names are unique per
 * tenant; a lost race means someone else took the name).
 *
 * <p>Deactivated tenants block NEW groups and NEW memberships (the
 * issue #9 affiliation rule) but stay administrable: renames, deletes,
 * removals, and reads continue. Since S6-03 (issue #28), group
 * membership additionally requires the tester to be an
 * effectively-active member of the group's tenant (an ACTIVE
 * {@code tenant_tester_memberships} row under an ACTIVE person
 * record); the tester's existence within the group's owning
 * organization was the S6-02 grain.
 */
@Service
public class TesterGroupService implements TesterGroupAccess, TesterGroupProvisioning {

    private final TesterGroupScopedRepository groups;
    private final TesterGroupCatalog catalog;
    private final TesterGroupMemberRepository members;
    private final TesterRepository testers;
    private final TenantAccess tenants;
    private final TenantScopeExecutor scopes;
    private final TesterProperties properties;
    private final TesterGroupWriteAttempt writes;
    private final TesterGroupMembershipAttempt membership;
    private final TesterGroupMembershipConflictResolver membershipConflicts;
    private final TenantTesterMembershipService tenantMemberships;
    private final AuditEventPublisher audit;
    private final Clock clock;
    private final TraceAttributes trace;

    TesterGroupService(
            TesterGroupScopedRepository groups,
            TesterGroupCatalog catalog,
            TesterGroupMemberRepository members,
            TesterRepository testers,
            TenantAccess tenants,
            TenantScopeExecutor scopes,
            TesterProperties properties,
            TesterGroupWriteAttempt writes,
            TesterGroupMembershipAttempt membership,
            TesterGroupMembershipConflictResolver membershipConflicts,
            TenantTesterMembershipService tenantMemberships,
            AuditEventPublisher audit,
            Clock clock,
            TraceAttributes trace) {
        this.groups = groups;
        this.catalog = catalog;
        this.members = members;
        this.testers = testers;
        this.tenants = tenants;
        this.scopes = scopes;
        this.properties = properties;
        this.writes = writes;
        this.membership = membership;
        this.membershipConflicts = membershipConflicts;
        this.tenantMemberships = tenantMemberships;
        this.audit = audit;
        this.clock = clock;
        this.trace = trace;
    }

    // -------------------------------------------------------------------------
    // Creation and seeding
    // -------------------------------------------------------------------------

    /**
     * Creates a group under a tenant (acceptance criterion 1); names
     * are unique per tenant ({@code uq_tester_groups_tenant_name}). The
     * collection route carries the future owner tenant in the body, so
     * no interceptor bound a context — the executor establishes the
     * tenant's scope here (the {@code POST /api/v1/applications}
     * precedent, issue #13), and the write itself runs in
     * {@link TesterGroupWriteAttempt}'s transaction so a lost name race
     * translates to the precise 409.
     */
    public TesterGroupEntity create(UUID actorId, UUID tenantId, String rawName) {
        requireKnownTenant(tenantId);
        requireNewAffiliationTenant(tenantId);
        String name = requireName(rawName);
        return scopes.inScope(tenantId, actorId, () -> {
            if (groups.existsScoped(nameEquals(name))) {
                throw nameTaken(name);
            }
            try {
                TesterGroupEntity group = writes.attempt(actorId, tenantId, name, false);
                trace.tenant(tenantId);
                trace.testerGroup(group.getId());
                return group;
            } catch (DataIntegrityViolationException e) {
                // Lost the name race: the constraint is the
                // final authority and creation is NOT idempotent
                // (another group owns the name now).
                throw nameTaken(name);
            }
        });
    }

    /**
     * Seeds the configured default groups on tenant creation
     * (acceptance criterion 4), inside the tenant module's creation
     * transaction — a seeding failure fails the whole creation. Runs in
     * the new tenant's scope (the freshly inserted tenant row is the
     * authority for the executor's binding); the seeded rows are
     * ordinary editable groups from the moment they exist.
     */
    @Override
    public void seedDefaultGroups(UUID tenantId, UUID actorId) {
        scopes.inScope(tenantId, actorId, () -> {
            for (String name : properties.defaultGroups()) {
                if (groups.existsScoped(nameEquals(name))) {
                    continue;
                }
                try {
                    writes.attempt(actorId, tenantId, name, true);
                } catch (DataIntegrityViolationException e) {
                    // Unreachable for a fresh tenant (no other
                    // writer can hold the name yet); treated as
                    // a provisioning failure either way.
                    throw new TesterException(
                            TesterException.Code.INVALID_REQUEST,
                            "default group seeding hit an unexpected conflict: " + name);
                }
            }
            return null;
        });
    }

    // -------------------------------------------------------------------------
    // Group reads and mutations (item routes: the interceptor bound the
    // group's tenant context; the seam enforces it)
    // -------------------------------------------------------------------------

    /** Single group read; misses and foreign tenants render as the concealed 404. */
    @Transactional(readOnly = true)
    public TesterGroupEntity get(UUID groupId) {
        return mustFindGroup(groupId);
    }

    /**
     * Lists the tenant's groups (collection route: the executor
     * establishes the scope, the creation-route precedent).
     */
    public Page<TesterGroupEntity> listForTenant(UUID actorId, UUID tenantId, long offset, int limit) {
        requireKnownTenant(tenantId);
        Pageable pageable = new CursorPageable((int) offset, limit);
        return scopes.inScope(tenantId, actorId, () -> groups.findAllScoped(Specification.unrestricted(), pageable));
    }

    /**
     * Renames a group (the seeded defaults are editable like any
     * group). Uniqueness pre-checked, then the constraint speaks at the
     * database inside the guard — the catch rethrows (never returns
     * normally: PostgreSQL has aborted this transaction), so the
     * rollback is clean and the client gets the precise 409.
     */
    @Transactional
    public TesterGroupEntity rename(UUID actorId, UUID groupId, String rawName) {
        TesterGroupEntity group = mustFindGroup(groupId);
        String name = requireName(rawName);
        Map<String, String> changes = new LinkedHashMap<>();
        putChange(changes, "name", group.getName(), name);
        if (!name.equals(group.getName())
                && groups.existsScoped(nameEquals(name).and(notSelf(groupId)))) {
            throw nameTaken(name);
        }
        group.setName(name);
        try {
            TesterGroupEntity saved = groups.saveScoped(group);
            groups.flush();
            audit.publish(AuditEvent.builder(AuditEventType.TESTER_GROUP_UPDATED)
                    .userId(actorId)
                    .tenantId(group.getTenantId())
                    .occurredAt(Instant.now(clock))
                    .success(true)
                    .detail(Map.copyOf(changes))
                    .after("name=" + name)
                    .build());
            return saved;
        } catch (DataIntegrityViolationException e) {
            throw nameTaken(name);
        }
    }

    /**
     * Deletes a group and its memberships. The junction rows go first
     * (the composite-PK children reference the parent), then the group
     * itself through the seam's guarded delete. Deletion is final and
     * audited with the roster size; releases targeting the group are an
     * E9 concern.
     */
    @Transactional
    public void delete(UUID actorId, UUID groupId) {
        TesterGroupEntity group = mustFindGroup(groupId);
        List<TesterGroupMemberEntity> roster = members.findByGroupId(groupId);
        members.deleteAll(roster);
        groups.delete(group);
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("name", group.getName());
        detail.put("membersRemoved", Integer.toString(roster.size()));
        audit.publish(AuditEvent.builder(AuditEventType.TESTER_GROUP_DELETED)
                .userId(actorId)
                .tenantId(group.getTenantId())
                .occurredAt(Instant.now(clock))
                .success(true)
                .detail(detail)
                .build());
    }

    // -------------------------------------------------------------------------
    // Membership
    // -------------------------------------------------------------------------

    /**
     * Adds a tester to a group (acceptance criterion 2): idempotent —
     * a fresh pair is created (201), an existing membership is returned
     * unchanged (200, nothing written or audited), and a tester may
     * hold memberships in any number of groups. Deliberately NOT
     * transactional: the insert runs in
     * {@link TesterGroupMembershipAttempt}'s own transaction, so a lost
     * composite-key race rolls that back and is answered here with the
     * concurrent winner — a catch inside one wrapping transaction would
     * commit a PostgreSQL-aborted transaction and 500 (issue #26).
     */
    public MembershipResult addMember(UUID actorId, UUID groupId, UUID testerId) {
        TesterGroupEntity group = mustFindGroup(groupId);
        requireNewAffiliationTenant(group.getTenantId());
        requireTesterOfOwningOrganization(group, testerId);
        if (members.findByGroupIdAndTesterId(groupId, testerId).isPresent()) {
            return new MembershipResult(group, false);
        }
        try {
            membership.attempt(actorId, group.getTenantId(), groupId, testerId);
            return new MembershipResult(group, true);
        } catch (DataIntegrityViolationException e) {
            // A concurrent add of the same pair won the composite key;
            // membership is idempotent, so the winner IS the answer,
            // re-derived on a fresh transaction (this thread holds none
            // open).
            return membershipConflicts
                    .winnerOf(groupId, testerId)
                    .map(winner -> new MembershipResult(group, false))
                    .orElseThrow(() -> membershipConflicts.unexplained(groupId, testerId));
        }
    }

    /**
     * Removes a tester from a group; idempotent by acceptance criterion
     * 2 — removing an absent membership is a no-op success, not an
     * error (a foreign tenant's group never reaches here: the
     * permission check conceal-denied it first).
     */
    @Transactional
    public MembershipResult removeMember(UUID actorId, UUID groupId, UUID testerId) {
        TesterGroupEntity group = mustFindGroup(groupId);
        TesterGroupMemberEntity existing =
                members.findByGroupIdAndTesterId(groupId, testerId).orElse(null);
        if (existing == null) {
            return new MembershipResult(group, false);
        }
        members.delete(existing);
        audit.publish(AuditEvent.builder(AuditEventType.TESTER_GROUP_MEMBER_REMOVED)
                .userId(actorId)
                .tenantId(group.getTenantId())
                .occurredAt(Instant.now(clock))
                .success(true)
                .detail(TesterGroupMembershipAttempt.membershipDetail(groupId, testerId))
                .build());
        return new MembershipResult(group, true);
    }

    /**
     * The group's testers (testers-per-group listing), join-ordered and
     * cursor-paginated; tester views come from the organization-scoped
     * person records.
     */
    @Transactional(readOnly = true)
    public Page<MemberView> listMembers(UUID groupId, long offset, int limit) {
        TesterGroupEntity group = mustFindGroup(groupId);
        Page<TesterGroupMemberEntity> roster =
                members.findByGroupIdOrderByCreatedAtAscTesterIdAsc(groupId, new CursorPageable((int) offset, limit));
        Map<UUID, TesterEntity> byId =
                testers
                        .findAllById(roster.stream()
                                .map(TesterGroupMemberEntity::getTesterId)
                                .toList())
                        .stream()
                        .collect(Collectors.toMap(TesterEntity::getId, Function.identity()));
        return roster.map(row -> {
            TesterEntity tester = byId.get(row.getTesterId());
            return new MemberView(
                    row.getTesterId(),
                    tester == null ? null : tester.getEmail(),
                    tester == null ? null : tester.getFullName(),
                    tester == null ? null : tester.getStatus().name(),
                    row.getCreatedAt());
        });
    }

    /**
     * The tester's groups across their organization's tenants
     * (groups-per-tester listing): the person record is
     * organization-scoped (ADR-0002 tenth amendment) and group
     * membership spans the organization's tenants, so this is an
     * organization-grain read through the cross-tenant catalog — the
     * caller's permission was checked at the tester target first, and
     * the tenant set below is the tester's own organization's.
     */
    @Transactional(readOnly = true)
    public List<TesterGroupEntity> groupsOfTester(UUID testerId) {
        TesterEntity tester = testers.findById(testerId)
                .orElseThrow(() -> new TesterException(TesterException.Code.NOT_FOUND, "tester not found"));
        List<UUID> tenantIds = tenants.tenantIdsOfOrganization(tester.getOrganizationId());
        List<UUID> groupIds = members.findByTesterId(testerId).stream()
                .map(TesterGroupMemberEntity::getGroupId)
                .toList();
        if (groupIds.isEmpty()) {
            return List.of();
        }
        return catalog.findGroupByIdInAndTenantIdIn(groupIds, tenantIds, Sort.by("name"));
    }

    // -------------------------------------------------------------------------
    // TesterGroupAccess: cross-module surface
    // -------------------------------------------------------------------------

    /** The group's owning tenant, for the evaluator hop and route binding. */
    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> owningTenantOf(UUID groupId) {
        return catalog.findGroupById(groupId).map(TesterGroupEntity::getTenantId);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Seam-served group load: unknown and foreign render identically. An
     * UNBOUND tenant context on an item route means the interceptor
     * resolved no owning tenant — the group is unknown — and the only
     * callers who reach this state are platform administrators (the
     * evaluator's wildcard passes them on any target, existence
     * included; every role-holding caller was conceal-denied at the
     * check). The seam would fail closed with a context refusal; the
     * platform's answer for a missing resource is the concealed 404,
     * never a 500 (the live-smoke finding).
     */
    private TesterGroupEntity mustFindGroup(UUID groupId) {
        if (TenantContext.current().isEmpty()) {
            throw new TesterException(TesterException.Code.GROUP_NOT_FOUND, "tester group not found");
        }
        return groups.findScoped(groupId)
                .orElseThrow(() -> new TesterException(TesterException.Code.GROUP_NOT_FOUND, "tester group not found"));
    }

    /** The tenant must exist (platform-admin wildcard otherwise skips the evaluator's 404). */
    private void requireKnownTenant(UUID tenantId) {
        if (tenants.owningOrganizationId(tenantId).isEmpty()) {
            throw new TesterException(TesterException.Code.NOT_FOUND, "tenant not found");
        }
    }

    /** Deactivated tenants take no new groups or members (issue #9 affiliation rule). */
    private void requireNewAffiliationTenant(UUID tenantId) {
        if (!tenants.acceptsNewAffiliations(tenantId)) {
            throw new TesterException(
                    TesterException.Code.TENANT_DEACTIVATED, "cannot add tester groups to a deactivated tenant");
        }
    }

    /**
     * The tester must exist within the group tenant's owning
     * organization — a foreign organization's tester is
     * indistinguishable from a missing one — AND (since S6-03, issue
     * #28) be an effectively-active member of the group's tenant: an
     * {@code ACTIVE} {@code tenant_tester_memberships} row under an
     * {@code ACTIVE} person record. A visible-but-unlinked or suspended
     * tester answers the explicit conflict; the concealment above stays
     * reserved for foreign/missing testers.
     */
    private void requireTesterOfOwningOrganization(TesterGroupEntity group, UUID testerId) {
        TesterEntity tester = testers.findById(testerId)
                .orElseThrow(() -> new TesterException(TesterException.Code.NOT_FOUND, "tester not found"));
        UUID owningOrganization =
                tenants.owningOrganizationId(group.getTenantId()).orElse(null);
        if (owningOrganization == null || !owningOrganization.equals(tester.getOrganizationId())) {
            throw new TesterException(TesterException.Code.NOT_FOUND, "tester not found");
        }
        tenantMemberships.requireEffectivelyActiveMember(group.getTenantId(), testerId);
    }

    private static String requireName(String rawName) {
        String name = rawName == null ? "" : rawName.trim();
        if (name.isEmpty() || name.length() > 200) {
            throw new TesterException(TesterException.Code.INVALID_REQUEST, "name is required (max 200 characters)");
        }
        return name;
    }

    private static TesterException nameTaken(String name) {
        return new TesterException(
                TesterException.Code.NAME_TAKEN, "group name is already in use in the tenant: " + name);
    }

    /** Name condition composed with the seam's tenant predicate. */
    private static Specification<TesterGroupEntity> nameEquals(String name) {
        return (root, query, builder) -> builder.equal(root.get("name"), name);
    }

    /** Excludes the group itself from a uniqueness probe (rename path). */
    private static Specification<TesterGroupEntity> notSelf(UUID groupId) {
        return (root, query, builder) -> builder.notEqual(root.get("id"), groupId);
    }

    /** Before/after pair for audit detail; a null "before" means unset. */
    private static void putChange(Map<String, String> changes, String field, String before, String after) {
        changes.put(field + ".before", before == null ? "" : before);
        changes.put(field + ".after", after == null ? "" : after);
    }

    /** Membership add/remove outcome: the group and whether a row changed. */
    public record MembershipResult(TesterGroupEntity group, boolean changed) {}

    /**
     * Tester-per-group view: the person record's display fields plus
     * the membership's join time (null display fields only if the
     * person record vanished while the membership survived).
     */
    public record MemberView(UUID testerId, String email, String fullName, String status, Instant addedAt) {}
}
