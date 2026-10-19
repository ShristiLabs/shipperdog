package com.shipperdog.api.analytics;

import com.shipperdog.api.analytics.internal.service.AnalyticsQueryService;
import com.shipperdog.api.analytics.internal.service.AnalyticsQueryService.OtaFunnelView;
import com.shipperdog.api.analytics.internal.service.DashboardQueryService;
import com.shipperdog.api.analytics.internal.service.DashboardQueryService.OtaBundleHealthView;
import com.shipperdog.api.identity.AuthenticatedPrincipal;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The dashboards API (issue #50, S11-05, PRD 29): the organization
 * landing view (the six counts, per-application release health, the
 * caller's role-filtered pending actions) and the tenant landing view
 * (the tenant block's four counts and the in-flight releases
 * targeting the tenant). The console home screens render these; the
 * computation is the S11-04 read-model discipline — a fixed handful of
 * aggregate statements per view, no N+1 list scans (AC5), served from
 * the analytics module because FR-029's charter is exactly "analytics
 * feed the dashboards" and the cross-module read precedent (the
 * rollups and series) already lives here.
 *
 * <p>Permissions: {@code org:dashboard:read} for the organization
 * dashboard — held by every functional console role (ORG_ADMIN,
 * APP_OWNER, QA_ENGINEER, SECURITY_REVIEWER, COMPLIANCE_REVIEWER,
 * RELEASE_MANAGER, VIEWER; the pending-actions panel personalizes by
 * role server-side) and not by DEVELOPER (the CLI persona without
 * registry read) or ORG_MEMBER (deny-by-default); and
 * {@code tenant:dashboard:read} for the tenant dashboard — TENANT_ADMIN
 * with the owning organization's overlay. Resource-scoped denials
 * render as the concealed 404 a genuine miss produces (PRD 12); the
 * tenant read is scoped by the bound parameter, and the isolation
 * suite's matrix proves the parity (IsolationEndpointRegistry).
 *
 * <p>The OTA dashboard reads (S16-10, issue #172, PRD 29's console
 * OtaDashboardPage) ride the SAME tenant authority under {@code
 * /dashboard/ota/*}: the funnel (stage counts, failure-reason
 * breakdown, adoption curve — the S16-09 live read, optional ring
 * filter) and the bundle-health crash-rate comparison (current vs
 * new). Both wrap the S16-09 service layer whole; the bundle must
 * belong to an application the addressed tenant can see (the catalog
 * union), and every miss conceals as the {@code BUNDLE_NOT_FOUND} a
 * genuine miss produces.
 */
@RestController
public class DashboardController {

    private final DashboardQueryService dashboards;

    private final AnalyticsQueryService queries;

    DashboardController(DashboardQueryService dashboards, AnalyticsQueryService queries) {
        this.dashboards = dashboards;
        this.queries = queries;
    }

    /**
     * The organization dashboard (AC1/AC2/AC3): PRD 29's counts block,
     * the per-application release-health rows (the BLOCKED / TESTING /
     * HEALTHY / INACTIVE derivation), and the caller's pending
     * actions — each item carrying the release and application context
     * the console deep-links with.
     */
    @GetMapping("/api/v1/organizations/{organizationId}/dashboard")
    @PreAuthorize("hasPermission(#organizationId, 'organization', 'org:dashboard:read')")
    DashboardQueryService.OrgDashboardView organizationDashboard(
            @AuthenticationPrincipal Object principal, @PathVariable UUID organizationId) {
        return dashboards.organizationDashboard(requireUserId(principal), organizationId);
    }

    /**
     * The tenant dashboard (AC4): PRD 29's tenant block — visible
     * applications (the catalog union), working-set versions, the
     * effective tester count, and the in-flight releases targeting
     * the tenant — scoped by the route's tenant through every
     * statement.
     */
    @GetMapping("/api/v1/tenants/{tenantId}/dashboard")
    @PreAuthorize("hasPermission(#tenantId, 'tenant', 'tenant:dashboard:read')")
    DashboardQueryService.TenantDashboardView tenantDashboard(
            @AuthenticationPrincipal Object principal, @PathVariable UUID tenantId) {
        return dashboards.tenantDashboard(tenantId);
    }

    /**
     * The OTA bundle funnel (S16-10): the device funnel's stage counts
     * (check -> seen -> download -> applied, beside the failure and
     * rollback legs), the failure-reason breakdown, and the adoption
     * curve over time — the S16-09 {@code otaFunnel} read served whole
     * for the console's OtaDashboardPage. The optional {@code ring}
     * filter narrows every leg through the bundle's own release's
     * timeline (release-anchored, the rollups' grain); an unknown ring
     * code is refused, never silently empty.
     */
    @GetMapping("/api/v1/tenants/{tenantId}/dashboard/ota/bundles/{bundleId}/funnel")
    @PreAuthorize("hasPermission(#tenantId, 'tenant', 'tenant:dashboard:read')")
    OtaFunnelView otaFunnel(
            @AuthenticationPrincipal Object principal,
            @PathVariable UUID tenantId,
            @PathVariable UUID bundleId,
            @RequestParam(required = false) String ring) {
        return queries.otaFunnel(tenantId, bundleId, ring);
    }

    /**
     * The OTA bundle health comparison (S16-10): the crash-free-rate
     * standing of the addressed (new) bundle beside the application's
     * current bundle — the S16-09 {@code otaBundleHealth} read, the
     * console's crash-rate comparison panel. A side without sessions
     * carries the explicit NO_DATA (null), never a zero.
     */
    @GetMapping("/api/v1/tenants/{tenantId}/dashboard/ota/bundles/{bundleId}/health")
    @PreAuthorize("hasPermission(#tenantId, 'tenant', 'tenant:dashboard:read')")
    OtaBundleHealthView otaBundleHealth(
            @AuthenticationPrincipal Object principal, @PathVariable UUID tenantId, @PathVariable UUID bundleId) {
        return dashboards.otaBundleHealth(tenantId, bundleId);
    }

    private static UUID requireUserId(Object principal) {
        if (principal instanceof Jwt jwt) {
            return UUID.fromString(jwt.getSubject());
        }
        if (principal instanceof AuthenticatedPrincipal patPrincipal) {
            return patPrincipal.userId();
        }
        throw new AnalyticsException(AnalyticsException.Code.INVALID_REQUEST, "no authenticated principal");
    }
}
