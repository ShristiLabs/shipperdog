package com.shipperdog.api.authorization.internal.grant;

import com.shipperdog.api.authorization.AuthorizationEntitlement;
import com.shipperdog.api.authorization.internal.cache.AuthorizationDecisionCache.CachedGrant;
import com.shipperdog.api.authorization.internal.cache.DecisionInputs;
import com.shipperdog.api.tester.TesterAccess;
import com.shipperdog.api.version.VersionAccess;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * The S7-02 entitlement contributor (issue #31): the
 * {@code version_access} grants — the INV-005 authorization a linked
 * tester's download actually requires. Registered beside the role
 * overlay (S7-01's console contributor), it answers the engine's
 * FR-011 question for the tester persona: a subject with a linked
 * tester person record holding an applicable, unexpired grant is
 * entitled.
 *
 * <p>Two owning dimensions, one probe path (issue #31 AC2 — a grant
 * may address an application the tenant HOLDS per the S3-03 mapping,
 * not only one it owns):
 *
 * <ul>
 *   <li><strong>Tenant-owned applications</strong> — the engine's
 *       query carries the owning tenant, and the TENANT layer has
 *       already verified the subject's tester-persona access to that
 *       very tenant (the two-switches rule, resolved through the
 *       decision-input cache at order 300, before the version layer's
 *       500): a grant through that tenant covers the addressed
 *       version per its shape, no re-derivation.</li>
 *   <li><strong>Organization-shared applications</strong> (query
 *       tenant null) — the subject's linked-tenant set resolves the
 *       candidate grants (the cached per-tenant sets, filtered by
 *       application in memory); each grant carries its own granting
 *       tenant, and the contributor matches it live: the subject's
 *       access to that tenant must be effective (the same
 *       two-switches rule, re-checked here because the engine's
 *       tenant layer verified the ORGANIZATION dimension for shared
 *       applications, not this tenant) and the granting tenant must
 *       still hold the application (the ACTIVE S3-03 mapping). A
 *       de-authorization or a suspension denies on the next
 *       evaluation.</li>
 * </ul>
 *
 * <p>Semantics — the four FR-011 shapes, each computed from the
 * cached grant set at evaluation time (the PRD 42 under-100 ms
 * decision SLO; since S7-03, issue #32, the grant SET per tester x
 * tenant, the membership flag, the visibility flag, and the linked-
 * tenant set resolve through the Redis decision-input cache with
 * write-through invalidation — a warm decision makes no grant-table
 * descent; the tag and working-set probes below stay LIVE because
 * they track version lifecycle, which mutations outside the cache's
 * invalidation set change continuously):
 *
 * <ul>
 *   <li>{@code EXPLICIT_VERSION}: a grant whose version is the
 *       addressed one.</li>
 *   <li>{@code APPLICATION}: any grant for the application.</li>
 *   <li>{@code TAG}: a grant whose tag the addressed version carries
 *       at evaluation time — one composite-key descent on
 *       {@code version_tags}; a newly tagged version is covered with
 *       no grant change.</li>
 *   <li>{@code LATEST_N}: a grant whose window still contains the
 *       addressed version — one indexed count of the newer
 *       working-set versions over {@code idx_versions_application_sort}
 *       (V12's sort key, never {@code created_at}).</li>
 * </ul>
 *
 * <p>Grant administration never consults effectiveness (two-switches
 * is a decision-time rule, not a grant-time one); expiry is filtered
 * here at evaluation time. Fails closed (the seam's contract): any
 * fault answers not-entitled — a probe that cannot run is a
 * contribution that does not authorize.
 */
@Component
@Order(300)
class VersionAccessGrantEntitlement implements AuthorizationEntitlement {

    private final DecisionInputs decisionInputs;
    private final TesterAccess testers;
    private final VersionAccess versions;
    private final Clock clock;

    VersionAccessGrantEntitlement(
            DecisionInputs decisionInputs, TesterAccess testers, VersionAccess versions, Clock clock) {
        this.decisionInputs = decisionInputs;
        this.testers = testers;
        this.versions = versions;
        this.clock = clock;
    }

    @Override
    public boolean isEntitled(EntitlementQuery query) {
        try {
            UUID subject = query.subjectUserId();
            UUID testerId = testers.testerIdOfUser(subject).orElse(null);
            if (testerId == null) {
                return false;
            }
            Instant now = Instant.now(clock);
            for (CachedGrant grant : candidateGrants(query, testerId)) {
                if (isExpired(grant, now)) {
                    continue;
                }
                if (reachesThrough(grant, query, testerId) && covers(grant, query)) {
                    return true;
                }
            }
            return false;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * The subject's candidate grants for the addressed application:
     * the engine-verified tenant's cached set for a tenant-owned
     * application (one cache entry), or — for an organization-shared
     * application — the union of the cached per-tenant sets across
     * the subject's linked tenants, filtered by application. Complete
     * by construction: a grant's creation requires a retained
     * membership row, and removal retains the row, so every grant's
     * tenant sits in the linked-tenant set.
     */
    private List<CachedGrant> candidateGrants(EntitlementQuery query, UUID testerId) {
        if (query.tenantId() != null) {
            return grantsForApplication(query.tenantId(), testerId, query.applicationId());
        }
        List<CachedGrant> candidates = new ArrayList<>();
        for (UUID tenantId : decisionInputs.tenantsOfTester(testerId)) {
            candidates.addAll(grantsForApplication(tenantId, testerId, query.applicationId()));
        }
        return candidates;
    }

    private List<CachedGrant> grantsForApplication(UUID tenantId, UUID testerId, UUID applicationId) {
        return decisionInputs.grantsOf(tenantId, testerId).stream()
                .filter(grant -> grant.applicationId().equals(applicationId))
                .toList();
    }

    private static boolean isExpired(CachedGrant grant, Instant now) {
        return grant.expiresAt() != null && grant.expiresAt() <= CachedGrant.epochMicros(now);
    }

    /**
     * Whether the subject reaches the application through the grant's
     * tenant at evaluation time: for a tenant-owned application the
     * engine's tenant layer already verified exactly this tenant (no
     * re-derivation — the memoization discipline); for an
     * organization-shared application the grant's own tenant is
     * matched live (effective membership + the S3-03 mapping).
     */
    private boolean reachesThrough(CachedGrant grant, EntitlementQuery query, UUID testerId) {
        if (query.tenantId() != null) {
            return query.tenantId().equals(grant.tenantId());
        }
        return decisionInputs.effectiveMembership(grant.tenantId(), testerId)
                && decisionInputs.visibleToTenant(query.applicationId(), grant.tenantId());
    }

    /** The per-shape coverage check over the addressed version. */
    private boolean covers(CachedGrant grant, EntitlementQuery query) {
        return switch (grant.accessType()) {
            case "EXPLICIT_VERSION" ->
                grant.versionId() != null && grant.versionId().equals(query.versionId());
            case "APPLICATION" -> true;
            case "TAG" -> grant.tag() != null && versions.versionHasTag(query.versionId(), grant.tag());
            case "LATEST_N" ->
                grant.latestN() != null && versions.isWithinLatestWorkingSetN(query.versionId(), grant.latestN());
            default -> false;
        };
    }
}
