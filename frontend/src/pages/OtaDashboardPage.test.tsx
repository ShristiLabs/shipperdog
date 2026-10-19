/** OTA dashboard behavior (S16-15, issue #177): the tenant's alert
 * badges and firings feed, the per-bundle device funnel with the
 * apply-success rate and failure-reason breakdown, the adoption
 * curve, and the crash-rate comparison with its delta — the honest
 * empty states when a bundle has no traffic yet. */
import { cleanup, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { Route, Routes } from "react-router-dom";
import OtaDashboardPage, { adoptionChartRows } from "./OtaDashboardPage";
import { installFetchRouter, renderConsole, seedSession } from "../test/harness";

const ORG_ID = "10000000-0000-4000-8000-000000000001";
const TENANT_ACME = "20000000-0000-4000-8000-000000000001";
const APPLICATION_ID = "30000000-0000-4000-8000-000000000001";
const RELEASE_ID = "50000000-0000-4000-8000-000000000001";
const BUNDLE_ID = "60000000-0000-4000-8000-000000000001";
const CURRENT_BUNDLE_ID = "60000000-0000-4000-8000-000000000002";
const FIRING_ID = "90000000-0000-4000-8000-000000000001";

const FUNNEL = {
  bundleId: BUNDLE_ID,
  bundleVersion: "2.4.1",
  applicationId: APPLICATION_ID,
  releaseId: RELEASE_ID,
  releaseRef: "R-101",
  stages: { checks: 200, seen: 150, downloads: 100, applied: 90, failed: 8, rolledBack: 2 },
  failures: [
    { reason: "checksum_mismatch", count: 5 },
    { reason: "signature_invalid", count: 3 },
  ],
  adoption: [
    { day: "2026-10-15", seenDevices: 100, appliedDevices: 60, adoptionRate: 0.6 },
    { day: "2026-10-16", seenDevices: 150, appliedDevices: 120, adoptionRate: 0.8 },
  ],
};

const HEALTH = {
  newBundle: {
    bundleId: BUNDLE_ID,
    bundleVersion: "2.4.1",
    releaseId: RELEASE_ID,
    releaseRef: "R-101",
    sessions: 1_000,
    crashSessions: 4,
    crashFreeRate: 0.996,
  },
  currentBundle: {
    bundleId: CURRENT_BUNDLE_ID,
    bundleVersion: "2.3.9",
    releaseId: "50000000-0000-4000-8000-000000000009",
    releaseRef: "R-99",
    sessions: 5_000,
    crashSessions: 15,
    crashFreeRate: 0.997,
  },
  crashFreeRateDelta: -0.001,
};

const ALERTS = {
  tenantId: TENANT_ACME,
  generatedAt: "2026-10-16T12:00:00Z",
  counts: { critical: 1, warning: 2, info: 0 },
  standing: [
    {
      id: FIRING_ID,
      ruleId: "80000000-0000-4000-8000-000000000001",
      applicationId: APPLICATION_ID,
      otaBundleId: BUNDLE_ID,
      metric: "OTA_APPLY_FAILURE_RATE",
      comparator: "GTE",
      threshold: 0.05,
      severity: "CRITICAL",
      observedValue: 0.081,
      detail: "8.1% of applies failed in the window",
      firedAt: "2026-10-16T11:55:00Z",
      resolvedAt: null,
    },
  ],
  recent: [],
};

function dashboardRoutes() {
  return [
    {
      method: "GET",
      url: "/api/v1/tenants",
      status: 200,
      body: {
        items: [
          {
            id: TENANT_ACME,
            organizationId: ORG_ID,
            name: "Acme Bank",
            externalId: "crm-acme",
            status: "ACTIVE",
            createdAt: "2026-10-01T10:00:00Z",
            updatedAt: "2026-10-01T10:00:00Z",
          },
        ],
      },
    },
    {
      method: "GET",
      url: `/api/v1/tenants/${TENANT_ACME}/applications`,
      status: 200,
      body: {
        items: [
          {
            id: APPLICATION_ID,
            name: "Banking",
            description: null,
            platforms: ["IOS"],
            bundleId: "com.acme.banking",
            packageName: null,
            status: "ACTIVE",
            createdAt: "2026-10-01T10:00:00Z",
            updatedAt: "2026-10-01T10:00:00Z",
          },
        ],
      },
    },
    {
      method: "GET",
      url: "/api/v1/ota/bundles",
      status: 200,
      body: {
        items: [
          {
            id: BUNDLE_ID,
            applicationId: APPLICATION_ID,
            versionId: "40000000-0000-4000-8000-000000000001",
            artifactId: "65000000-0000-4000-8000-000000000001",
            releaseId: RELEASE_ID,
            bundleVersion: "2.4.1",
            platformTag: "js-bundle",
            sha256: "b1a1",
            sizeBytes: 1024,
            signature: "sig",
            keyId: "k1k1",
            forced: false,
            minAppVersion: null,
            status: "RELEASED",
            createdBy: "10000000-0000-4000-8000-000000000010",
            createdAt: "2026-10-10T10:00:00Z",
            updatedAt: "2026-10-10T10:05:00Z",
          },
        ],
      },
    },
    {
      method: "GET",
      url: `/api/v1/tenants/${TENANT_ACME}/dashboard/ota/alerts`,
      status: 200,
      body: ALERTS,
    },
    {
      method: "GET",
      url: `/api/v1/tenants/${TENANT_ACME}/dashboard/ota/bundles/${BUNDLE_ID}/funnel`,
      status: 200,
      body: FUNNEL,
    },
    {
      method: "GET",
      url: `/api/v1/tenants/${TENANT_ACME}/dashboard/ota/bundles/${BUNDLE_ID}/health`,
      status: 200,
      body: HEALTH,
    },
  ];
}

function renderDashboard(routes: Parameters<typeof installFetchRouter>[0], route = "/ota/dashboard") {
  installFetchRouter(routes);
  seedSession();
  renderConsole(
    <Routes>
      <Route path="/ota/dashboard/:tenantId?" element={<OtaDashboardPage />} />
    </Routes>,
    { route },
  );
}

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe("adoptionChartRows", () => {
  it("maps the server fractions to chart percentages", () => {
    expect(adoptionChartRows(FUNNEL.adoption)).toEqual([
      { day: "2026-10-15", rate: 60 },
      { day: "2026-10-16", rate: 80 },
    ]);
  });
});

describe("OtaDashboardPage", () => {
  it("renders the alert badges and the standing firing", async () => {
    renderDashboard(dashboardRoutes());

    expect(await screen.findByTestId("ota-dashboard-title")).toHaveTextContent("Acme Bank");
    expect(await screen.findByTestId("ota-alerts-critical")).toHaveTextContent("1 critical");
    expect(screen.getByTestId("ota-alerts-warning")).toHaveTextContent("2 warning");
    expect(screen.getByTestId("ota-alerts-info")).toHaveTextContent("0 info");
    expect(
      screen.getByTestId(`ota-firing-standing-${FIRING_ID}`),
    ).toBeInTheDocument();
    expect(screen.getByText(/observed 0.081/)).toBeInTheDocument();
  });

  it("renders the funnel stages, success rate, failure exits, and failure reasons", async () => {
    renderDashboard(dashboardRoutes());

    expect(await screen.findByTestId("ota-funnel-section")).toBeInTheDocument();
    // The funnel's data lands asynchronously after the section shell.
    expect(await screen.findByTestId("ota-funnel-stage-checks")).toHaveTextContent("200");
    expect(screen.getByText("R-101")).toBeInTheDocument();
    expect(screen.getByTestId("ota-success-rate")).toHaveTextContent("apply success 91.84%");
    expect(screen.getByTestId("ota-funnel-stage-devices-seen")).toHaveTextContent("150 (75%)");
    expect(screen.getByTestId("ota-funnel-stage-downloads")).toHaveTextContent("100 (50%)");
    expect(screen.getByTestId("ota-funnel-stage-applied")).toHaveTextContent("90 (45%)");
    expect(screen.getByTestId("ota-funnel-failed")).toHaveTextContent("failed 8");
    expect(screen.getByTestId("ota-funnel-rolled-back")).toHaveTextContent("rolled back 2");
    expect(screen.getByTestId("ota-failures-chart")).toBeInTheDocument();
  });

  it("renders the adoption curve and the crash-rate comparison with the delta", async () => {
    renderDashboard(dashboardRoutes());

    expect(await screen.findByTestId("ota-adoption-chart")).toBeInTheDocument();
    expect(await screen.findByTestId("ota-health-section")).toBeInTheDocument();
    expect(screen.getByText("New bundle: 2.4.1 (R-101)")).toBeInTheDocument();
    expect(screen.getByText("Current released: 2.3.9 (R-99)")).toBeInTheDocument();
    expect(screen.getAllByTestId("ota-health-rate")[0]).toHaveTextContent("99.60%");
    expect(screen.getAllByTestId("ota-health-rate")[1]).toHaveTextContent("99.70%");
    expect(screen.getByTestId("ota-health-delta")).toHaveTextContent("-0.1 pp");
  });

  it("renders the honest empty states when the bundle has no traffic", async () => {
    const routes = dashboardRoutes().map((route) =>
      route.url.endsWith(`/bundles/${BUNDLE_ID}/funnel`) || route.url.endsWith(`/bundles/${BUNDLE_ID}/health`)
        ? { ...route, status: 404, body: { title: "Not Found", status: 404, code: "NOT_FOUND" } }
        : route,
    );
    renderDashboard(routes);

    expect(await screen.findByText(/No funnel recorded for this bundle yet/)).toBeInTheDocument();
    expect(screen.getByText(/No adoption curve for this bundle yet/)).toBeInTheDocument();
    expect(screen.getByText(/No health statement for this bundle yet/)).toBeInTheDocument();
  });
});
