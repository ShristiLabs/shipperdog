/** Application list screen behavior (issue #16): the organization-wide
 * administration listing (both scopes, unauthorized shared apps
 * included), the per-tenant effective-visibility mode, search over
 * identifiers, and the platform-coupled create dialog. */
import { cleanup, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it } from "vitest";
import { Route, Routes } from "react-router-dom";
import ApplicationsPage from "./ApplicationsPage";
import { installFetchRouter, renderConsole, seedSession } from "../test/harness";

const user = userEvent.setup();

const ORG_ID = "10000000-0000-4000-8000-000000000001";
const TENANT_ACME = "20000000-0000-4000-8000-000000000001";
const TENANT_CONTOSO = "20000000-0000-4000-8000-000000000002";

const TENANT_OWNED_APP = {
  id: "30000000-0000-4000-8000-000000000001",
  organizationId: ORG_ID,
  tenantId: TENANT_ACME,
  ownershipScope: "TENANT",
  name: "Banking Mobile",
  description: null,
  platforms: ["IOS"],
  bundleId: "com.acme.banking",
  packageName: null,
  status: "ACTIVE",
  createdAt: "2026-10-01T10:00:00Z",
  updatedAt: "2026-10-02T10:00:00Z",
};
const SHARED_APP = {
  id: "30000000-0000-4000-8000-000000000002",
  organizationId: ORG_ID,
  tenantId: null,
  ownershipScope: "ORGANIZATION",
  name: "Field Service",
  description: null,
  platforms: ["IOS", "ANDROID"],
  bundleId: "com.acme.fieldservice",
  packageName: "com.acme.fieldservice.android",
  status: "ACTIVE",
  createdAt: "2026-10-01T10:00:00Z",
  updatedAt: "2026-10-02T10:00:00Z",
};

function directoryRoutes(): Parameters<typeof installFetchRouter>[0] {
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
          {
            id: TENANT_CONTOSO,
            organizationId: ORG_ID,
            name: "Contoso",
            externalId: "crm-contoso",
            status: "ACTIVE",
            createdAt: "2026-10-01T10:00:00Z",
            updatedAt: "2026-10-01T10:00:00Z",
          },
        ],
      },
    },
  ];
}

function orgListingRoutes(): Parameters<typeof installFetchRouter>[0] {
  return [
    ...directoryRoutes(),
    {
      method: "GET",
      url: "/api/v1/applications",
      status: 200,
      body: { items: [TENANT_OWNED_APP, SHARED_APP] },
    },
  ];
}

