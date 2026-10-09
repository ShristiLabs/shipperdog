/** Role-aware navigation (issue #12, PRD §26): sections render only
 * when the probed endpoint permits the caller, and org-less users get
 * the notice instead of unusable sections. */
import { cleanup, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import ConsoleLayout from "./ConsoleLayout";
import { installFetchRouter, renderConsole, seedSession } from "../test/harness";

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe("ConsoleLayout role-aware navigation", () => {
  it("shows the Dashboard section when the landing read probe answers 200 (S11-05)", async () => {
    installFetchRouter([
      {
        method: "GET",
        url: "/api/v1/organizations/10000000-0000-4000-8000-000000000001/dashboard",
        status: 200,
        body: {
          organizationId: "10000000-0000-4000-8000-000000000001",
          generatedAt: "2026-10-04T12:00:00Z",
          counts: {
            applications: 0,
            tenants: 0,
            activeReleases: 0,
            pendingApprovals: 0,
            testing: 0,
            production: 0,
          },
          applications: [],
          pendingActions: { approvals: [], reviews: [], failedGates: [] },
        },
      },
    ]);
    seedSession();
    renderConsole(<ConsoleLayout />, { route: "/" });

    expect(await screen.findByTestId("nav-dashboard")).toBeInTheDocument();
  });

  it("hides the Dashboard section when the probe is conceal-denied (the developer persona)", async () => {
    installFetchRouter([]);
    seedSession();
    renderConsole(<ConsoleLayout />, { route: "/" });

    await screen.findByTestId("nav-tenants").catch(() => {});
    expect(screen.queryByTestId("nav-dashboard")).not.toBeInTheDocument();
  });

  it("shows the Tenants section when the probe endpoint answers 200", async () => {
    installFetchRouter([
      {
        method: "GET",
        url: "/api/v1/tenants",
        status: 200,
        body: { items: [] },
      },
    ]);
    seedSession();
    renderConsole(<ConsoleLayout />, { route: "/" });

    expect(await screen.findByTestId("nav-tenants")).toBeInTheDocument();
    expect(screen.getByTestId("console-user")).toHaveTextContent("dev@shipperdog.local");
  });

  it("shows the Applications section when the registry listing probe answers 200", async () => {
    installFetchRouter([
      {
        method: "GET",
        url: "/api/v1/applications",
        status: 200,
        body: { items: [] },
      },
    ]);
    seedSession();
    renderConsole(<ConsoleLayout />, { route: "/" });

    expect(await screen.findByTestId("nav-applications")).toBeInTheDocument();
  });

  it("shows the Releases section under the applications probe (S9-05)", async () => {
    installFetchRouter([
      {
        method: "GET",
        url: "/api/v1/applications",
        status: 200,
        body: { items: [] },
      },
    ]);
    seedSession();
    renderConsole(<ConsoleLayout />, { route: "/" });

    expect(await screen.findByTestId("nav-releases")).toBeInTheDocument();
  });

  it("hides the Applications section when the probe is conceal-denied", async () => {
    installFetchRouter([
      {
        method: "GET",
        url: "/api/v1/tenants",
        status: 200,
        body: { items: [] },
      },
      {
        method: "GET",
        url: "/api/v1/applications",
        status: 404,
        body: {
          title: "Not Found",
          status: 404,
          code: "ORGANIZATION_NOT_FOUND",
          detail: "Resource not found.",
        },
      },
    ]);
    seedSession();
    renderConsole(<ConsoleLayout />, { route: "/" });

    await screen.findByTestId("nav-tenants");
    expect(screen.queryByTestId("nav-applications")).not.toBeInTheDocument();
    expect(screen.queryByTestId("nav-releases")).not.toBeInTheDocument();
  });

  it("hides the Tenants section when the probe is denied (concealed 404)", async () => {
    installFetchRouter([
      {
        method: "GET",
        url: "/api/v1/tenants",
        status: 404,
        body: {
          title: "Not Found",
          status: 404,
          code: "ORGANIZATION_NOT_FOUND",
          detail: "Resource not found.",
        },
      },
    ]);
    seedSession();
    renderConsole(<ConsoleLayout />, { route: "/" });

    expect(await screen.findByText(/do not have permission/i)).toBeInTheDocument();
    expect(screen.queryByTestId("nav-tenants")).not.toBeInTheDocument();
  });

  it("shows the organization notice for users without an organization", async () => {
    installFetchRouter([]);
    seedSession({ orgId: null });
    renderConsole(<ConsoleLayout />, { route: "/" });

    expect(await screen.findByText(/no active organization membership/i)).toBeInTheDocument();
    expect(screen.queryByTestId("nav-tenants")).not.toBeInTheDocument();
  });
});
