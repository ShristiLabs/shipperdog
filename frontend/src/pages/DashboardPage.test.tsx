/** Organization dashboard behavior (issue #50, S11-05): the §29 blocks
 * rendered from one live read, the health rows and pending actions
 * deep-linking to the release screens they act on, and the honest
 * loading / denied / error / empty states. */
import { cleanup, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it } from "vitest";
import { Route, Routes } from "react-router-dom";
import DashboardPage from "./DashboardPage";
import { installFetchRouter, renderConsole, seedSession, type Route as FetchRoute } from "../test/harness";

const user = userEvent.setup();

const ORG_ID = "10000000-0000-4000-8000-000000000001";
const APP_A = "20000000-0000-4000-8000-000000000001";
const APP_B = "20000000-0000-4000-8000-000000000002";
const RELEASE_1 = "30000000-0000-4000-8000-000000000001";

const DASHBOARD = {
  organizationId: ORG_ID,
  generatedAt: "2026-10-04T12:00:00Z",
  counts: {
    applications: 2,
    tenants: 5,
    activeReleases: 3,
    pendingApprovals: 1,
    testing: 2,
    production: 1,
  },
  applications: [
    {
      applicationId: APP_A,
      name: "Banking",
      health: "TESTING",
      activeReleases: 2,
      testing: 2,
      production: 0,
      blockedFindings: 0,
    },
    {
      applicationId: APP_B,
      name: "Loans",
      health: "BLOCKED",
      activeReleases: 1,
      testing: 0,
      production: 0,
      blockedFindings: 1,
    },
  ],
  pendingActions: {
    approvals: [
      {
        releaseId: RELEASE_1,
        releaseRef: "R-1028",
        applicationId: APP_A,
        applicationName: "Banking",
        approvalType: "QA",
        roundNumber: 1,
        scopeTenant: null,
        decidableByCaller: true,
      },
    ],
    reviews: [
      {
        releaseId: RELEASE_1,
        releaseRef: "R-1028",
        applicationId: APP_A,
        applicationName: "Banking",
        approvalType: "SECURITY",
        roundNumber: 1,
        scopeTenant: null,
        decidableByCaller: false,
      },
    ],
    failedGates: [
      {
        releaseId: RELEASE_1,
        releaseRef: "R-1028",
        applicationId: APP_A,
        applicationName: "Banking",
        gateType: "AUTOMATED_TESTS",
      },
    ],
  },
};

function dashboardRoutes(body: unknown = DASHBOARD, status = 200): FetchRoute[] {
  return [
    {
      method: "GET",
      url: `/api/v1/organizations/${ORG_ID}/dashboard`,
      status,
      body,
    },
  ];
}

afterEach(cleanup);

