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
                });
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
