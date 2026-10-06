package com.shipperdog.api.authorization.internal.grant;

import com.shipperdog.api.application.ApplicationAccess;
import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.audit.AuditLogger;
import com.shipperdog.api.authorization.AuthorizationGrantException;
import com.shipperdog.api.authorization.internal.cache.AuthorizationDecisionCache;
import com.shipperdog.api.authorization.internal.cache.AuthorizationDecisionCache.CachedGrant;
import com.shipperdog.api.authorization.internal.grant.EffectiveAccessResolver.EffectiveEntry;
import com.shipperdog.api.authorization.internal.grant.EffectiveAccessResolver.EffectiveSet;
import com.shipperdog.api.authorization.internal.grant.EffectiveAccessResolver.GrantShape;
import com.shipperdog.api.authorization.internal.grant.VersionAccessGrantEntity.AccessType;
import com.shipperdog.api.config.CursorPageable;
import com.shipperdog.api.tenant.TenantAccess;
import com.shipperdog.api.tester.TenantTesterMembershipAccess;
import com.shipperdog.api.tester.TesterAccess;
import com.shipperdog.api.version.VersionAccess;
import com.shipperdog.api.version.VersionAccess.VersionCatalogEntry;
import jakarta.persistence.criteria.Predicate;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Grant administration and resolution (issue #31, S7-02, FR-011):
 * the four grant shapes' CRUD — create/list/read/update-expiry/
 * delete — plus the effective-access computation, the single
 * resolved method the portal catalog (S8-02) will cache above
 * (S7-03's decision-API performance work; this story evaluates live).
 *
 * <p><strong>Who may grant</strong> is RBAC's question, answered at
 * the routes ({@code tenant:authorization-grants:manage}: TENANT_ADMIN,
 * and ORG_ADMIN / APP_OWNER through the owning-organization overlay —
 * PRD 26's tenant-admin / app-owner authority). <strong>What a grant
 * may address</strong> is this service's validation, in concealment
 * order: the tester must exist in the tenant's organization (a
 * foreign tester is indistinguishable from a missing one), the tester
 * must hold a retained membership row with the tenant (any status —
 * the two-switches effectiveness rule is honored at DECISION time,
 * never at grant time: a suspended tester keeps their grants and
 * loses access; re-enabling restores both), the application must be
 * visible to the granting tenant (S3-03 — the chain still applies:
 * an app-wide grant never widens what the tenant itself can see),
 * and the shape's target must be real: the version under the
 * application, a tag the application actually uses, a latest-N
 * window of 1..100.
 *
 * <p><strong>Duplicates</strong> compose with the database's unique
 * grant identity: the SAME grant (shape and target) replays the
 * stored row {@code 200} idempotent (the tenant-member precedent —
 * no write, no audit), while the same identity with a DIFFERENT
 * expiry answers {@code 409 AUTHORIZATION_GRANT_CONFLICT} — renewal
 * is the expiry route's job, not a second row. A concurrent create
 * race is answered with the winner (the link discipline: a lost
 * unique-key race re-derives the concurrent winner on a fresh
 * transaction, which is why create is deliberately NOT
 * transactional).
 *
 * <p><strong>Every mutation audits</strong> actor + reason (issue
 * #31 acceptance criterion 7): CREATED, EXPIRY_CHANGED, DELETED carry
 * the grant's shape, target, and expiry state; the reason is
 * mandatory (1..1000 characters, trimmed — the revoke-distribution
 * precedent). Idempotent replays write and audit nothing.
 */
@Service
public class VersionAccessGrantService {

    private static final Sort LISTING_ORDER = Sort.by(Sort.Direction.ASC, "createdAt", "id");
    private static final int REASON_MAX = 1000;
    private static final int LATEST_N_MAX = 100;

    private final VersionAccessGrantScopedRepository grants;
    private final VersionAccessGrantCatalog catalog;
    private final TenantAccess tenants;
    private final TesterAccess testers;
    private final TenantTesterMembershipAccess memberships;
    private final ApplicationAccess applications;
    private final VersionAccess versions;
    private final AuthorizationDecisionCache decisionCache;
    private final AuditLogger audit;
    private final Clock clock;

    public VersionAccessGrantService(
            VersionAccessGrantScopedRepository grants,
            VersionAccessGrantCatalog catalog,
            TenantAccess tenants,
            TesterAccess testers,
            TenantTesterMembershipAccess memberships,
            ApplicationAccess applications,
            VersionAccess versions,
            AuthorizationDecisionCache decisionCache,
            AuditLogger audit,
            Clock clock) {
        this.grants = grants;
        this.catalog = catalog;
        this.tenants = tenants;
        this.testers = testers;
        this.memberships = memberships;
        this.applications = applications;
        this.versions = versions;
        this.decisionCache = decisionCache;
        this.audit = audit;
        this.clock = clock;
    }

    /** The create request: shape, target, expiry, and the mandatory audit reason. */
    public record CreateCommand(
            UUID testerId,
            UUID applicationId,
            String accessType,
            UUID versionId,
            String tag,
            Integer latestN,
            Instant expiresAt,
            String reason) {}

    /** Create outcome: {@code created} distinguishes 201 from the idempotent 200 replay. */
    public record CreateResult(GrantView view, boolean created) {}

    /** The grant as the API serves it. */
    public record GrantView(
            UUID id,
            UUID tenantId,
            UUID testerId,
            UUID applicationId,
            String accessType,
            UUID versionId,
            String tag,
            Integer latestN,
            Instant expiresAt,
            UUID createdBy,
            Instant createdAt,
            Instant updatedAt) {

        static GrantView of(VersionAccessGrantEntity grant) {
            return new GrantView(
                    grant.getId(),
                    grant.getTenantId(),
                    grant.getTesterId(),
                    grant.getApplicationId(),
                    grant.getAccessType().name(),
                    grant.getVersionId(),
                    grant.getTag(),
                    grant.getLatestN(),
                    grant.getExpiresAt(),
                    grant.getCreatedBy(),
                    grant.getCreatedAt(),
                    grant.getUpdatedAt());
        }
    }

    /** One effective-access entry: the version, its contributors, and its TTL. */
    public record EffectiveEntryView(
            UUID versionId, String versionName, String status, List<UUID> sourceGrantIds, Instant expiresAt) {}

    /** The tester's effective access for one application (issue #31 acceptance criterion 5 and 6). */
    public record EffectiveAccessView(
            UUID tenantId,
            UUID testerId,
            UUID applicationId,
            boolean effectiveAccess,
            Instant resolvedAt,
            List<EffectiveEntryView> entries,
            int expiredGrantCount) {}

    // -------------------------------------------------------------------------
    // Create (acceptance criteria 1-4)
    // -------------------------------------------------------------------------

    /**
     * Creates (or idempotently replays) a grant. Deliberately NOT
     * transactional — the link discipline (see class documentation).
     */
    public CreateResult create(UUID actorId, UUID tenantId, CreateCommand command) {
        UUID organizationId = requireGrantableTenant(tenantId);
        requireReason(command.reason());
        UUID testerId = requireTesterOfOrganization(command.testerId(), organizationId);
        if (!memberships.membershipExists(tenantId, testerId)) {
            throw new AuthorizationGrantException(
                    AuthorizationGrantException.Code.MEMBERSHIP_NOT_FOUND,
                    "the tester holds no membership row with the tenant");
        }
        UUID applicationId = requireVisibleApplication(command.applicationId(), tenantId);
        AccessType shape = parseShape(command.accessType());
        Instant expiresAt = requireFutureExpiry(command.expiresAt());
        ResolvedTarget target = resolveTarget(shape, command, applicationId);

        VersionAccessGrantEntity existing = findSameGrant(tenantId, testerId, applicationId, shape, target);
        if (existing != null) {
            if (sameInstant(existing.getExpiresAt(), expiresAt)) {
                return new CreateResult(GrantView.of(existing), false);
            }
            throw new AuthorizationGrantException(
                    AuthorizationGrantException.Code.CONFLICT,
                    "the same grant exists with a different expiry; use the expiry route to change it");
        }
        VersionAccessGrantEntity grant = new VersionAccessGrantEntity(
                tenantId,
                testerId,
                applicationId,
                shape,
                target.versionId(),
                target.tag(),
                target.latestN(),
                expiresAt,
                actorId);
        try {
            VersionAccessGrantEntity saved = grants.saveScoped(grant);
            // Write-through (S7-03, AC2): create is deliberately
            // non-transactional (the link discipline above), so the
            // inner save has committed by the time this runs — the
            // drop lands immediately after the new state exists and
            // before any reader can be served another decision on the
            // old set; a concurrent refill race is bounded by the TTL
            // (the safe direction: it can only delay a brand-new
            // grant's effect, never extend a deleted one's).
            decisionCache.invalidateGrantSet(tenantId, testerId);
            auditGrant(
                    AuditEventType.AUTHORIZATION_GRANT_CREATED,
                    actorId,
                    tenantId,
                    applicationId,
                    target,
                    saved,
                    command.reason());
            return new CreateResult(GrantView.of(saved), true);
        } catch (DataIntegrityViolationException e) {
            // A concurrent create of the same grant won the unique
            // identity; the winner IS the answer (idempotent replay).
            VersionAccessGrantEntity winner = findSameGrant(tenantId, testerId, applicationId, shape, target);
            if (winner == null) {
                throw e;
            }
            return new CreateResult(GrantView.of(winner), false);
        }
    }

    // -------------------------------------------------------------------------
    // Reads (acceptance criterion 5's inventory; the effective view below)
    // -------------------------------------------------------------------------

    /** The tenant's grants, creation-ordered, optional tester/application filters. */
    @Transactional(readOnly = true)
    public Page<GrantView> list(UUID tenantId, UUID testerId, UUID applicationId, long offset, int limit) {
        requireKnownTenant(tenantId);
        Specification<VersionAccessGrantEntity> filter = (root, query, criteria) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (testerId != null) {
                predicates.add(criteria.equal(root.get("testerId"), testerId));
            }
            if (applicationId != null) {
                predicates.add(criteria.equal(root.get("applicationId"), applicationId));
            }
            return criteria.and(predicates.toArray(Predicate[]::new));
        };
        return grants.findAllScoped(filter, new CursorPageable(offset, limit, LISTING_ORDER))
                .map(GrantView::of);
    }

    /** One grant inside the caller's tenant context. */
    @Transactional(readOnly = true)
    public GrantView get(UUID tenantId, UUID grantId) {
        return GrantView.of(mustFind(tenantId, grantId));
    }

    // -------------------------------------------------------------------------
    // Expiry and removal (acceptance criterion 5's TTL; removal is CRUD)
    // -------------------------------------------------------------------------

    /**
     * Changes a grant's expiry (extend, shorten, or clear to
     * never-expires): the TTL administration point. Idempotent for
     * the same value (no write, no audit — the platform's replay
     * discipline).
     */
    @Transactional
    public GrantView changeExpiry(UUID actorId, UUID tenantId, UUID grantId, Instant expiresAt, String reason) {
        requireReason(reason);
        Instant next = requireFutureExpiry(expiresAt);
        VersionAccessGrantEntity grant = mustFind(tenantId, grantId);
        if (sameInstant(grant.getExpiresAt(), next)) {
            return GrantView.of(grant);
        }
        Instant before = grant.getExpiresAt();
        grant.setExpiresAt(next);
        VersionAccessGrantEntity saved = grants.saveScoped(grant);
        // Write-through (S7-03, AC2): this transaction holds the
        // mutation, so the drop registers at before-commit — the
        // moment the new expiry becomes visible, the old set is gone.
        decisionCache.invalidateGrantSet(tenantId, grant.getTesterId());
        Map<String, String> detail = new LinkedHashMap<>(shapeDetail(saved));
        detail.put("expiry.before", String.valueOf(before));
        detail.put("expiry.after", String.valueOf(next));
        audit.record(AuditEvent.builder(AuditEventType.AUTHORIZATION_GRANT_EXPIRY_CHANGED)
                .userId(actorId)
                .tenantId(tenantId)
                .applicationId(saved.getApplicationId())
                .versionId(saved.getVersionId())
                .success(true)
                .detail(detail)
                .reason(reason.trim())
                .build());
        return GrantView.of(saved);
    }

    /**
     * Removes a grant: the row is deleted and the removal audited
     * with actor + reason (the audit trail IS the history — rows are
     * not retained, unlike tenant-tester memberships, because a
     * deleted grant authorizes nothing and its history lives in the
     * append-only audit store; S7-04 builds the revocation FLOWS
     * above this primitive).
     */
    @Transactional
    public void delete(UUID actorId, UUID tenantId, UUID grantId, String reason) {
        requireReason(reason);
        VersionAccessGrantEntity grant = mustFind(tenantId, grantId);
        grants.delete(grant);
        // Write-through (S7-03, AC2): the drop rides this transaction
        // at before-commit — revocation immediacy is the point.
        decisionCache.invalidateGrantSet(tenantId, grant.getTesterId());
        Map<String, String> detail = new LinkedHashMap<>(shapeDetail(grant));
        detail.put("testerId", String.valueOf(grant.getTesterId()));
        audit.record(AuditEvent.builder(AuditEventType.AUTHORIZATION_GRANT_DELETED)
                .userId(actorId)
                .tenantId(tenantId)
                .applicationId(grant.getApplicationId())
                .versionId(grant.getVersionId())
                .success(true)
                .detail(detail)
                .reason(reason.trim())
                .build());
    }

    // -------------------------------------------------------------------------
    // Effective access (acceptance criteria 5 and 6)
    // -------------------------------------------------------------------------

    /**
     * The tester's EFFECTIVE authorized version set for the
     * application (issue #31 acceptance criterion 6): every version
     * the tester's live grants cover, union-composed, each entry
     * carrying its contributing grants and its TTL (the nearest
     * contributing expiry; null when a contributor never expires).
     * The {@code effectiveAccess} flag is the two-switches rule
     * live-evaluated (person record ACTIVE and membership ACTIVE) —
     * a suspended tester's grants stay listed but authorize nothing
     * until re-enabled. Since S7-03 the grant set and the flag
     * resolve through the decision-input cache (write-through
     * invalidated, TTL-backed); the version catalog and the tagged
     * sets stay live so version lifecycle is reflected on the very
     * next read.
     */
    @Transactional(readOnly = true)
    public EffectiveAccessView effective(UUID tenantId, UUID testerId, UUID applicationId) {
        UUID organizationId = requireKnownTenant(tenantId);
        UUID visibleTester = requireTesterOfOrganization(testerId, organizationId);
        if (!memberships.membershipExists(tenantId, visibleTester)) {
            throw new AuthorizationGrantException(
                    AuthorizationGrantException.Code.MEMBERSHIP_NOT_FOUND,
                    "the tester holds no membership row with the tenant");
        }
        requireVisibleApplication(applicationId, tenantId);
        return resolveEffective(tenantId, visibleTester, applicationId);
    }

    /**
     * The effective-set computation the validated admin view and the
     * decision API's self-scoped eligibility listing share (S7-03):
     * callers perform their OWN authorization and concealment first
     * (the admin route its tenant/tester/application validation, the
     * eligibility route its engine decision and visibility check),
     * then this resolves the tester's authorized version set from
     * the cached grant set and the live version catalog.
     */
    @Transactional(readOnly = true)
    public EffectiveAccessView resolveEffective(UUID tenantId, UUID testerId, UUID applicationId) {
        Instant now = Instant.now(clock);
        List<GrantShape> shapes = decisionCache.grantsOf(tenantId, testerId).stream()
                .filter(grant -> grant.applicationId().equals(applicationId))
                .map(VersionAccessGrantService::shapeOf)
                .toList();
        List<VersionCatalogEntry> versionCatalog = versions.catalogOfApplication(applicationId);
        Map<String, Set<UUID>> tagged = taggedVersionSets(applicationId, shapes, now);
        EffectiveSet set = EffectiveAccessResolver.resolve(shapes, versionCatalog, tagged, now);
        int expiredGrantCount = (int) shapes.stream()
                .filter(shape -> shape.expiresAt() != null && !shape.expiresAt().isAfter(now))
                .count();
        List<EffectiveEntryView> entries = set.entries().stream()
                .map(VersionAccessGrantService::entryViewOf)
                .toList();
        return new EffectiveAccessView(
                tenantId,
                testerId,
                applicationId,
                decisionCache.effectiveMembership(tenantId, testerId),
                now,
                entries,
                expiredGrantCount);
    }

    // -------------------------------------------------------------------------
    // Validation helpers (concealment order)
    // -------------------------------------------------------------------------

    private UUID requireGrantableTenant(UUID tenantId) {
        UUID organizationId = requireKnownTenant(tenantId);
        if (!tenants.acceptsNewAffiliations(tenantId)) {
            throw new AuthorizationGrantException(
                    AuthorizationGrantException.Code.TENANT_DEACTIVATED,
                    "a deactivated tenant takes no new grants (existing grants stay administrable)");
        }
        return organizationId;
    }

    private UUID requireKnownTenant(UUID tenantId) {
        if (tenantId == null) {
            throw new AuthorizationGrantException(
                    AuthorizationGrantException.Code.INVALID_REQUEST, "tenant is required");
        }
        return tenants.owningOrganizationId(tenantId)
                .orElseThrow(() -> new AuthorizationGrantException(
                        AuthorizationGrantException.Code.TENANT_NOT_FOUND, "tenant not found"));
    }

    /** A foreign or unknown tester is indistinguishable from a missing one (PRD 12). */
    private UUID requireTesterOfOrganization(UUID testerId, UUID organizationId) {
        if (testerId == null) {
            throw new AuthorizationGrantException(
                    AuthorizationGrantException.Code.INVALID_REQUEST, "testerId is required");
        }
        UUID testerOrganization = testers.owningOrganizationOf(testerId).orElse(null);
        if (testerOrganization == null || !testerOrganization.equals(organizationId)) {
            throw new AuthorizationGrantException(
                    AuthorizationGrantException.Code.TESTER_NOT_FOUND, "tester not found");
        }
        return testerId;
    }

    private UUID requireVisibleApplication(UUID applicationId, UUID tenantId) {
        if (applicationId == null) {
            throw new AuthorizationGrantException(
                    AuthorizationGrantException.Code.INVALID_REQUEST, "applicationId is required");
        }
        if (!applications.visibleToTenant(applicationId, tenantId)) {
            throw new AuthorizationGrantException(
                    AuthorizationGrantException.Code.APPLICATION_NOT_FOUND, "application not found");
        }
        return applicationId;
    }

    private static AccessType parseShape(String accessType) {
        if (accessType == null || accessType.isBlank()) {
            throw new AuthorizationGrantException(
                    AuthorizationGrantException.Code.INVALID_REQUEST,
                    "accessType must be one of EXPLICIT_VERSION, APPLICATION, TAG, LATEST_N");
        }
        try {
            return AccessType.valueOf(accessType.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new AuthorizationGrantException(
                    AuthorizationGrantException.Code.INVALID_REQUEST,
                    "accessType must be one of EXPLICIT_VERSION, APPLICATION, TAG, LATEST_N");
        }
    }

    private record ResolvedTarget(UUID versionId, String tag, Integer latestN) {}

    /**
     * The per-shape pairing (the service-side contract the V21
     * header documents): exactly the shape's own target field set,
     * the others refused; the target must be real.
     */
    private ResolvedTarget resolveTarget(AccessType shape, CreateCommand command, UUID applicationId) {
        switch (shape) {
            case EXPLICIT_VERSION -> {
                requireAbsent(command.tag(), "tag");
                requireAbsent(command.latestN(), "latestN");
                if (command.versionId() == null) {
                    throw new AuthorizationGrantException(
                            AuthorizationGrantException.Code.INVALID_REQUEST, "versionId is required");
                }
                VersionAccess.VersionState state =
                        versions.stateOf(command.versionId()).orElse(null);
                if (state == null || !applicationId.equals(state.applicationId())) {
                    throw new AuthorizationGrantException(
                            AuthorizationGrantException.Code.VERSION_NOT_FOUND, "version not found");
                }
                return new ResolvedTarget(command.versionId(), null, null);
            }
            case APPLICATION -> {
                requireAbsent(command.versionId(), "versionId");
                requireAbsent(command.tag(), "tag");
                requireAbsent(command.latestN(), "latestN");
                return new ResolvedTarget(null, null, null);
            }
            case TAG -> {
                requireAbsent(command.versionId(), "versionId");
                requireAbsent(command.latestN(), "latestN");
                String tag = normalizeTag(command.tag());
                if (versions.versionIdsOfApplicationWithTag(applicationId, tag).isEmpty()) {
                    throw new AuthorizationGrantException(
                            AuthorizationGrantException.Code.TAG_UNKNOWN,
                            "the application carries no version tagged " + tag);
                }
                return new ResolvedTarget(null, tag, null);
            }
            case LATEST_N -> {
                requireAbsent(command.versionId(), "versionId");
                requireAbsent(command.tag(), "tag");
                if (command.latestN() == null) {
                    throw new AuthorizationGrantException(
                            AuthorizationGrantException.Code.INVALID_REQUEST, "latestN is required");
                }
                if (command.latestN() < 1 || command.latestN() > LATEST_N_MAX) {
                    throw new AuthorizationGrantException(
                            AuthorizationGrantException.Code.INVALID_REQUEST,
                            "latestN must be between 1 and " + LATEST_N_MAX);
                }
                return new ResolvedTarget(null, null, command.latestN());
            }
            default ->
                throw new AuthorizationGrantException(
                        AuthorizationGrantException.Code.INVALID_REQUEST, "unsupported access type");
        }
    }

    private static void requireAbsent(Object value, String field) {
        if (value != null) {
            throw new AuthorizationGrantException(
                    AuthorizationGrantException.Code.INVALID_REQUEST, field + " must be absent for this access type");
        }
    }

    /** The tag vocabulary's public shape (V13): trimmed, UPPER, [A-Z0-9][A-Z0-9_-]{0,49}. */
    private static String normalizeTag(String tag) {
        if (tag == null) {
            throw new AuthorizationGrantException(AuthorizationGrantException.Code.INVALID_REQUEST, "tag is required");
        }
        String normalized = tag.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("^[A-Z0-9][A-Z0-9_-]{0,49}$")) {
            throw new AuthorizationGrantException(
                    AuthorizationGrantException.Code.INVALID_REQUEST, "tag shape is invalid");
        }
        return normalized;
    }

    private Instant requireFutureExpiry(Instant expiresAt) {
        if (expiresAt == null) {
            return null;
        }
        if (!expiresAt.isAfter(Instant.now(clock))) {
            throw new AuthorizationGrantException(
                    AuthorizationGrantException.Code.INVALID_REQUEST, "expiresAt must be in the future");
        }
        return expiresAt;
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.trim().isEmpty() || reason.trim().length() > REASON_MAX) {
            throw new AuthorizationGrantException(
                    AuthorizationGrantException.Code.INVALID_REQUEST,
                    "reason is required (1..1000 characters, trimmed)");
        }
    }

    private VersionAccessGrantEntity mustFind(UUID tenantId, UUID grantId) {
        if (grantId == null) {
            throw new AuthorizationGrantException(
                    AuthorizationGrantException.Code.INVALID_REQUEST, "grant id is required");
        }
        return grants.findScoped(grantId)
                .orElseThrow(() -> new AuthorizationGrantException(
                        AuthorizationGrantException.Code.GRANT_NOT_FOUND, "grant not found"));
    }

    /** The stored grant with the same identity, if any (the working set per subject is small). */
    private VersionAccessGrantEntity findSameGrant(
            UUID tenantId, UUID testerId, UUID applicationId, AccessType shape, ResolvedTarget target) {
        return catalog.findByTenantIdAndTesterIdAndApplicationId(tenantId, testerId, applicationId).stream()
                .filter(grant -> grant.getAccessType() == shape
                        && sameId(grant.getVersionId(), target.versionId())
                        && java.util.Objects.equals(grant.getTag(), target.tag())
                        && java.util.Objects.equals(grant.getLatestN(), target.latestN()))
                .findFirst()
                .orElse(null);
    }

    private static boolean sameId(UUID a, UUID b) {
        return a == null ? b == null : a.equals(b);
    }

    private static boolean sameInstant(Instant a, Instant b) {
        return a == null ? b == null : a.equals(b);
    }

    private void auditGrant(
            AuditEventType type,
            UUID actorId,
            UUID tenantId,
            UUID applicationId,
            ResolvedTarget target,
            VersionAccessGrantEntity grant,
            String reason) {
        Map<String, String> detail = new LinkedHashMap<>(shapeDetail(grant));
        detail.put("testerId", String.valueOf(grant.getTesterId()));
        detail.put("expiresAt", String.valueOf(grant.getExpiresAt()));
        audit.record(AuditEvent.builder(type)
                .userId(actorId)
                .tenantId(tenantId)
                .applicationId(applicationId)
                .versionId(target.versionId())
                .success(true)
                .detail(detail)
                .reason(reason.trim())
                .build());
    }

    private static Map<String, String> shapeDetail(VersionAccessGrantEntity grant) {
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("grantId", String.valueOf(grant.getId()));
        detail.put("accessType", grant.getAccessType().name());
        if (grant.getTag() != null) {
            detail.put("tag", grant.getTag());
        }
        if (grant.getLatestN() != null) {
            detail.put("latestN", String.valueOf(grant.getLatestN()));
        }
        return detail;
    }

    /** The tagged-version sets for the live TAG grants (one query per distinct tag). */
    private Map<String, Set<UUID>> taggedVersionSets(UUID applicationId, List<GrantShape> shapes, Instant now) {
        Map<String, Set<UUID>> tagged = new HashMap<>();
        Set<String> queried = new HashSet<>();
        for (GrantShape shape : shapes) {
            if (shape.accessType() != AccessType.TAG || shape.tag() == null || !queried.add(shape.tag())) {
                continue;
            }
            tagged.put(shape.tag(), Set.copyOf(versions.versionIdsOfApplicationWithTag(applicationId, shape.tag())));
        }
        return tagged;
    }

    private static GrantShape shapeOf(VersionAccessGrantEntity grant) {
        return new GrantShape(
                grant.getId(),
                grant.getAccessType(),
                grant.getVersionId(),
                grant.getTag(),
                grant.getLatestN(),
                grant.getExpiresAt());
    }

    /** The cached tuple's shape: the same facts, epoch-micros expiry. */
    private static GrantShape shapeOf(CachedGrant grant) {
        return new GrantShape(
                grant.grantId(),
                AccessType.valueOf(grant.accessType()),
                grant.versionId(),
                grant.tag(),
                grant.latestN(),
                grant.expiresAt() == null ? null : CachedGrant.instantOf(grant.expiresAt()));
    }

    private static EffectiveEntryView entryViewOf(EffectiveEntry entry) {
        return new EffectiveEntryView(
                entry.versionId(), entry.versionName(), entry.status(), entry.sourceGrantIds(), entry.expiresAt());
    }
}