function fetchCalls(): Array<[string, RequestInit | undefined]> {
  const mock = fetch as unknown as { mock: { calls: Array<[string, RequestInit]> } };
  return mock.mock.calls;
}

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe("ApplicationsPage", () => {
  it("lists the organization-wide registry with both scopes, shared app included", async () => {
    installFetchRouter(orgListingRoutes());
    seedSession();
    renderConsole(
      <Routes>
        <Route path="/applications" element={<ApplicationsPage />} />
      </Routes>,
      { route: "/applications" },
    );

    expect(await screen.findByRole("gridcell", { name: "Banking Mobile" })).toBeInTheDocument();
    expect(screen.getByRole("gridcell", { name: "com.acme.banking" })).toBeInTheDocument();
    expect(screen.getByText("Tenant")).toBeInTheDocument();
    expect(screen.getByText("Shared")).toBeInTheDocument();
    expect(screen.getAllByText("Active").length).toBeGreaterThanOrEqual(1);
  });

  it("switches to the tenant's effective-visibility listing", async () => {
    installFetchRouter([
      ...orgListingRoutes(),
      {
        method: "GET",
        url: `/api/v1/tenants/${TENANT_ACME}/applications`,
        status: 200,
        body: { items: [TENANT_OWNED_APP] },
      },
    ]);
    seedSession();
    renderConsole(
      <Routes>
        <Route path="/applications" element={<ApplicationsPage />} />
      </Routes>,
      { route: "/applications" },
    );
    await screen.findByRole("gridcell", { name: "Field Service" });

    await user.click(screen.getByTestId("application-mode-tenant"));
    expect(await screen.findByRole("gridcell", { name: "Banking Mobile" })).toBeInTheDocument();
    await waitFor(() => {
      expect(screen.queryByRole("gridcell", { name: "Field Service" })).not.toBeInTheDocument();
    });
    const tenantListings = fetchCalls().filter(([url]) =>
      url.includes(`/tenants/${TENANT_ACME}/applications`),
    );
    expect(tenantListings.length).toBeGreaterThanOrEqual(1);
  });

  it("narrows rows by search over the bundle identifier", async () => {
    installFetchRouter(orgListingRoutes());
    seedSession();
    renderConsole(
      <Routes>
        <Route path="/applications" element={<ApplicationsPage />} />
      </Routes>,
      { route: "/applications" },
    );
    await screen.findByRole("gridcell", { name: "Banking Mobile" });

    await user.type(screen.getByTestId("application-search"), "fieldservice");
    expect(screen.getByRole("gridcell", { name: "Field Service" })).toBeInTheDocument();
    expect(screen.queryByRole("gridcell", { name: "Banking Mobile" })).not.toBeInTheDocument();
  });

  it("registers an application through the platform-coupled dialog", async () => {
    installFetchRouter([
      ...orgListingRoutes(),
      {
        method: "POST",
        url: "/api/v1/applications",
        status: 201,
        body: {
          ...SHARED_APP,
          id: "30000000-0000-4000-8000-000000000003",
          ownershipScope: "TENANT",
          tenantId: TENANT_ACME,
          name: "Wallet",
          platforms: ["IOS"],
          packageName: null,
        },
      },
    ]);
    seedSession();
    renderConsole(
      <Routes>
        <Route path="/applications" element={<ApplicationsPage />} />
      </Routes>,
      { route: "/applications" },
    );
    await screen.findByRole("gridcell", { name: "Banking Mobile" });

    await user.click(screen.getByTestId("create-application-button"));
    await user.type(screen.getByTestId("app-name"), "Wallet");
    await user.click(screen.getByTestId("platform-ios"));

    // Platform coupling: iOS chosen without a bundle id is blocked with
    // the mirrored API rule.
    await user.click(screen.getByTestId("application-submit"));
    expect(screen.getByTestId("platforms-helper")).toHaveTextContent(
      "bundleId is required when its platform is chosen",
    );

    await user.type(screen.getByTestId("app-bundle-id"), "com.acme.wallet");
    const submit = screen.getByTestId("application-submit");
    await waitFor(() => expect(submit).not.toBeDisabled());
    await user.click(submit);

    await waitFor(() => {
      const posts = fetchCalls().filter(
        ([url, init]) => url.endsWith("/api/v1/applications") && init?.method === "POST",
      );
      expect(posts).toHaveLength(1);
      expect(JSON.parse(String(posts[0]?.[1]?.body))).toEqual({
        ownershipScope: "TENANT",
        tenantId: TENANT_ACME,
        name: "Wallet",
        platforms: ["IOS"],
        bundleId: "com.acme.wallet",
      });
    });
    expect(await screen.findByText("Application Wallet registered")).toBeInTheDocument();
  });

  it("registers an organization-shared application under the owning organization", async () => {
    installFetchRouter([
      ...orgListingRoutes(),
      {
        method: "POST",
        url: "/api/v1/applications",
        status: 201,
        body: SHARED_APP,
      },
    ]);
    seedSession();
    renderConsole(
      <Routes>
        <Route path="/applications" element={<ApplicationsPage />} />
      </Routes>,
      { route: "/applications" },
    );
    await screen.findByTestId("applications-grid");

    await user.click(screen.getByTestId("create-application-button"));
    await user.type(screen.getByTestId("app-name"), "Field Service");
    await user.click(screen.getByTestId("platform-android"));
    await user.type(screen.getByTestId("app-package-name"), "com.acme.fieldservice.android");
    const scopeControl = screen.getByTestId("app-scope");
    await user.click(within(scopeControl).getByRole("combobox"));
    await user.click(await screen.findByRole("option", { name: "Organization-shared" }));
    await user.click(screen.getByTestId("application-submit"));

    await waitFor(() => {
      const posts = fetchCalls().filter(
        ([url, init]) => url.endsWith("/api/v1/applications") && init?.method === "POST",
      );
      expect(posts).toHaveLength(1);
      expect(JSON.parse(String(posts[0]?.[1]?.body))).toEqual({
        ownershipScope: "ORGANIZATION",
        organizationId: ORG_ID,
        name: "Field Service",
        platforms: ["ANDROID"],
        packageName: "com.acme.fieldservice.android",
      });
    });
  });

  it("shows the empty registry state without an error envelope", async () => {
    installFetchRouter([
      ...directoryRoutes(),
      {
        method: "GET",
        url: "/api/v1/applications",
        status: 200,
        body: { items: [] },
      },
    ]);
    seedSession();
    renderConsole(
      <Routes>
        <Route path="/applications" element={<ApplicationsPage />} />
      </Routes>,
      { route: "/applications" },
    );

    expect(await screen.findByText("No rows")).toBeInTheDocument();
    expect(screen.queryByTestId("applications-error")).not.toBeInTheDocument();
    expect(screen.queryByTestId("applications-load-more")).not.toBeInTheDocument();
  });

  it("renders the generic error envelope when the listing is conceal-denied", async () => {
    installFetchRouter([
      ...directoryRoutes(),
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
    renderConsole(
      <Routes>
        <Route path="/applications" element={<ApplicationsPage />} />
      </Routes>,
      { route: "/applications" },
    );

    const alert = await screen.findByTestId("applications-error");
    expect(alert).toHaveTextContent("Failed to load applications.");
    // Concealment stays opaque at the screen level: no code leakage.
    expect(alert).not.toHaveTextContent("ORGANIZATION_NOT_FOUND");
  });

  it("walks cursor pages through Load more and terminates with the last page", async () => {
    installFetchRouter([
      ...directoryRoutes(),
      {
        method: "GET",
        url: "/api/v1/applications",
        status: 200,
        sequence: [
          { status: 200, body: { items: [TENANT_OWNED_APP], next_cursor: "cursor-2" } },
          { status: 200, body: { items: [SHARED_APP] } },
        ],
      },
    ]);
    seedSession();
    renderConsole(
      <Routes>
        <Route path="/applications" element={<ApplicationsPage />} />
      </Routes>,
      { route: "/applications" },
    );

    expect(await screen.findByRole("gridcell", { name: "Banking Mobile" })).toBeInTheDocument();
    expect(screen.queryByRole("gridcell", { name: "Field Service" })).not.toBeInTheDocument();

    await user.click(screen.getByTestId("applications-load-more"));
    expect(await screen.findByRole("gridcell", { name: "Field Service" })).toBeInTheDocument();

    // The second fetch carries the opaque cursor from the first page.
    const paged = fetchCalls().filter(([url]) => url.includes("cursor=cursor-2"));
    expect(paged).toHaveLength(1);
    // No next cursor on the last page: the walk ends, Load more is gone.
    await waitFor(() => {
      expect(screen.queryByTestId("applications-load-more")).not.toBeInTheDocument();
    });
  });

  it("asks for a tenant when by-tenant mode has no tenants to list", async () => {
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
        status: 200,
        body: { items: [] },
      },
    ]);
    seedSession();
    renderConsole(
      <Routes>
        <Route path="/applications" element={<ApplicationsPage />} />
      </Routes>,
      { route: "/applications" },
    );
    await screen.findByTestId("applications-grid");

    await user.click(screen.getByTestId("application-mode-tenant"));

    expect(
      await screen.findByText("Pick a tenant to list its visible applications."),
    ).toBeInTheDocument();
    // No tenant listing request goes out without a tenant.
    expect(fetchCalls().some(([url]) => /\/tenants\/.+\/applications/.test(url))).toBe(false);
  });
});
