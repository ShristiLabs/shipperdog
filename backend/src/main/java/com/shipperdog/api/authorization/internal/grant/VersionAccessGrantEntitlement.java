package com.shipperdog.api.authorization.internal.grant;

import com.shipperdog.api.application.ApplicationAccess;
import com.shipperdog.api.authorization.AuthorizationEntitlement;
import com.shipperdog.api.authorization.internal.grant.VersionAccessGrantEntity.AccessType;
import com.shipperdog.api.tester.TenantTesterMembershipAccess;
import com.shipperdog.api.tester.TesterAccess;
import com.shipperdog.api.version.VersionAccess;
import java.time.Clock;
import java.time.Instant;
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
 *       very tenant (the two-switches rule, live-queried, order 300
 *       before the version layer's 500): a grant through that tenant
 *       covers the addressed version per its shape, no re-derivation.</li>
 *   <li><strong>Organization-shared applications</strong> (query
 *       tenant null) — the subject's grants for the application
 *       resolve across tenants by (tester, application); each grant
 *       carries its own granting tenant, and the contributor matches
 *       it live: the subject's access to that tenant must be
 *       effective (the same two-switches rule, re-checked here
 *       because the engine's tenant layer verified the ORGANIZATION
 *       dimension for shared applications, not this tenant) and the
 *       granting tenant must still hold the application (the ACTIVE
 *       S3-03 mapping). A de-authorization or suspension denies on
 *       the next evaluation.</li>
 * </ul>
 *
 * <p>Semantics — the four FR-011 shapes, each an indexed point
 * lookup (the PRD 42 under-100 ms decision SLO; S7-03 caches above
 * this seam, this story does not):
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

    private final VersionAccessGrantCatalog grants;
    private final TesterAccess testers;
    private final TenantTesterMembershipAccess memberships;
    private final ApplicationAccess applications;
    private final VersionAccess versions;
    private final Clock clock;

    VersionAccessGrantEntitlement(
            VersionAccessGrantCatalog grants,
            TesterAccess testers,
            TenantTesterMembershipAccess memberships,
            ApplicationAccess applications,
            VersionAccess versions,
            Clock clock) {
        this.grants = grants;
        this.testers = testers;
        this.memberships = memberships;
        this.applications = applications;
        this.versions = versions;
        this.clock = clock;
    }

    @Override
    public boolean isEntitled(EntitlementQuery query) {
        try {
            UUID testerId = testers.testerIdOfUser(query.subjectUserId()).orElse(null);
            if (testerId == null) {
                return false;
            }
            Instant now = Instant.now(clock);
            List<VersionAccessGrantEntity> live =
                    grants.findByTesterIdAndApplicationId(testerId, query.applicationId()).stream()
                            .filter(grant -> grant.getExpiresAt() == null
                                    || grant.getExpiresAt().isAfter(now))
                            .toList();
            for (VersionAccessGrantEntity grant : live) {
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
     * Whether the subject reaches the application through the grant's
     * tenant at evaluation time: for a tenant-owned application the
     * engine's tenant layer already verified exactly this tenant (no
     * re-derivation — the memoization discipline); for an
     * organization-shared application the grant's own tenant is
     * matched live (effective membership + the S3-03 mapping).
     */
    private boolean reachesThrough(VersionAccessGrantEntity grant, EntitlementQuery query, UUID testerId) {
        if (query.tenantId() != null) {
            return query.tenantId().equals(grant.getTenantId());
        }
        return memberships.isEffectivelyActive(grant.getTenantId(), testerId)
                && applications.visibleToTenant(query.applicationId(), grant.getTenantId());
    }

    /** The per-shape coverage check over the addressed version. */
    private boolean covers(VersionAccessGrantEntity grant, EntitlementQuery query) {
        AccessType shape = grant.getAccessType();
        return switch (shape) {
            case EXPLICIT_VERSION ->
                grant.getVersionId() != null && grant.getVersionId().equals(query.versionId());
            case APPLICATION -> true;
            case TAG -> grant.getTag() != null && versions.versionHasTag(query.versionId(), grant.getTag());
            case LATEST_N ->
                grant.getLatestN() != null && versions.isWithinLatestWorkingSetN(query.versionId(), grant.getLatestN());
            default -> false;
        };
    }
}
