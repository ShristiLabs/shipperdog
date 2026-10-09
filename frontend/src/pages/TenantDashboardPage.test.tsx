/** Tenant dashboard behavior (issue #50, S11-05): the tenant block's
 * counts, the pending releases deep-linking to their release screens,
 * and the downloads trend folded with the counts-sum rule over the
 * served per-(bucket, release, ring) points. */
import { cleanup, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it } from "vitest";
import { Route, Routes } from "react-router-dom";
import TenantDashboardPage, { downloadsChartRows } from "./TenantDashboardPage";
import type { MetricPoint } from "../api/analytics";
import { installFetchRouter, renderConsole, seedSession, type Route as FetchRoute } from "../test/harness";

const user = userEvent.setup();

const TENANT_ID = "20000000-0000-4000-8000-000000000009";
const APP_ID = "30000000-0000-4000-8000-000000000001";
const RELEASE_ID = "40000000-0000-4000-8000-000000000001";

const TENANT = {
  id: TENANT_ID,
  organizationId: "10000000-0000-4000-8000-000000000001",
  name: "Acme Bank",
  externalId: "crm-acme",
  status: "ACTIVE",
  createdAt: "2026-10-01T10:00:00Z",
  updatedAt: "2026-10-01T10:00:00Z",
};

const DASHBOARD = {
  tenantId: TENANT_ID,
  generatedAt: "2026-10-04T12:00:00Z",
  counts: { applications: 4, availableVersions: 17, activeTesters: 82, pendingReleases: 2 },
  pendingReleases: [
    {
      releaseId: RELEASE_ID,
      releaseRef: "R-1028",
      applicationId: APP_ID,
      applicationName: "Banking",
      status: "DEPLOYING",
      ring: "testing",
    },
  ],
};

function routes(seriesItems: MetricPoint[] = [], dashboard = DASHBOARD): FetchRoute[] {
  return [
    { method: "GET", url: `/api/v1/tenants/${TENANT_ID}`, status: 200, body: TENANT },
    { method: "GET", url: `/api/v1/tenants/${TENANT_ID}/dashboard`, status: 200, body: dashboard },
    { method: "GET", url: `/api/v1/tenants/${TENANT_ID}/metrics`, status: 200, body: { items: seriesItems } },
  ];
}

afterEach(cleanup);

describe("downloadsChartRows (the folding rule at the chart boundary)", () => {
  it("sums the per-(release, ring) download points per day", () => {
    const rows = downloadsChartRows([
      { bucketStart: "2026-09-30T00:00:00Z", value: 4, releaseRef: "R-1", ringCode: "qa" },
      { bucketStart: "2026-09-30T00:00:00Z", value: 2, releaseRef: "R-1", ringCode: "production" },
      { bucketStart: "2026-10-01T00:00:00Z", value: 6, releaseRef: "R-2", ringCode: "qa" },
    ]);
    expect(rows).toEqual([
      { day: "2026-09-30", downloads: 6 },
      { day: "2026-10-01", downloads: 6 },
    ]);
  });
});

describe("TenantDashboardPage", () => {
  it("renders the four counts and the pending release with its ring", async () => {
    installFetchRouter(routes());
    seedSession();
    renderConsole(
      <Routes>
        <Route path="/tenants/:tenantId/dashboard" element={<TenantDashboardPage />} />
      </Routes>,
      { route: `/tenants/${TENANT_ID}/dashboard` },
    );

    expect(await screen.findByTestId("tenant-count-applications")).toBeTruthy();
    expect(screen.getByTestId("tenant-count-applications").textContent).toContain("4");
    expect(screen.getByTestId("tenant-count-versions").textContent).toContain("17");
    expect(screen.getByTestId("tenant-count-testers").textContent).toContain("82");
    expect(screen.getByTestId("tenant-count-pending").textContent).toContain("2");
    expect(screen.getByTestId(`tenant-pending-${RELEASE_ID}`).textContent).toContain("R-1028");
    expect(screen.getByTestId(`tenant-pending-${RELEASE_ID}`).textContent).toContain("DEPLOYING");
  });

  it("renders the folded downloads chart when the series carries points", async () => {
    installFetchRouter(
      routes([
        { bucketStart: "2026-10-01T00:00:00Z", value: 3, releaseRef: "R-1", ringCode: "qa" },
        { bucketStart: "2026-10-01T00:00:00Z", value: 4, releaseRef: "R-2", ringCode: "qa" },
      ]),
    );
    seedSession();
    renderConsole(
      <Routes>
        <Route path="/tenants/:tenantId/dashboard" element={<TenantDashboardPage />} />
      </Routes>,
      { route: `/tenants/${TENANT_ID}/dashboard` },
    );

    expect(await screen.findByTestId("downloads-chart")).toBeTruthy();
  });

  it("renders the honest empty states when nothing is in flight or recorded", async () => {
    installFetchRouter(
      routes([], { ...DASHBOARD, counts: { applications: 0, availableVersions: 0, activeTesters: 0, pendingReleases: 0 }, pendingReleases: [] }),
    );
    seedSession();
    renderConsole(
      <Routes>
        <Route path="/tenants/:tenantId/dashboard" element={<TenantDashboardPage />} />
      </Routes>,
      { route: `/tenants/${TENANT_ID}/dashboard` },
    );

    expect(await screen.findByTestId("tenant-pending-empty")).toBeTruthy();
    expect(screen.getByTestId("downloads-empty")).toBeTruthy();
  });

  it("deep-links a pending release to its release screen", async () => {
    installFetchRouter(routes());
    seedSession();
    renderConsole(
      <Routes>
        <Route path="/tenants/:tenantId/dashboard" element={<TenantDashboardPage />} />
        <Route path="/releases/:applicationId/:releaseId" element={<div>RELEASE DASHBOARD SCREEN</div>} />
      </Routes>,
      { route: `/tenants/${TENANT_ID}/dashboard` },
    );

    await user.click(await screen.findByTestId(`tenant-pending-${RELEASE_ID}`));
    expect(await screen.findByText("RELEASE DASHBOARD SCREEN")).toBeTruthy();
  });

  it("shows the error state when the read fails", async () => {
    installFetchRouter([
      { method: "GET", url: `/api/v1/tenants/${TENANT_ID}`, status: 200, body: TENANT },
      {
        method: "GET",
        url: `/api/v1/tenants/${TENANT_ID}/dashboard`,
        status: 500,
        body: { title: "Boom" },
      },
    ]);
    seedSession();
    renderConsole(
      <Routes>
        <Route path="/tenants/:tenantId/dashboard" element={<TenantDashboardPage />} />
      </Routes>,
      { route: `/tenants/${TENANT_ID}/dashboard` },
    );

    expect(await screen.findByTestId("tenant-dashboard-error")).toBeTruthy();
  });
});
