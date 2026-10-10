package com.shipperdog.api.isolation;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * The endpoint registry of the isolation & authorization suite (S7-05,
 * issue #34): every M1 read surface as a declarative entry — method,
 * path template, a builder instantiating the request against one side
 * of the seeded world, the answer each legitimate persona receives on
 * their OWN side, and the genuine-miss call whose body the concealed
 * denial must be byte-identical to. The cross-tenant matrix
 * ({@code CrossTenantAccessMatrixTest}) parameterizes over this
 * catalog, and the OpenAPI completeness guard
 * ({@code IsolationEndpointCatalogTest}) fails the suite when the
 * served spec grows a read route the catalog does not know — a new
 * endpoint joins the isolation matrix by landing here, one deliberate
 * entry instead of an accidental blind spot (the issue's technical
 * note: the matrix is generated from a registry, never hand-enumerated
 * per test).
 */
final class IsolationEndpointRegistry {

    /**
     * One read surface. Own-side statuses are stated per persona: the
     * owning organization's ORG_ADMIN (organization grain), the owning
     * tenant's TENANT_ADMIN (tenant grain), and the platform
     * administrator (the wildcard — reads everything). The platform
     * persona's expectation equals the ORG_ADMIN's: the wildcard
     * passes every permission check and the seam serves the override.
     */
    interface Entry {
        /** HTTP method of the surface. */
        String method();

        /** The path template as the OpenAPI spec renders it. */
        String template();

        /** The URI addressing THIS side's data (the leak target). */
        String uri(IsolationScenarioHarness.IsolatedTenant side);

        /** The status the owning ORG_ADMIN persona receives on their own side. */
        int ownOrgAdmin();

        /** The status the owning TENANT_ADMIN persona receives on their own side. */
        int ownTenantAdmin();

        /**
         * The status a foreign attempt must render: the concealed 404
         * everywhere except target-less platform-scope surfaces (the
         * audit query), which stay one opaque 403 for foreign and miss
         * alike.
         */
        default int concealStatus() {
            return 404;
        }

        /**
         * The genuine-miss call for the acting persona: the same route
         * with an unknown resource of the same kind. A concealed
         * cross-tenant denial must be byte-identical to this answer
         * (modulo the per-request trace id).
         */
        String missUri(IsolationScenarioHarness.IsolatedTenant actorSide);

        /** A human name for failure messages. */
        default String name() {
            return method() + " " + template();
        }
    }

    /**
     * The analytics series routes' shared query: one dictionary metric
     * over a bounded recent window (this hour; the seeded world holds
     * no rollup rows, so the own-side answer is the empty page).
     */
    private static String seriesWindow() {
        java.time.Instant to = java.time.Instant.now();
        return "?metric=DOWNLOADS&from=" + to.minusSeconds(3600) + "&to=" + to;
    }

    /** Builds a GET surface from its builders and persona expectations. */
    private static Entry surface(
            String template,
            Function<IsolationScenarioHarness.IsolatedTenant, String> uri,
            int ownOrgAdmin,
            int ownTenantAdmin,
            Function<IsolationScenarioHarness.IsolatedTenant, String> missUri) {
        return new Entry() {
            @Override
            public String method() {
                return "GET";
            }

            @Override
            public String template() {
                return template;
            }

            @Override
            public String uri(IsolationScenarioHarness.IsolatedTenant side) {
                return uri.apply(side);
            }

            @Override
            public int ownOrgAdmin() {
                return ownOrgAdmin;
            }

            @Override
            public int ownTenantAdmin() {
                return ownTenantAdmin;
            }

            @Override
            public String missUri(IsolationScenarioHarness.IsolatedTenant actorSide) {
                return missUri.apply(actorSide);
            }
        };
    }

    /**
     * The catalog: every M1 read surface, grouped by module. Mutation
     * routes are deliberately absent — this is INV-001's OBSERVATION
     * proof; each mutation story pins its own denial shapes in its own
     * suite, and the completeness guard allowlists them.
     */
    static List<Entry> readSurfaces() {
        return List.of(
                // Organizations (organization grain) -----------------------------
                surface(
                        "/api/v1/organizations/{id}",
                        side -> "/api/v1/organizations/" + side.organizationId(),
                        200,
                        404,
                        side -> "/api/v1/organizations/" + UUID.randomUUID()),
                surface(
                        "/api/v1/organizations/{id}/idp-config",
                        // A fresh organization carries no IdP configuration:
                        // the module's own miss (404) IS the correct own
                        // answer; the cross-tenant probe still proves the
                        // concealment parity on this route.
                        side -> "/api/v1/organizations/" + side.organizationId() + "/idp-config",
                        404,
                        404,
                        side -> "/api/v1/organizations/" + UUID.randomUUID() + "/idp-config"),
                surface(
                        "/api/v1/organizations/{id}/members",
                        side -> "/api/v1/organizations/" + side.organizationId() + "/members",
                        200,
                        404,
                        side -> "/api/v1/organizations/" + UUID.randomUUID() + "/members"),

                // Tenants --------------------------------------------------------
                surface(
                        "/api/v1/tenants",
                        side -> "/api/v1/tenants?organizationId=" + side.organizationId(),
                        200,
                        404,
                        side -> "/api/v1/tenants?organizationId=" + UUID.randomUUID()),
                surface(
                        "/api/v1/tenants/{id}",
                        side -> "/api/v1/tenants/" + side.tenantId(),
                        200,
                        200,
                        side -> "/api/v1/tenants/" + UUID.randomUUID()),
                surface(
                        "/api/v1/tenants/{id}/members",
                        side -> "/api/v1/tenants/" + side.tenantId() + "/members",
                        200,
                        200,
                        side -> "/api/v1/tenants/" + UUID.randomUUID() + "/members"),
                surface(
                        "/api/v1/tenants/{id}/applications",
                        side -> "/api/v1/tenants/" + side.tenantId() + "/applications",
                        200,
                        200,
                        side -> "/api/v1/tenants/" + UUID.randomUUID() + "/applications"),
                surface(
                        "/api/v1/tenants/{id}/testers",
                        side -> "/api/v1/tenants/" + side.tenantId() + "/testers",
                        200,
                        200,
                        side -> "/api/v1/tenants/" + UUID.randomUUID() + "/testers"),
                surface(
                        "/api/v1/tenants/{id}/testers/{testerId}",
                        side -> "/api/v1/tenants/" + side.tenantId() + "/testers/" + side.testerId(),
                        200,
                        200,
                        side -> "/api/v1/tenants/" + UUID.randomUUID() + "/testers/" + UUID.randomUUID()),

                // Devices (S11-03) --------------------------------------------------
                // tenant:devices:read is tenant-grain with the
                // owning-organization overlay (the tenant:testers
                // shape): both personas list the tenant's registry —
                // an EMPTY page in the seeded world, the honest own
                // answer (no device exists), and the parity proof is
                // the point; concealment of a REAL foreign tenant's
                // devices is pinned with live devices by the S11-03
                // device suite.
                surface(
                        "/api/v1/tenants/{id}/devices",
                        side -> "/api/v1/tenants/" + side.tenantId() + "/devices",
                        200,
                        200,
                        side -> "/api/v1/tenants/" + UUID.randomUUID() + "/devices"),
                // The device item and export routes: the seeded world
                // holds no device, so own-side probes address a random
                // device id (the grant-item rationale — the route's
                // miss shape IS the correct own answer), and
                // concealment of a REAL foreign device (or export) is
                // pinned with live devices by the S11-03 suite.
                surface(
                        "/api/v1/tenants/{id}/devices/{deviceId}",
                        side -> "/api/v1/tenants/" + side.tenantId() + "/devices/" + UUID.randomUUID(),
                        404,
                        404,
                        side -> "/api/v1/tenants/" + UUID.randomUUID() + "/devices/" + UUID.randomUUID()),
                surface(
                        "/api/v1/tenants/{id}/devices/export",
                        side -> "/api/v1/tenants/" + side.tenantId() + "/devices/export",
                        200,
                        200,
                        side -> "/api/v1/tenants/" + UUID.randomUUID() + "/devices/export"),

                // Analytics (S11-04) ------------------------------------------------
                // The release-metrics route: the seeded world holds no
                // release, so own and miss probes share the route's miss
                // shape (the feedback route's rationale — the release
                // resolution precedes everything); concealment of a REAL
                // foreign release's metrics is pinned with live releases
                // by the S11-04 analytics suite.
                surface(
                        "/api/v1/applications/{applicationId}/releases/{releaseId}/metrics",
                        side -> "/api/v1/applications/" + side.applicationId() + "/releases/" + UUID.randomUUID()
                                + "/metrics",
                        404,
                        404,
                        side -> "/api/v1/applications/" + UUID.randomUUID() + "/releases/" + UUID.randomUUID()
                                + "/metrics"),
                // The application series: app:analytics:read is
                // organization-grain (the RBAC matrix) — the org admin
                // reads their side's (empty) page; the tenant admin of a
                // target tenant holds nothing and receives the denial's
                // concealed shape. The series window rides the harness's
                // fixture window builder (bounded, this hour).
                surface(
                        "/api/v1/applications/{applicationId}/metrics",
                        side -> "/api/v1/applications/" + side.applicationId() + "/metrics" + seriesWindow(),
                        200,
                        404,
                        side -> "/api/v1/applications/" + UUID.randomUUID() + "/metrics" + seriesWindow()),
                // The tenant series: tenant:analytics:read is the tenant
                // admin's own grain — an empty page on their side, the
                // concealed miss on a foreign tenant.
                surface(
                        "/api/v1/tenants/{tenantId}/metrics",
                        side -> "/api/v1/tenants/" + side.tenantId() + "/metrics" + seriesWindow(),
                        200,
                        200,
                        side -> "/api/v1/tenants/" + UUID.randomUUID() + "/metrics" + seriesWindow()),

                // Dashboards (S11-05) ------------------------------------------------
                // The organization dashboard: org:dashboard:read is
                // organization-grain — the org admin reads their side's
                // landing view (counts over the seeded world, an empty
                // pending-actions set); the tenant admin of a target
                // tenant holds no organization role and receives the
                // denial's concealed shape.
                surface(
                        "/api/v1/organizations/{organizationId}/dashboard",
                        side -> "/api/v1/organizations/" + side.organizationId() + "/dashboard",
                        200,
                        404,
                        side -> "/api/v1/organizations/" + UUID.randomUUID() + "/dashboard"),
                // The tenant dashboard: tenant:dashboard:read is the
                // tenant:testers shape — the tenant admin's own grain
                // with the owning organization's overlay above it, so
                // both personas read their side; a foreign tenant
                // conceals as the genuine miss.
                surface(
                        "/api/v1/tenants/{tenantId}/dashboard",
                        side -> "/api/v1/tenants/" + side.tenantId() + "/dashboard",
                        200,
                        200,
                        side -> "/api/v1/tenants/" + UUID.randomUUID() + "/dashboard"),

                // Notifications (S12-01) --------------------------------------------
                // The subscription listing: org:notifications:read is
                // organization-grain (the RBAC matrix) — the org admin
                // reads their side's (empty) page, the tenant admin of
                // a target tenant holds nothing and receives the
                // denial's concealed shape.
                surface(
                        "/api/v1/organizations/{organizationId}/notification-subscriptions",
                        side -> "/api/v1/organizations/" + side.organizationId() + "/notification-subscriptions",
                        200,
                        404,
                        side -> "/api/v1/organizations/" + UUID.randomUUID() + "/notification-subscriptions"),
                // The subscription item route: the seeded world holds
                // no subscription, so own and miss probes share the
                // route's miss shape (the grant-item rationale);
                // concealment of a REAL foreign subscription is pinned
                // with live subscriptions by the S12-01 suite.
                surface(
                        "/api/v1/organizations/{organizationId}/notification-subscriptions/{subscriptionId}",
                        side -> "/api/v1/organizations/"
                                + side.organizationId()
                                + "/notification-subscriptions/"
                                + UUID.randomUUID(),
                        404,
                        404,
                        side -> "/api/v1/organizations/"
                                + UUID.randomUUID()
                                + "/notification-subscriptions/"
                                + UUID.randomUUID()),
                // The delivery log (S12-01 AC5): organization-grain
                // like the listing — an empty page on the org admin's
                // own side, the concealed miss for the tenant persona.
                surface(
                        "/api/v1/organizations/{organizationId}/notification-deliveries",
                        side -> "/api/v1/organizations/" + side.organizationId() + "/notification-deliveries",
                        200,
                        404,
                        side -> "/api/v1/organizations/" + UUID.randomUUID() + "/notification-deliveries"),
                // The inbox: param-less and self-scoped, so "foreign"
                // and "miss" are both the caller's OWN inbox — the
                // portal-catalog rationale: the body answers the
                // caller and only the caller; a persona without an
                // in-app delivery answers the honest empty 200.
                new Entry() {
                    @Override
                    public String method() {
                        return "GET";
                    }

                    @Override
                    public String template() {
                        return "/api/v1/notification-inbox";
                    }

                    @Override
                    public String uri(IsolationScenarioHarness.IsolatedTenant side) {
                        return "/api/v1/notification-inbox";
                    }

                    @Override
                    public int ownOrgAdmin() {
                        return 200;
                    }

                    @Override
                    public int ownTenantAdmin() {
                        return 200;
                    }

                    @Override
                    public int concealStatus() {
                        return 200;
                    }

                    @Override
                    public String missUri(IsolationScenarioHarness.IsolatedTenant actorSide) {
                        return "/api/v1/notification-inbox";
                    }
                },

                // Testers (organization grain) -----------------------------------
                surface(
                        "/api/v1/testers",
                        side -> "/api/v1/testers?organizationId=" + side.organizationId(),
                        200,
                        404,
                        side -> "/api/v1/testers?organizationId=" + UUID.randomUUID()),
                surface(
                        "/api/v1/testers/{id}",
                        side -> "/api/v1/testers/" + side.testerId(),
                        200,
                        404,
                        side -> "/api/v1/testers/" + UUID.randomUUID()),
                surface(
                        "/api/v1/testers/{id}/tenants",
                        side -> "/api/v1/testers/" + side.testerId() + "/tenants",
                        200,
                        404,
                        side -> "/api/v1/testers/" + UUID.randomUUID() + "/tenants"),

                // Tester groups ---------------------------------------------------
                surface(
                        "/api/v1/tester-groups",
                        side -> "/api/v1/tester-groups?tenantId=" + side.tenantId(),
                        200,
                        404,
                        side -> "/api/v1/tester-groups?tenantId=" + UUID.randomUUID()),
                surface(
                        "/api/v1/tester-groups/{id}",
                        side -> "/api/v1/tester-groups/" + side.groupId(),
                        200,
                        404,
                        side -> "/api/v1/tester-groups/" + UUID.randomUUID()),
                surface(
                        "/api/v1/tester-groups/{id}/members",
                        side -> "/api/v1/tester-groups/" + side.groupId() + "/members",
                        200,
                        404,
                        side -> "/api/v1/tester-groups/" + UUID.randomUUID() + "/members"),

                // Application registry -------------------------------------------
                surface(
                        "/api/v1/applications",
                        side -> "/api/v1/applications?organizationId=" + side.organizationId(),
                        200,
                        404,
                        side -> "/api/v1/applications?organizationId=" + UUID.randomUUID()),
                surface(
                        "/api/v1/applications/{id}",
                        // app:applications:read is an organization-grain
                        // permission (the RBAC matrix): the tenant admin
                        // reads their tenant's applications through the
                        // tenant-prefixed listing, not this route.
                        side -> "/api/v1/applications/" + side.applicationId(),
                        200,
                        404,
                        side -> "/api/v1/applications/" + UUID.randomUUID()),
                surface(
                        "/api/v1/applications/{id}/tenants",
                        side -> "/api/v1/applications/" + side.applicationId() + "/tenants",
                        200,
                        404,
                        side -> "/api/v1/applications/" + UUID.randomUUID() + "/tenants"),

                // Versions ---------------------------------------------------------
                surface(
                        "/api/v1/applications/{applicationId}/versions",
                        // app:versions:read is organization-grain (the
                        // tenant persona uses the tenant-prefixed routes).
                        side -> "/api/v1/applications/" + side.applicationId() + "/versions",
                        200,
                        404,
                        side -> "/api/v1/applications/" + UUID.randomUUID() + "/versions"),
                surface(
                        "/api/v1/applications/{applicationId}/versions/{versionId}",
                        side -> "/api/v1/applications/" + side.applicationId() + "/versions/" + side.versionId(),
                        200,
                        404,
                        side -> "/api/v1/applications/" + UUID.randomUUID() + "/versions/" + UUID.randomUUID()),
                surface(
                        "/api/v1/applications/{applicationId}/versions/{versionId}/history",
                        side -> "/api/v1/applications/" + side.applicationId() + "/versions/" + side.versionId()
                                + "/history",
                        200,
                        404,
                        side -> "/api/v1/applications/"
                                + UUID.randomUUID()
                                + "/versions/"
                                + UUID.randomUUID()
                                + "/history"),

                // Artifacts --------------------------------------------------------
                surface(
                        "/api/v1/artifacts/{artifactId}",
                        // M1 versions are born DRAFT (distribution is
                        // M2): the engine's distributability rule denies
                        // console artifact reads of undistributed
                        // versions for every non-platform persona, so
                        // the concealed version miss IS the own answer
                        // for both personas; the parity proof still
                        // exercises the route end to end.
                        side -> "/api/v1/artifacts/" + side.artifactId() + "?version_id=" + side.versionId(),
                        404,
                        404,
                        side -> "/api/v1/artifacts/" + UUID.randomUUID() + "?version_id=" + side.versionId()),

                // The download issuance (S8-04): the engine's DOWNLOAD
                // decision is the route's entire authority (the #37
                // follow-up's relaxation — no console RBAC gate), and
                // neither matrix persona holds a download entitlement
                // (ORG_ADMIN/TENANT_ADMIN carry neither
                // app:artifacts:read nor tenant:applications:download),
                // so the concealed version miss IS the own answer for
                // both — exactly the artifact read's own shape, for the
                // entitlement layer's reason instead of the evaluator's.
                // The ALLOW path (the URL, its TTL, the audit and
                // counter) is pinned with live grants by the S8-04
                // download suite, like the grant suites pin theirs.
                surface(
                        "/api/v1/artifacts/{artifactId}/download",
                        side -> "/api/v1/artifacts/" + side.artifactId() + "/download?version_id=" + side.versionId(),
                        404,
                        404,
                        side -> "/api/v1/artifacts/" + UUID.randomUUID() + "/download?version_id=" + side.versionId()),

                // The emulator-mode download proxy (S8-04): anonymous
                // by design — the HMAC token in the path IS the
                // credential, minted only by an engine ALLOW at
                // issuance. The seeded world mints none, so every
                // persona's own answer, the foreign probe, and the
                // genuine miss are the SAME uniform 404: the parity
                // proof here is that a token guess cannot even reveal
                // that downloads exist (INV-005 applied to the one
                // surface whose callers are anonymous). The
                // valid-token legs (stream, expiry, tamper) are the
                // S8-04 download suite's.
                new Entry() {
                    @Override
                    public String method() {
                        return "GET";
                    }

                    @Override
                    public String template() {
                        return "/api/v1/downloads/{token}";
                    }

                    @Override
                    public String uri(IsolationScenarioHarness.IsolatedTenant side) {
                        return "/api/v1/downloads/" + UUID.randomUUID();
                    }

                    @Override
                    public int ownOrgAdmin() {
                        return 404;
                    }

                    @Override
                    public int ownTenantAdmin() {
                        return 404;
                    }

                    @Override
                    public String missUri(IsolationScenarioHarness.IsolatedTenant actorSide) {
                        return "/api/v1/downloads/" + UUID.randomUUID();
                    }
                },

                // Releases (S9-01) ------------------------------------------------
                surface(
                        "/api/v1/releases",
                        // app:releases:read is organization-grain (the
                        // RBAC matrix): the owning ORG_ADMIN lists (an
                        // empty page in the seeded world — no release
                        // exists), the tenant persona conceals through
                        // the application-grain permission check.
                        side -> "/api/v1/releases?applicationId=" + side.applicationId(),
                        200,
                        404,
                        side -> "/api/v1/releases?applicationId=" + UUID.randomUUID()),
                // The release-item route: the seeded world holds no
                // release, so own-side probes address a random release id
                // (the grant-item rationale — the route's miss shape is
                // itself the correct own answer, and the parity proof is
                // the point). Concealment of a REAL foreign release is
                // pinned with live releases by the S9-01 release suite.
                surface(
                        "/api/v1/releases/{releaseId}",
                        side -> "/api/v1/releases/" + UUID.randomUUID() + "?applicationId=" + side.applicationId(),
                        404,
                        404,
                        side -> "/api/v1/releases/" + UUID.randomUUID() + "?applicationId=" + UUID.randomUUID()),
                // The release history route (S9-02): the same miss-shape
                // rationale as the release item — the seeded world holds
                // no release, own-side probes address a random release id,
                // and concealment of a REAL foreign release's timeline is
                // pinned with live releases by the S9-02 transition suite.
                surface(
                        "/api/v1/releases/{releaseId}/history",
                        side -> "/api/v1/releases/" + UUID.randomUUID() + "/history?applicationId="
                                + side.applicationId(),
                        404,
                        404,
                        side -> "/api/v1/releases/" + UUID.randomUUID() + "/history?applicationId="
                                + UUID.randomUUID()),
                // The frozen-manifest route (S9-03): the same miss-shape
                // rationale — the seeded world holds no release, so own
                // and miss probes share the route's miss shape, and
                // concealment of a REAL foreign release's manifest is
                // pinned with live releases by the S9-03 manifest suite.
                surface(
                        "/api/v1/releases/{releaseId}/manifest",
                        side -> "/api/v1/releases/" + UUID.randomUUID() + "/manifest?applicationId="
                                + side.applicationId(),
                        404,
                        404,
                        side -> "/api/v1/releases/" + UUID.randomUUID() + "/manifest?applicationId="
                                + UUID.randomUUID()),
                // The manifest diff route (S9-03): both sides of the diff
                // address random releases in the seeded world; the live
                // concealment proof belongs to the manifest suite.
                surface(
                        "/api/v1/releases/{releaseId}/manifest/diff",
                        side -> "/api/v1/releases/"
                                + UUID.randomUUID()
                                + "/manifest/diff?applicationId="
                                + side.applicationId()
                                + "&otherReleaseId="
                                + UUID.randomUUID(),
                        404,
                        404,
                        side -> "/api/v1/releases/"
                                + UUID.randomUUID()
                                + "/manifest/diff?applicationId="
                                + UUID.randomUUID()
                                + "&otherReleaseId="
                                + UUID.randomUUID()),
                // The approval state route (S10-01): the seeded world
                // holds no release, so own-side probes address a random
                // release id (the history route's rationale — the miss
                // shape IS the correct own answer), and concealment of a
                // REAL foreign release's approvals is pinned with live
                // releases by the S10-01 approval suite.
                surface(
                        "/api/v1/releases/{releaseId}/approvals",
                        side -> "/api/v1/releases/" + UUID.randomUUID() + "/approvals?applicationId="
                                + side.applicationId(),
                        404,
                        404,
                        side -> "/api/v1/releases/" + UUID.randomUUID() + "/approvals?applicationId="
                                + UUID.randomUUID()),
                // The promotion precheck route (S10-05): the seeded world
                // holds no release, so own and miss probes share the
                // route's miss shape; concealment of a REAL foreign
                // release's precheck is pinned with live releases by the
                // S10-05 promotion suite.
                surface(
                        "/api/v1/releases/{releaseId}/promotion-precheck",
                        side -> "/api/v1/releases/"
                                + UUID.randomUUID()
                                + "/promotion-precheck?applicationId="
                                + side.applicationId(),
                        404,
                        404,
                        side -> "/api/v1/releases/"
                                + UUID.randomUUID()
                                + "/promotion-precheck?applicationId="
                                + UUID.randomUUID()),
                // The promotions timeline route (S10-05): the same
                // miss-shape rationale — the live concealment proof
                // belongs to the promotion suite.
                surface(
                        "/api/v1/releases/{releaseId}/promotions",
                        side -> "/api/v1/releases/" + UUID.randomUUID() + "/promotions?applicationId="
                                + side.applicationId(),
                        404,
                        404,
                        side -> "/api/v1/releases/" + UUID.randomUUID() + "/promotions?applicationId="
                                + UUID.randomUUID()),

                // Approval workflows (S10-01) ------------------------------------
                // org:approval-workflows:read is organization-grain (the
                // RBAC matrix): the owning ORG_ADMIN lists (an empty page
                // in the seeded world — no definition exists), the tenant
                // persona conceals through the organization-grain
                // permission check.
                surface(
                        "/api/v1/approval-workflows",
                        side -> "/api/v1/approval-workflows?organizationId=" + side.organizationId(),
                        200,
                        404,
                        side -> "/api/v1/approval-workflows?organizationId=" + UUID.randomUUID()),
                // The definition item route (S10-01): the seeded world
                // holds no definition, so own and miss probes share the
                // route's miss shape; concealment of a REAL foreign
                // definition is pinned with live definitions by the
                // S10-01 approval suite.
                surface(
                        "/api/v1/approval-workflows/{workflowId}",
                        side -> "/api/v1/approval-workflows/" + UUID.randomUUID() + "?organizationId="
                                + side.organizationId(),
                        404,
                        404,
                        side -> "/api/v1/approval-workflows/" + UUID.randomUUID() + "?organizationId="
                                + UUID.randomUUID()),

                // Gate definitions (S10-03) ---------------------------------------
                // org:gate-definitions:read is organization-grain (the
                // RBAC matrix): the owning ORG_ADMIN lists (an empty page
                // in the seeded world — no definition exists), the tenant
                // persona conceals through the organization-grain
                // permission check.
                surface(
                        "/api/v1/gate-definitions",
                        side -> "/api/v1/gate-definitions?organizationId=" + side.organizationId(),
                        200,
                        404,
                        side -> "/api/v1/gate-definitions?organizationId=" + UUID.randomUUID()),
                // The gate definition item route (S10-03): the seeded
                // world holds no definition, so own and miss probes share
                // the route's miss shape; concealment of a REAL foreign
                // definition is pinned with live definitions by the
                // S10-03 gate suite.
                surface(
                        "/api/v1/gate-definitions/{definitionId}",
                        side -> "/api/v1/gate-definitions/" + UUID.randomUUID() + "?organizationId="
                                + side.organizationId(),
                        404,
                        404,
                        side -> "/api/v1/gate-definitions/" + UUID.randomUUID() + "?organizationId="
                                + UUID.randomUUID()),
                // The release gates route (S10-03): the seeded world
                // holds no release, so own-side probes address a random
                // release id (the history route's rationale — the miss
                // shape IS the correct own answer), and concealment of a
                // REAL foreign release's gates is pinned with live
                // releases by the S10-03 gate suite.
                surface(
                        "/api/v1/releases/{releaseId}/gates",
                        side -> "/api/v1/releases/" + UUID.randomUUID() + "/gates?applicationId="
                                + side.applicationId(),
                        404,
                        404,
                        side -> "/api/v1/releases/" + UUID.randomUUID() + "/gates?applicationId=" + UUID.randomUUID()),

                // Rings (S10-04) ----------------------------------------------------
                // org:rings:read is organization-grain (the RBAC matrix):
                // the owning ORG_ADMIN lists the effective vocabulary
                // (its own seeded five plus any system defaults it has not
                // redefined), the tenant persona conceals through the
                // organization-grain permission check.
                surface(
                        "/api/v1/rings",
                        side -> "/api/v1/rings?organizationId=" + side.organizationId(),
                        200,
                        404,
                        side -> "/api/v1/rings?organizationId=" + UUID.randomUUID()),
                // The ring item route (S10-04): the seeded world holds no
                // ORG ring (the harness's worlds predate V32 or seed via
                // SQL), so own and miss probes share the route's miss
                // shape unless the org was created through the API;
                // concealment of a REAL foreign ring is pinned with live
                // rings by the S10-04 ring suite.
                surface(
                        "/api/v1/rings/{ringId}",
                        side -> "/api/v1/rings/" + UUID.randomUUID() + "?organizationId=" + side.organizationId(),
                        404,
                        404,
                        side -> "/api/v1/rings/" + UUID.randomUUID() + "?organizationId=" + UUID.randomUUID()),
                // The release eligibility route (S10-04): the seeded world
                // holds no release, so own-side probes address a random
                // release id (the history route's rationale — the miss
                // shape IS the correct own answer), and concealment of a
                // REAL foreign release's eligibility is pinned with live
                // releases by the S10-04 ring suite.
                surface(
                        "/api/v1/releases/{releaseId}/eligibility",
                        side -> "/api/v1/releases/" + UUID.randomUUID() + "/eligibility?applicationId="
                                + side.applicationId(),
                        404,
                        404,
                        side -> "/api/v1/releases/" + UUID.randomUUID() + "/eligibility?applicationId="
                                + UUID.randomUUID()),

                // Release policies (S10-06) -----------------------------------------
                // org:release-policies:read is organization-grain (the
                // RBAC matrix): the owning ORG_ADMIN lists (an empty page
                // in the seeded world — no rule exists), the tenant
                // persona conceals through the organization-grain
                // permission check (the approval-workflows shape).
                surface(
                        "/api/v1/release-policies",
                        side -> "/api/v1/release-policies?organizationId=" + side.organizationId(),
                        200,
                        404,
                        side -> "/api/v1/release-policies?organizationId=" + UUID.randomUUID()),
                // The rule item route (S10-06): the seeded world holds no
                // rule, so own and miss probes share the route's miss
                // shape; concealment of a REAL foreign rule is pinned with
                // live rules by the S10-06 policy suite.
                surface(
                        "/api/v1/release-policies/{ruleId}",
                        side -> "/api/v1/release-policies/" + UUID.randomUUID() + "?organizationId="
                                + side.organizationId(),
                        404,
                        404,
                        side -> "/api/v1/release-policies/" + UUID.randomUUID() + "?organizationId="
                                + UUID.randomUUID()),
                // The skip-allowances route (S10-06): organization-grain
                // like the rule listing; the empty-set answer is the
                // honest own-side standing for a world that never edited
                // the knob.
                surface(
                        "/api/v1/release-policies/skip-allowances",
                        side -> "/api/v1/release-policies/skip-allowances?organizationId=" + side.organizationId(),
                        200,
                        404,
                        side -> "/api/v1/release-policies/skip-allowances?organizationId=" + UUID.randomUUID()),
                // The policy dry-run route (S10-06): the seeded world
                // holds no release, so own and miss probes share the
                // route's miss shape; concealment of a REAL foreign
                // release's dry-run is pinned with live releases by the
                // S10-06 policy suite.
                surface(
                        "/api/v1/releases/{releaseId}/policy-dry-run",
                        side -> "/api/v1/releases/" + UUID.randomUUID() + "/policy-dry-run?applicationId="
                                + side.applicationId(),
                        404,
                        404,
                        side -> "/api/v1/releases/" + UUID.randomUUID() + "/policy-dry-run?applicationId="
                                + UUID.randomUUID()),
                // The policy holds route (S10-06): the same miss-shape
                // rationale — the live concealment proof belongs to the
                // S10-06 policy suite.
                surface(
                        "/api/v1/releases/{releaseId}/policy-holds",
                        side -> "/api/v1/releases/" + UUID.randomUUID() + "/policy-holds?applicationId="
                                + side.applicationId(),
                        404,
                        404,
                        side -> "/api/v1/releases/" + UUID.randomUUID() + "/policy-holds?applicationId="
                                + UUID.randomUUID()),
                // The policy evaluations route (S10-06): the same
                // miss-shape rationale — the live concealment proof
                // belongs to the S10-06 policy suite.
                surface(
                        "/api/v1/releases/{releaseId}/policy-evaluations",
                        side -> "/api/v1/releases/" + UUID.randomUUID() + "/policy-evaluations?applicationId="
                                + side.applicationId(),
                        404,
                        404,
                        side -> "/api/v1/releases/" + UUID.randomUUID() + "/policy-evaluations?applicationId="
                                + UUID.randomUUID()),

                // Feedback (S11-01) -------------------------------------------------
                // app:feedback:read is organization-grain (the RBAC
                // matrix): the seeded world holds no release, so own and
                // miss probes share the route's miss shape (the history
                // route's rationale — the release resolution precedes
                // everything), and concealment of a REAL foreign
                // release's feedback is pinned with live releases by the
                // S11-01 feedback suite.
                surface(
                        "/api/v1/releases/{releaseId}/feedback",
                        side -> "/api/v1/releases/" + UUID.randomUUID() + "/feedback?applicationId="
                                + side.applicationId(),
                        404,
                        404,
                        side -> "/api/v1/releases/" + UUID.randomUUID() + "/feedback?applicationId="
                                + UUID.randomUUID()),
                // The feedback rollup route (S11-02): the same miss-shape
                // rationale; the live concealment (including the
                // SECURITY_ISSUE exclusion from a restricted reader's
                // counts) is pinned with live reports by the S11-02
                // triage suite.
                surface(
                        "/api/v1/releases/{releaseId}/feedback/rollup",
                        side -> "/api/v1/releases/" + UUID.randomUUID() + "/feedback/rollup?applicationId="
                                + side.applicationId(),
                        404,
                        404,
                        side -> "/api/v1/releases/" + UUID.randomUUID() + "/feedback/rollup?applicationId="
                                + UUID.randomUUID()),
                // The report detail route (S11-01): the same miss-shape
                // rationale; the live concealment (including the
                // SECURITY_ISSUE restricted-visibility concealment, AC6)
                // is pinned with live reports by the S11-01 suite.
                surface(
                        "/api/v1/releases/{releaseId}/feedback/{feedbackId}",
                        side -> "/api/v1/releases/" + UUID.randomUUID() + "/feedback/" + UUID.randomUUID()
                                + "?applicationId=" + side.applicationId(),
                        404,
                        404,
                        side -> "/api/v1/releases/" + UUID.randomUUID() + "/feedback/" + UUID.randomUUID()
                                + "?applicationId=" + UUID.randomUUID()),
                // The attachment download route (S11-01, AC3): the same
                // miss-shape rationale; the authorized-roles-only egress
                // is pinned with live attachments by the S11-01 suite.
                surface(
                        "/api/v1/releases/{releaseId}/feedback/{feedbackId}/attachments/{attachmentId}",
                        side -> "/api/v1/releases/" + UUID.randomUUID() + "/feedback/" + UUID.randomUUID()
                                + "/attachments/" + UUID.randomUUID() + "?applicationId=" + side.applicationId(),
                        404,
                        404,
                        side -> "/api/v1/releases/" + UUID.randomUUID() + "/feedback/" + UUID.randomUUID()
                                + "/attachments/" + UUID.randomUUID() + "?applicationId=" + UUID.randomUUID()),

                // Environments (S9-04) ---------------------------------------------
                // org:environments:read is organization-grain (the RBAC
                // matrix): the owning ORG_ADMIN lists the seeded defaults
                // (every organization creation seeds FR-025's five), the
                // tenant persona conceals through the organization-grain
                // permission check.
                surface(
                        "/api/v1/environments",
                        side -> "/api/v1/environments?organizationId=" + side.organizationId(),
                        200,
                        404,
                        side -> "/api/v1/environments?organizationId=" + UUID.randomUUID()),
                // The environment item and child reads: the seeded-world
                // rationale of the release item routes — probes address a
                // random environment id (the route's miss shape IS the
                // correct own answer), and concealment of a REAL foreign
                // environment's config, assignments, ledger, or bindings
                // is pinned with live environments by the S9-04 suite.
                surface(
                        "/api/v1/environments/{environmentId}",
                        side -> "/api/v1/environments/" + UUID.randomUUID(),
                        404,
                        404,
                        side -> "/api/v1/environments/" + UUID.randomUUID()),
                surface(
                        "/api/v1/environments/{environmentId}/config",
                        side -> "/api/v1/environments/" + UUID.randomUUID() + "/config",
                        404,
                        404,
                        side -> "/api/v1/environments/" + UUID.randomUUID() + "/config"),
                surface(
                        "/api/v1/environments/{environmentId}/config/versions",
                        side -> "/api/v1/environments/" + UUID.randomUUID() + "/config/versions",
                        404,
                        404,
                        side -> "/api/v1/environments/" + UUID.randomUUID() + "/config/versions"),
                surface(
                        "/api/v1/environments/{environmentId}/tenants",
                        side -> "/api/v1/environments/" + UUID.randomUUID() + "/tenants",
                        404,
                        404,
                        side -> "/api/v1/environments/" + UUID.randomUUID() + "/tenants"),
                surface(
                        "/api/v1/environments/{environmentId}/credentials",
                        side -> "/api/v1/environments/" + UUID.randomUUID() + "/credentials",
                        404,
                        404,
                        side -> "/api/v1/environments/" + UUID.randomUUID() + "/credentials"),

                // Authorization grants ---------------------------------------------
                surface(
                        "/api/v1/tenants/{id}/authorization-grants",
                        side -> IsolationScenarioHarness.grantsPath(side.tenantId()),
                        200,
                        200,
                        side -> IsolationScenarioHarness.grantsPath(UUID.randomUUID())),
                // The grant-item route: the seeded world holds no grant,
                // so own-side probes address a random grant id (the
                // artifact-entry rationale — the route's miss shape is
                // itself the correct own answer, and the parity proof
                // is the point). Concealment of a REAL foreign grant is
                // pinned with live grants by the S7-02/S7-04 suites.
                surface(
                        "/api/v1/tenants/{id}/authorization-grants/{grantId}",
                        side -> IsolationScenarioHarness.grantsPath(side.tenantId()) + "/" + UUID.randomUUID(),
                        404,
                        404,
                        side -> IsolationScenarioHarness.grantsPath(UUID.randomUUID()) + "/" + UUID.randomUUID()),
                surface(
                        "/api/v1/tenants/{id}/authorization-grants/effective",
                        side -> IsolationScenarioHarness.grantsPath(side.tenantId())
                                + "/effective?testerId="
                                + side.testerId()
                                + "&applicationId="
                                + side.applicationId(),
                        200,
                        200,
                        side -> IsolationScenarioHarness.grantsPath(UUID.randomUUID())
                                + "/effective?testerId="
                                + UUID.randomUUID()
                                + "&applicationId="
                                + UUID.randomUUID()),
                surface(
                        "/api/v1/tenants/{id}/authorization-grants/revocation-report",
                        side -> IsolationScenarioHarness.grantsPath(side.tenantId())
                                + "/revocation-report?testerId="
                                + side.testerId(),
                        200,
                        200,
                        side -> IsolationScenarioHarness.grantsPath(UUID.randomUUID())
                                + "/revocation-report?testerId="
                                + UUID.randomUUID()),

                // The decision API (portal grade, self-scoped) ---------------------
                surface(
                        "/api/v1/authorization/decisions/download",
                        side -> IsolationScenarioHarness.downloadUri(side.tenantId(), side.versionId()),
                        404,
                        404,
                        side -> IsolationScenarioHarness.downloadUri(side.tenantId(), UUID.randomUUID())),
                surface(
                        "/api/v1/authorization/decisions/eligibility",
                        side -> IsolationScenarioHarness.eligibilityUri(side.tenantId(), side.applicationId()),
                        200,
                        200,
                        side -> IsolationScenarioHarness.eligibilityUri(UUID.randomUUID(), UUID.randomUUID())),
                // The portal catalog (S8-02): param-less and self-scoped,
                // so "foreign" and "miss" are both the caller's OWN
                // catalog — the concealment proof here is that the body
                // answers the caller and only the caller (byte-stable,
                // no volatile fields, no foreign identifiers), never a
                // leak of the addressed side. A persona without a linked
                // tester record answers the honest empty 200.
                new Entry() {
                    @Override
                    public String method() {
                        return "GET";
                    }

                    @Override
                    public String template() {
                        return "/api/v1/authorization/decisions/applications";
                    }

                    @Override
                    public String uri(IsolationScenarioHarness.IsolatedTenant side) {
                        return "/api/v1/authorization/decisions/applications";
                    }

                    @Override
                    public int ownOrgAdmin() {
                        return 200;
                    }

                    @Override
                    public int ownTenantAdmin() {
                        return 200;
                    }

                    @Override
                    public int concealStatus() {
                        return 200;
                    }

                    @Override
                    public String missUri(IsolationScenarioHarness.IsolatedTenant actorSide) {
                        return "/api/v1/authorization/decisions/applications";
                    }
                },

                // The audit query (platform grain) ----------------------------------
                new Entry() {
                    @Override
                    public String method() {
                        return "GET";
                    }

                    @Override
                    public String template() {
                        return "/api/v1/audit";
                    }

                    @Override
                    public String uri(IsolationScenarioHarness.IsolatedTenant side) {
                        return "/api/v1/audit?tenant=" + side.tenantId();
                    }

                    @Override
                    public int ownOrgAdmin() {
                        // A target-less platform-scope permission: no
                        // resource id to conceal at, so the denial is
                        // the opaque 403 — foreign and miss alike.
                        return 403;
                    }

                    @Override
                    public int ownTenantAdmin() {
                        return 403;
                    }

                    @Override
                    public int concealStatus() {
                        return 403;
                    }

                    @Override
                    public String missUri(IsolationScenarioHarness.IsolatedTenant actorSide) {
                        return "/api/v1/audit?tenant=" + UUID.randomUUID();
                    }
                },

                // Distribution (S13-01, org grain) ---------------------------------
                // The adapter framework's reads are organization-addressed
                // configuration surfaces (the environments standing): the owning
                // ORG_ADMIN reads them, a tenant admin of the owning organization
                // holds no org:distribution:read and conceals at 404, and a
                // foreign attempt conceals identically. The channels catalog is
                // the same org-addressed shape (it asks for the organization the
                // console renders); the records read answers the honest EMPTY
                // page for a release the seeded world never created -- the
                // leak-proofing is the org guard itself.
                surface(
                        "/api/v1/distribution/channels",
                        side -> "/api/v1/distribution/channels?organizationId=" + side.organizationId(),
                        200,
                        404,
                        side -> "/api/v1/distribution/channels?organizationId=" + UUID.randomUUID()),
                surface(
                        "/api/v1/distribution/credentials",
                        side -> "/api/v1/distribution/credentials?organizationId=" + side.organizationId(),
                        200,
                        404,
                        side -> "/api/v1/distribution/credentials?organizationId=" + UUID.randomUUID()),
                surface(
                        "/api/v1/distribution/credentials/effective",
                        side -> "/api/v1/distribution/credentials/effective?organizationId=" + side.organizationId(),
                        200,
                        404,
                        side -> "/api/v1/distribution/credentials/effective?organizationId=" + UUID.randomUUID()),
                surface(
                        "/api/v1/distribution/records",
                        side -> "/api/v1/distribution/records?organizationId=" + side.organizationId() + "&releaseId="
                                + UUID.randomUUID(),
                        200,
                        404,
                        side -> "/api/v1/distribution/records?organizationId=" + UUID.randomUUID() + "&releaseId="
                                + UUID.randomUUID()));
    }

    /**
     * Spec GET routes the completeness guard excludes: self surfaces
     * (authentication tokens, the caller's own profile), the bare
     * organization collection (a platform-registry listing carrying no
     * tenant data), and the OpenAPI document itself. Every other GET
     * route the served spec declares must appear in the catalog — a
     * new read endpoint fails the guard until it joins the matrix.
     */
    static List<String> allowlistedGetRoutes() {
        return List.of("GET /api/v1/auth/me", "GET /api/v1/auth/tokens", "GET /api/v1/organizations");
    }

    private IsolationEndpointRegistry() {}
}