describe("DashboardPage", () => {
  it("renders the six counts, the health rows, and the pending actions", async () => {
    installFetchRouter(dashboardRoutes());
    seedSession();
    renderConsole(
      <Routes>
        <Route path="/dashboard" element={<DashboardPage />} />
        <Route path="/releases/:applicationId" element={<div>RELEASES SCREEN</div>} />
      </Routes>,
      { route: "/dashboard" },
    );

    expect(await screen.findByTestId("count-applications")).toBeTruthy();
    expect(screen.getByTestId("count-applications").textContent).toContain("2");
    expect(screen.getByTestId("count-tenants").textContent).toContain("5");
    expect(screen.getByTestId("count-active-releases").textContent).toContain("3");
    expect(screen.getByTestId("count-pending-approvals").textContent).toContain("1");
    expect(screen.getByTestId("count-testing").textContent).toContain("2");
    expect(screen.getByTestId("count-production").textContent).toContain("1");

    expect(screen.getByText("Banking")).toBeTruthy();
    expect(screen.getByText("Loans")).toBeTruthy();
    expect(screen.getAllByText("Testing").length).toBeGreaterThan(0);
    expect(screen.getAllByText("Blocked").length).toBeGreaterThan(0);
    expect(screen.getByText("1 step waiting on you.")).toBeTruthy();
    expect(screen.getByTestId(`pending-approval-${RELEASE_1}-QA`)).toBeTruthy();
    expect(screen.getByText(/security review pending/i)).toBeTruthy();
    expect(screen.getByTestId(`failed-gate-${RELEASE_1}-AUTOMATED_TESTS`)).toBeTruthy();
  });

  it("deep-links a pending approval to its release dashboard (AC3)", async () => {
    installFetchRouter(dashboardRoutes());
    seedSession();
    renderConsole(
      <Routes>
        <Route path="/dashboard" element={<DashboardPage />} />
        <Route
          path="/releases/:applicationId/:releaseId"
          element={<div>RELEASE DASHBOARD SCREEN</div>}
        />
      </Routes>,
      { route: "/dashboard" },
    );

    await user.click(await screen.findByTestId(`pending-approval-${RELEASE_1}-QA`));
    await waitFor(() =>
      expect(window.location.pathname + document.textContent).toBeTruthy(),
    );
    expect(screen.getByText("RELEASE DASHBOARD SCREEN")).toBeTruthy();
  });

  it("deep-links a health row to the application's releases", async () => {
    installFetchRouter(dashboardRoutes());
    seedSession();
    renderConsole(
      <Routes>
        <Route path="/dashboard" element={<DashboardPage />} />
        <Route path="/releases/:applicationId" element={<div>RELEASES SCREEN</div>} />
      </Routes>,
      { route: "/dashboard" },
    );

    await user.click(await screen.findByTestId(`health-row-${APP_A}`));
    expect(await screen.findByText("RELEASES SCREEN")).toBeTruthy();
  });

  it("renders the honest empty state when nothing is in flight", async () => {
    installFetchRouter(
      dashboardRoutes({
        ...DASHBOARD,
        counts: {
          applications: 1,
          tenants: 1,
          activeReleases: 0,
          pendingApprovals: 0,
          testing: 0,
          production: 0,
        },
        applications: [],
        pendingActions: { approvals: [], reviews: [], failedGates: [] },
      }),
    );
    seedSession();
    renderConsole(
      <Routes>
        <Route path="/dashboard" element={<DashboardPage />} />
      </Routes>,
      { route: "/dashboard" },
    );

    expect(await screen.findByTestId("health-empty")).toBeTruthy();
    expect(screen.getByText("Nothing needs attention.")).toBeTruthy();
  });

  it("shows the denied notice for actors the probe would hide (the server concealed)", async () => {
    installFetchRouter(dashboardRoutes({ title: "Not Found", status: 404 }, 404));
    seedSession();
    renderConsole(
      <Routes>
        <Route path="/dashboard" element={<DashboardPage />} />
      </Routes>,
      { route: "/dashboard" },
    );

    expect(await screen.findByTestId("dashboard-error")).toBeTruthy();
    expect(screen.getByText(/does not include the organization dashboard/i)).toBeTruthy();
  });

  it("shows the error state with a retry on a real failure", async () => {
    installFetchRouter(dashboardRoutes({ title: "Boom", status: 500 }, 500));
    seedSession();
    renderConsole(
      <Routes>
        <Route path="/dashboard" element={<DashboardPage />} />
      </Routes>,
      { route: "/dashboard" },
    );

    expect(await screen.findByTestId("dashboard-error")).toBeTruthy();
    expect(screen.getByText(/could not be loaded/i)).toBeTruthy();
    expect(screen.getByRole("button", { name: /retry/i })).toBeTruthy();
  });

  it("renders the no-organization notice for org-less accounts", async () => {
    installFetchRouter([]);
    seedSession({ orgId: null });
    renderConsole(
      <Routes>
        <Route path="/dashboard" element={<DashboardPage />} />
      </Routes>,
      { route: "/dashboard" },
    );

    expect(await screen.findByText(/no active organization membership/i)).toBeTruthy();
  });
});
