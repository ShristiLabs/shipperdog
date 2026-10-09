package com.shipperdog.api.analytics.internal.service;

import com.shipperdog.api.analytics.AnalyticsException;
import com.shipperdog.api.identity.PlatformAdmins;
import com.shipperdog.api.organization.OrgRole;
import com.shipperdog.api.organization.OrganizationAccess;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The dashboard read model (issue #50, S11-05, PRD 29): the
 * organization and tenant landing views, computed from a fixed
 * handful of aggregate SQL statements over the modules' tables — the
 * S11-04 read-model discipline (the {@link MetricRollupService}
 * precedent) the cross-module surface rides: no per-row service
 * loops, no N+1 list scans (AC5's no-N+1 rule), one bounded statement
 * per block.
 *
 * <p><b>Release health</b> (AC2, documented in docs/dashboards.md):
 * an application is BLOCKED when any of its releases failed (state
 * FAILED), carries a currently-failed gate ({@code release_gates}
 * FAILED and not overridden), or holds a PENDING policy hold; else
 * TESTING when any in-distribution release (DEPLOYING/ACTIVE/PROMOTED)
 * still has a target outside the effective vocabulary's final ring;
 * else HEALTHY when an in-distribution release's every target sits at
 * the final ring (production-active); else INACTIVE. Blocked outranks
 * Testing outranks Healthy — attention goes where it is needed.
 *
 * <p><b>Pending actions are role-filtered</b> (AC3's context: every
 * item carries the release ref and application facts the console
 * deep-links with): the approvals list carries a per-step
 * {@code decidableByCaller} flag from the caller's organization role
 * (the five approval roles' 1:1 type map, with the release creator's
 * self-approval guard); the security-review queue renders only for
 * ORG_ADMIN and SECURITY_REVIEWER (the feedback restricted-visibility
 * precedent); the failed-gate queue renders only for the override and
 * owner personas (ORG_ADMIN, RELEASE_MANAGER, APP_OWNER). The
 * platform administrator's wildcard sees every queue (the audited
 * override standing).
 *
 * <p><b>Tenant dashboard</b> (AC4): every statement binds the route's
 * tenant id — the read is scoped by construction, the permission
 * check conceals foreign tenants as the genuine miss, and the
 * isolation suite's matrix proves the parity
 * (IsolationEndpointRegistry). "Visible applications" is the catalog
 * union (tenant-owned plus organization-shared with an ACTIVE
 * authorization), "available versions" the working set (DRAFT/ACTIVE)
 * of those applications, "active testers" the effective view (person
 * record ACTIVE and membership ACTIVE), and "pending releases" the
 * non-terminal releases targeting the tenant.
 */
@Service
public class DashboardQueryService {

    /** The per-category bound of the pending-action lists (bounded by design, documented). */
    static final int ACTION_LIMIT = 25;

    /**
     * The distribution states: a release occupies its targets' rings
     * exactly while it is deploying, active, or promoted — the states
     * the health derivation reads as in-distribution.
     */
    private static final List<String> DISTRIBUTION_STATES = List.of("DEPLOYING", "ACTIVE", "PROMOTED");

    /** The terminal states: everything else is in flight (PRD 17's vocabulary). */
    private static final List<String> TERMINAL_STATES = List.of("COMPLETED", "ROLLED_BACK", "FAILED");

    /** The approval-type each deciding role signs (the workflow module's 1:1 map, mirrored for filtering). */
    private static final Map<OrgRole, String> APPROVAL_TYPE_OF_ROLE = Map.of(
            OrgRole.QA_ENGINEER, "QA",
            OrgRole.SECURITY_REVIEWER, "SECURITY",
            OrgRole.COMPLIANCE_REVIEWER, "COMPLIANCE",
            OrgRole.APP_OWNER, "PRODUCT",
            OrgRole.RELEASE_MANAGER, "RELEASE_MANAGER");

    private final NamedParameterJdbcTemplate jdbc;

    private final OrganizationAccess organizations;

    private final PlatformAdmins platformAdmins;

    DashboardQueryService(
            NamedParameterJdbcTemplate jdbc, OrganizationAccess organizations, PlatformAdmins platformAdmins) {
        this.jdbc = jdbc;
        this.organizations = organizations;
        this.platformAdmins = platformAdmins;
    }

    /**
     * The organization dashboard (AC1): the six counts of PRD 29's
     * block — every one reconciling with its list endpoint (the
     * integration test's proof) — beside the per-application health
     * rows and the caller's role-filtered pending actions.
     */
    @Transactional(readOnly = true)
    public OrgDashboardView organizationDashboard(UUID actorId, UUID organizationId) {
        if (!organizations.organizationExists(organizationId)) {
            // The platform wildcard passes the permission check for an
            // unknown id; the route still answers the genuine miss.
            throw new AnalyticsException(AnalyticsException.Code.ORGANIZATION_NOT_FOUND, "unknown organization");
        }
        OrgRole callerRole = organizations.activeOrganizationRoles(actorId).get(organizationId);
        boolean platform = platformAdmins.isPlatformAdmin(actorId);
        OrgScope scope = new OrgScope(organizationId, finalRingPositionOf(organizationId));

        OrgCountsRow counts = countsOf(scope);
        List<ApplicationHealthView> applications = applicationHealthOf(scope);
        List<ApprovalStepRow> steps = pendingApprovalStepsOf(scope);
        List<ApprovalStepView> approvals = approvalsOf(steps, actorId, callerRole, platform);
        List<ApprovalStepView> reviews = reviewsOf(steps, callerRole, platform);
        List<FailedGateView> failedGates = failedGatesVisible(callerRole, platform) ? failedGatesOf(scope) : List.of();

        return new OrgDashboardView(
                organizationId,
                Instant.now().toString(),
                new OrgCountsView(
                        counts.applications(),
                        counts.tenants(),
                        counts.activeReleases(),
                        counts.pendingApprovals(),
                        counts.testing(),
                        counts.production()),
                applications,
                new PendingActionsView(approvals, reviews, failedGates));
    }

    /**
     * The tenant dashboard (AC4): PRD 29's tenant block, every count
     * scoped to the route's tenant by the bound parameter.
     */
    @Transactional(readOnly = true)
    public TenantDashboardView tenantDashboard(UUID tenantId) {
        UUID owningOrganization = owningOrganizationOf(tenantId);
        TenantScope scope = new TenantScope(tenantId, finalRingPositionOf(owningOrganization));

        TenantCountsRow counts = tenantCountsOf(scope);
        List<PendingReleaseView> pending = pendingReleasesOf(scope);
        return new TenantDashboardView(
                tenantId,
                Instant.now().toString(),
                new TenantCountsView(
                        counts.applications(),
                        counts.availableVersions(),
                        counts.activeTesters(),
                        counts.pendingReleases()),
                pending);
    }

    // ------------------------------------------------------------------
    // Ring vocabulary
    // ------------------------------------------------------------------

    /**
     * The effective vocabulary's final position (Production-last is
     * enforced on writes, so the highest effective position IS the
     * production ring): the same org-preferred-over-system resolution
     * the ring module's reads run, expressed once here as SQL. The
     * seeded five rings answer 4 for every organization that never
     * customized its ladder.
     */
    private int finalRingPositionOf(UUID organizationId) {
        List<Integer> positions =
                jdbc.queryForList("""
                SELECT DISTINCT ON (code) position
                FROM rings
                WHERE organization_id = :org OR organization_id IS NULL
                ORDER BY code, organization_id NULLS LAST, position
                """, new MapSqlParameterSource("org", organizationId), Integer.class);
        return positions.stream().mapToInt(Integer::intValue).max().orElse(0);
    }

    private UUID owningOrganizationOf(UUID tenantId) {
        List<UUID> owners = jdbc.queryForList(
                "SELECT organization_id FROM tenants WHERE id = :tenant",
                new MapSqlParameterSource("tenant", tenantId),
                UUID.class);
        if (owners.isEmpty()) {
            throw new AnalyticsException(AnalyticsException.Code.TENANT_NOT_FOUND, "unknown tenant");
        }
        return owners.get(0);
    }

    // ------------------------------------------------------------------
    // Organization dashboard blocks
    // ------------------------------------------------------------------

    /** AC1's six counts, each aggregate reconciling with its list endpoint. */
    private OrgCountsRow countsOf(OrgScope scope) {
        Map<String, Object> applications =
                jdbc.queryForMap("SELECT count(*) AS n FROM applications WHERE organization_id = :org", scope.bound());
        Map<String, Object> tenants =
                jdbc.queryForMap("SELECT count(*) AS n FROM tenants WHERE organization_id = :org", scope.bound());
        Map<String, Object> releases = jdbc.queryForMap("""
                SELECT
                  count(*) FILTER (WHERE r.status::text NOT IN (:terminalStates)) AS active_releases,
                  count(*) FILTER (WHERE r.status = 'APPROVAL_PENDING') AS pending_approvals
                FROM releases r
                WHERE r.application_id IN (SELECT id FROM applications WHERE organization_id = :org)
                """, scope.bound());
        Map<String, Object> rings = jdbc.queryForMap("""
                SELECT
                  count(*) FILTER (WHERE o.target_count > 0 AND o.at_final < o.target_count) AS testing,
                  count(*) FILTER (WHERE o.target_count > 0 AND o.at_final = o.target_count) AS production
                FROM (
                  SELECT r.id,
                         count(t.tenant_id) AS target_count,
                         count(t.tenant_id) FILTER (WHERE ring.position = :finalPosition) AS at_final
                  FROM releases r
                  LEFT JOIN release_targets t ON t.release_id = r.id
                  LEFT JOIN rings ring ON ring.id = t.ring_id
                  WHERE r.status::text IN (:distributionStates)
                    AND r.application_id IN (SELECT id FROM applications WHERE organization_id = :org)
                  GROUP BY r.id
                ) o
                """, scope.bound());
        return new OrgCountsRow(
                ((Number) applications.get("n")).intValue(),
                ((Number) tenants.get("n")).intValue(),
                ((Number) releases.get("active_releases")).intValue(),
                ((Number) releases.get("pending_approvals")).intValue(),
                ((Number) rings.get("testing")).intValue(),
                ((Number) rings.get("production")).intValue());
    }

    /**
     * The per-application health rows (AC2): one grouped statement
     * carries every health input — the in-distribution occupancy split
     * (testing vs production), the failed-release count, and the
     * blocked findings (un-overridden failed gates, PENDING policy
     * holds) — and the derivation rule runs once, in Java, on the
     * assembled row.
     */
    private List<ApplicationHealthView> applicationHealthOf(OrgScope scope) {
        return jdbc.query("""
                WITH dist AS (
                  SELECT r.id AS release_id, r.application_id,
                         count(t.tenant_id) AS target_count,
                         count(t.tenant_id) FILTER (WHERE ring.position = :finalPosition) AS at_final
                  FROM releases r
                  LEFT JOIN release_targets t ON t.release_id = r.id
                  LEFT JOIN rings ring ON ring.id = t.ring_id
                  WHERE r.status::text IN (:distributionStates)
                    AND r.application_id IN (SELECT id FROM applications WHERE organization_id = :org)
                  GROUP BY r.id, r.application_id
                )
                SELECT a.id, a.name,
                       count(r.id) FILTER (WHERE r.status::text NOT IN (:terminalStates)) AS active_releases,
                       count(d.release_id) FILTER (WHERE d.target_count > 0 AND d.at_final < d.target_count) AS testing,
                       count(d.release_id) FILTER (WHERE d.target_count > 0 AND d.at_final = d.target_count) AS production,
                       count(r.id) FILTER (WHERE r.status = 'FAILED') AS failed_releases,
                       (SELECT count(*) FROM release_gates g JOIN releases gr ON gr.id = g.release_id
                          WHERE gr.application_id = a.id AND g.status = 'FAILED' AND g.overridden_by IS NULL) AS failed_gates,
                       (SELECT count(*) FROM release_policy_holds h JOIN releases hr ON hr.id = h.release_id
                          WHERE hr.application_id = a.id AND h.status = 'PENDING') AS pending_holds
                FROM applications a
                LEFT JOIN releases r ON r.application_id = a.id
                LEFT JOIN dist d ON d.release_id = r.id
                WHERE a.organization_id = :org
                GROUP BY a.id, a.name
                ORDER BY a.name
                """, scope.bound(), (rs, i) -> healthRowOf(rs));
    }

    /** The health derivation rule (AC2), one place, from the assembled inputs. */
    private static ApplicationHealthView healthRowOf(ResultSet rs) throws SQLException {
        int activeReleases = rs.getInt("active_releases");
        int testing = rs.getInt("testing");
        int production = rs.getInt("production");
        int failedReleases = rs.getInt("failed_releases");
        int failedGates = rs.getInt("failed_gates");
        int pendingHolds = rs.getInt("pending_holds");
        int blockedFindings = failedReleases + failedGates + pendingHolds;
        String health;
        if (blockedFindings > 0) {
            health = "BLOCKED";
        } else if (testing > 0) {
            health = "TESTING";
        } else if (production > 0) {
            health = "HEALTHY";
        } else {
            health = "INACTIVE";
        }
        return new ApplicationHealthView(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                health,
                activeReleases,
                testing,
                production,
                blockedFindings);
    }

    /**
     * The active approval steps of the organization's
     * APPROVAL_PENDING releases: the latest PENDING round per release,
     * its active group (the lowest group neither satisfied nor fully
     * decided — the evaluator's own rule, re-expressed as SQL), and
     * that group's PENDING steps with the deep-link context.
     */
    private List<ApprovalStepRow> pendingApprovalStepsOf(OrgScope scope) {
        return jdbc.query(
                """
                WITH latest_rounds AS (
                  SELECT DISTINCT ON (rr.release_id) rr.release_id, rr.id AS round_id, rr.round_number
                  FROM release_approval_rounds rr
                  JOIN releases r ON r.id = rr.release_id
                  WHERE rr.status = 'PENDING'
                    AND r.status = 'APPROVAL_PENDING'
                    AND r.application_id IN (SELECT id FROM applications WHERE organization_id = :org)
                  ORDER BY rr.release_id, rr.round_number DESC, rr.created_at DESC
                ),
                group_stats AS (
                  SELECT ra.round_id, ra.group_position, ra.group_mode,
                         count(*) FILTER (WHERE ra.status = 'PENDING') AS pending,
                         count(*) FILTER (WHERE ra.status = 'APPROVED') AS approved
                  FROM release_approvals ra
                  GROUP BY ra.round_id, ra.group_position, ra.group_mode
                ),
                active_groups AS (
                  SELECT DISTINCT ON (g.round_id) g.round_id, g.group_position
                  FROM group_stats g
                  WHERE NOT (g.group_mode = 'ANY' AND g.approved > 0)
                    AND NOT (g.group_mode = 'ALL' AND g.pending = 0)
                  ORDER BY g.round_id, g.group_position
                )
                SELECT ra.release_id, r.release_number, r.application_id, a.name AS application_name,
                       r.created_by, ra.approval_type, lr.round_number, ra.tenant_id AS scope_tenant
                FROM release_approvals ra
                JOIN latest_rounds lr ON lr.round_id = ra.round_id
                JOIN active_groups ag ON ag.round_id = ra.round_id AND ag.group_position = ra.group_position
                JOIN releases r ON r.id = ra.release_id
                JOIN applications a ON a.id = r.application_id
                WHERE ra.status = 'PENDING'
                ORDER BY r.release_number DESC, ra.position
                LIMIT :actionLimit
                """,
                scope.bound(),
                (rs, i) -> new ApprovalStepRow(
                        rs.getObject("release_id", UUID.class),
                        rs.getLong("release_number"),
                        rs.getObject("application_id", UUID.class),
                        rs.getString("application_name"),
                        rs.getObject("created_by", UUID.class),
                        rs.getString("approval_type"),
                        rs.getInt("round_number"),
                        rs.getObject("scope_tenant", UUID.class)));
    }

    /**
     * The approvals queue with each step's decidable flag: the caller
     * decides a step when their organization role maps to the step's
     * approval type AND they did not create the release (the
     * self-approval guard the workflow engine enforces at decision
     * time — the flag mirrors it, never replaces it). The organization
     * administrator and the platform wildcard see the whole queue with
     * the flag still computed against their own roles (an
     * administrator's panel is informational; their standing to
     * decide a step is nothing).
     */
    private static List<ApprovalStepView> approvalsOf(
            List<ApprovalStepRow> steps, UUID actorId, OrgRole callerRole, boolean platform) {
        String callerType = callerRole == null ? null : APPROVAL_TYPE_OF_ROLE.get(callerRole);
        boolean queueWide = platform || callerRole == OrgRole.ORG_ADMIN;
        List<ApprovalStepView> views = new ArrayList<>();
        for (ApprovalStepRow step : steps) {
            boolean decidable = callerType != null
                    && callerType.equals(step.approvalType())
                    && !step.releaseCreatedBy().equals(actorId);
            if (decidable || queueWide) {
                views.add(new ApprovalStepView(
                        step.releaseId(),
                        "R-" + step.releaseNumber(),
                        step.applicationId(),
                        step.applicationName(),
                        step.approvalType(),
                        step.roundNumber(),
                        step.scopeTenant(),
                        decidable));
            }
        }
        return views;
    }

    /**
     * The security-review queue: the SECURITY steps of the same active
     * set, rendered only for the restricted-visibility personas
     * (ORG_ADMIN, SECURITY_REVIEWER, and the platform override — the
     * feedback module's bar, the same standing).
     */
    private static List<ApprovalStepView> reviewsOf(List<ApprovalStepRow> steps, OrgRole callerRole, boolean platform) {
        boolean visible = platform || callerRole == OrgRole.ORG_ADMIN || callerRole == OrgRole.SECURITY_REVIEWER;
        if (!visible) {
            return List.of();
        }
        return steps.stream()
                .filter(step -> "SECURITY".equals(step.approvalType()))
                .map(step -> new ApprovalStepView(
                        step.releaseId(),
                        "R-" + step.releaseNumber(),
                        step.applicationId(),
                        step.applicationName(),
                        step.approvalType(),
                        step.roundNumber(),
                        step.scopeTenant(),
                        false))
                .toList();
    }

    /** Whether the caller sees the failed-gate queue: the override and owner personas, plus the platform override. */
    private static boolean failedGatesVisible(OrgRole callerRole, boolean platform) {
        return platform
                || callerRole == OrgRole.ORG_ADMIN
                || callerRole == OrgRole.RELEASE_MANAGER
                || callerRole == OrgRole.APP_OWNER;
    }

    /** The failed-gate queue: currently-FAILED blocking gates nobody has overridden, newest release first. */
    private List<FailedGateView> failedGatesOf(OrgScope scope) {
        return jdbc.query(
                """
                SELECT r.id, r.release_number, r.application_id, a.name AS application_name, g.gate_type
                FROM release_gates g
                JOIN releases r ON r.id = g.release_id
                JOIN applications a ON a.id = r.application_id
                WHERE g.status = 'FAILED' AND g.overridden_by IS NULL
                  AND r.application_id IN (SELECT id FROM applications WHERE organization_id = :org)
                ORDER BY r.release_number DESC, g.gate_type
                LIMIT :actionLimit
                """,
                scope.bound(),
                (rs, i) -> new FailedGateView(
                        rs.getObject("id", UUID.class),
                        "R-" + rs.getLong("release_number"),
                        rs.getObject("application_id", UUID.class),
                        rs.getString("application_name"),
                        rs.getString("gate_type")));
    }

    // ------------------------------------------------------------------
    // Tenant dashboard blocks
    // ------------------------------------------------------------------

    /** PRD 29's tenant-block counts: the catalog union, the working set, the effective testers, the in-flight targets. */
    private TenantCountsRow tenantCountsOf(TenantScope scope) {
        Map<String, Object> row = jdbc.queryForMap("""
                WITH visible AS (
                  SELECT a.id FROM applications a
                  WHERE a.tenant_id = :tenant
                  UNION
                  SELECT aa.application_id FROM tenant_application_authorizations aa
                  WHERE aa.tenant_id = :tenant AND aa.status = 'ACTIVE'
                )
                SELECT
                  (SELECT count(*) FROM visible) AS applications,
                  (SELECT count(*) FROM versions v JOIN visible ON visible.id = v.application_id
                     WHERE v.status IN ('DRAFT', 'ACTIVE')) AS available_versions,
                  (SELECT count(*) FROM tenant_tester_memberships m
                     JOIN testers t ON t.id = m.tester_id
                     WHERE m.tenant_id = :tenant AND m.status = 'ACTIVE' AND t.status = 'ACTIVE') AS active_testers,
                  (SELECT count(DISTINCT r.id) FROM releases r
                     JOIN release_targets rt ON rt.release_id = r.id
                     WHERE rt.tenant_id = :tenant AND r.status::text NOT IN (:terminalStates)) AS pending_releases
                """, scope.bound());
        return new TenantCountsRow(
                ((Number) row.get("applications")).intValue(),
                ((Number) row.get("available_versions")).intValue(),
                ((Number) row.get("active_testers")).intValue(),
                ((Number) row.get("pending_releases")).intValue());
    }

    /** The pending-release list: in-flight releases targeting the tenant, newest first, with the tenant-facing ring label. */
    private List<PendingReleaseView> pendingReleasesOf(TenantScope scope) {
        return jdbc.query(
                """
                SELECT DISTINCT r.id, r.release_number, r.application_id, a.name AS application_name,
                       r.status, rt.ring_id, ring.position
                FROM releases r
                JOIN release_targets rt ON rt.release_id = r.id AND rt.tenant_id = :tenant
                JOIN applications a ON a.id = r.application_id
                LEFT JOIN rings ring ON ring.id = rt.ring_id
                WHERE r.status::text NOT IN (:terminalStates)
                ORDER BY r.release_number DESC
                LIMIT :actionLimit
                """,
                scope.bound(),
                (rs, i) -> new PendingReleaseView(
                        rs.getObject("id", UUID.class),
                        "R-" + rs.getLong("release_number"),
                        rs.getObject("application_id", UUID.class),
                        rs.getString("application_name"),
                        rs.getString("status"),
                        rs.getObject("ring_id", UUID.class) == null
                                ? null
                                : ringLabelOf(rs.getObject("position", Integer.class), scope.finalPosition())));
    }

    /** The tenant-facing ring label: {@code production} when the target sits at the final ring, else {@code testing}. */
    private static String ringLabelOf(Integer position, int finalPosition) {
        return position != null && position == finalPosition ? "production" : "testing";
    }

    // ------------------------------------------------------------------
    // Scopes, rows, and views
    // ------------------------------------------------------------------

    /** The organization dashboard's bound parameters (the fixed aggregate set plus the resolved final ring position). */
    private record OrgScope(UUID organizationId, int finalPosition) {
        MapSqlParameterSource bound() {
            return boundBase().addValue("org", organizationId).addValue("finalPosition", finalPosition);
        }
    }

    /** The tenant dashboard's bound parameters, plus the owning organization's final ring position. */
    private record TenantScope(UUID tenantId, int finalPosition) {
        MapSqlParameterSource bound() {
            return boundBase().addValue("tenant", tenantId).addValue("finalPosition", finalPosition);
        }
    }

    /**
     * The shared bound set: the state vocabularies expand into IN
     * lists only through a {@link MapSqlParameterSource} (a plain map
     * value would bind as one parameter — the rollup service's own
     * discipline).
     */
    private static MapSqlParameterSource boundBase() {
        return new MapSqlParameterSource()
                .addValue("distributionStates", DISTRIBUTION_STATES)
                .addValue("terminalStates", TERMINAL_STATES)
                .addValue("finalPosition", 0)
                .addValue("actionLimit", ACTION_LIMIT);
    }

    /** One raw approval step row (internal; the views below are the API shape). */
    private record ApprovalStepRow(
            UUID releaseId,
            long releaseNumber,
            UUID applicationId,
            String applicationName,
            UUID releaseCreatedBy,
            String approvalType,
            int roundNumber,
            UUID scopeTenant) {}

    private record OrgCountsRow(
            int applications, int tenants, int activeReleases, int pendingApprovals, int testing, int production) {}

    private record TenantCountsRow(int applications, int availableVersions, int activeTesters, int pendingReleases) {}

    /** The organization dashboard view (PRD 29's org block). */
    public record OrgDashboardView(
            UUID organizationId,
            String generatedAt,
            OrgCountsView counts,
            List<ApplicationHealthView> applications,
            PendingActionsView pendingActions) {}

    /** PRD 29's six counts. */
    public record OrgCountsView(
            int applications, int tenants, int activeReleases, int pendingApprovals, int testing, int production) {}

    /**
     * One application's health row: the derived status (AC2's rule),
     * the in-flight counts the health bar renders, and the blocked
     * findings count (failed releases + un-overridden failed gates +
     * PENDING policy holds).
     */
    public record ApplicationHealthView(
            UUID applicationId,
            String name,
            String health,
            int activeReleases,
            int testing,
            int production,
            int blockedFindings) {}

    /** The caller's role-filtered pending actions (AC3). */
    public record PendingActionsView(
            List<ApprovalStepView> approvals, List<ApprovalStepView> reviews, List<FailedGateView> failedGates) {}

    /**
     * One pending approval step with its deep-link context: the
     * release and application the console routes to, the step's type
     * and round, the step's tenant scope when it carries one, and
     * whether THIS caller may decide it.
     */
    public record ApprovalStepView(
            UUID releaseId,
            String releaseRef,
            UUID applicationId,
            String applicationName,
            String approvalType,
            int roundNumber,
            UUID scopeTenant,
            boolean decidableByCaller) {}

    /** One currently-failed, un-overridden gate with its deep-link context. */
    public record FailedGateView(
            UUID releaseId, String releaseRef, UUID applicationId, String applicationName, String gateType) {}

    /** The tenant dashboard view (PRD 29's tenant block). */
    public record TenantDashboardView(
            UUID tenantId, String generatedAt, TenantCountsView counts, List<PendingReleaseView> pendingReleases) {}

    /** PRD 29's tenant counts. */
    public record TenantCountsView(int applications, int availableVersions, int activeTesters, int pendingReleases) {}

    /** One in-flight release targeting the tenant, with the tenant-facing ring label. */
    public record PendingReleaseView(
            UUID releaseId,
            String releaseRef,
            UUID applicationId,
            String applicationName,
            String status,
            String ring) {}
}
