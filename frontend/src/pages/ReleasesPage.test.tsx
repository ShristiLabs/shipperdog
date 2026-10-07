/** Release list screen behavior (issue #59): the application-addressed
 * listing with its server-side conjunctive filters (status/version/
 * tenant ride the query string), the client-side ring filter and
 * search over loaded pages, cursor paging via Load more, and the
 * loading/empty/error states. */
import { cleanup, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it } from "vitest";
import { Route, Routes } from "react-router-dom";
import ReleasesPage from "./ReleasesPage";
import { installFetchRouter, renderConsole, seedSession } from "../test/harness";

const user = userEvent.setup();

const ORG_ID = "10000000-0000-4000-8000-000000000001";
const TENANT_ACME = "20000000-0000-4000-8000-000000000001";
const APPLICATION_ID = "30000000-0000-4000-8000-000000000001";
const VERSION_ID = "40000000-0000-4000-8000-000000000001";
const RELEASE_DRAFT = {
  id: "50000000-0000-4000-8000-000000000001",
  releaseRef: "R-101",
  applicationId: APPLICATION_ID,
  versionId: VERSION_ID,
  status: "DRAFT",
  ring: "engineering",
  environment: null,
  targetCount: 1,
  createdAt: "2026-10-01T10:00:00Z",
  updatedAt: "2026-10-01T10:00:00Z",
};
const RELEASE_ACTIVE = {
  id: "50000000-0000-4000-8000-000000000002",
  releaseRef: "R-100",
  applicationId: APPLICATION_ID,
  versionId: VERSION_ID,
  status: "ACTIVE",
  ring: "qa",
  environment: "qa",
  targetCount: 2,
  createdAt: "2026-09-30T10:00:00Z",
  updatedAt: "2026-10-01T11:00:00Z",
};

function fetchCalls(): Array<[string, RequestInit | undefined]> {
  const mock = fetch as unknown as { mock: { calls: Array<[string, RequestInit]> } };
  return mock.mock.calls;
}

function releaseListCalls(): string[] {
  return fetchCalls()
    .map(([url]) => url)
    .filter((url) => url.startsWith("/api/v1/releases"));
}

function baseRoutes(): Parameters<typeof installFetchRouter>[0] {
  return [
    {
      method: "GET",
      url: "/api/v1/applications",
      status: 200,
      body: {
        items: [
          {
            id: APPLICATION_ID,
            organizationId: ORG_ID,
            tenantId: TENANT_ACME,
            ownershipScope: "TENANT",
            name: "Banking",
            description: null,
            platforms: ["IOS", "ANDROID"],
            bundleId: "com.acme.banking",
            packageName: "com.acme.banking",
            status: "ACTIVE",
            createdAt: "2026-10-01T10:00:00Z",
            updatedAt: "2026-10-01T10:00:00Z",
          },
        ],
      },
    },
    {
      method: "GET",
      url: "/api/v1/tenants",
      status: 200,
      body: {
        items: [
          {
            id: TENANT_ACME,
            organizationId: ORG_ID,
            name: "Acme",
            externalId: "demo-acme",
            status: "ACTIVE",
            createdAt: "2026-10-01T10:00:00Z",
            updatedAt: "2026-10-01T10:00:00Z",
          },
        ],
      },
    },
    {
      method: "GET",
      url: `/api/v1/applications/${APPLICATION_ID}/versions`,
      status: 200,
      body: {
        items: [
          {
            id: VERSION_ID,
            applicationId: APPLICATION_ID,
            versionName: "7.12.0",
            status: "ACTIVE",
            tags: [],
            createdAt: "2026-10-01T10:00:00Z",
            updatedAt: "2026-10-01T10:00:00Z",
            artifactsByPlatform: {},
          },
        ],
      },
    },
  ];
}

function renderReleases(route = "/releases") {
  renderConsole(
    <Routes>
      <Route path="/releases" element={<ReleasesPage />} />
      <Route path="/releases/:applicationId" element={<ReleasesPage />} />
      <Route
        path="/releases/:applicationId/:releaseId"
        element={<div data-testid="detail-stub" />}
      />
    </Routes>,
    { route },
  );
}

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe("ReleasesPage", () => {
  it("defaults to the first application and lists releases with status chips", async () => {
    installFetchRouter([
      ...baseRoutes(),
      { method: "GET", url: "/api/v1/releases", status: 200, body: { items: [RELEASE_DRAFT, RELEASE_ACTIVE] } },
    ]);
    seedSession();
    renderReleases();

    expect(await screen.findByRole("gridcell", { name: "R-101" })).toBeInTheDocument();
    expect(screen.getByRole("gridcell", { name: "Draft" })).toBeInTheDocument();
    expect(screen.getByRole("gridcell", { name: "Active" })).toBeInTheDocument();
    // The version column resolves the version name through the walk.
    expect(screen.getAllByRole("gridcell", { name: "7.12.0" })).toHaveLength(2);
    // The application filter rode the query string.
    expect(releaseListCalls()[0]).toContain(`applicationId=${APPLICATION_ID}`);
  });

  it("pages through the cursor with Load more", async () => {
    installFetchRouter([
      ...baseRoutes(),
      {
        method: "GET",
        url: "/api/v1/releases",
        status: 200,
        sequence: [
          { status: 200, body: { items: [RELEASE_ACTIVE], next_cursor: "cursor-2" } },
          { status: 200, body: { items: [RELEASE_DRAFT] } },
        ],
      },
    ]);
    seedSession();
    renderReleases();

    expect(await screen.findByRole("gridcell", { name: "R-100" })).toBeInTheDocument();
    expect(screen.queryByRole("gridcell", { name: "R-101" })).not.toBeInTheDocument();

    await user.click(screen.getByTestId("releases-load-more"));
    expect(await screen.findByRole("gridcell", { name: "R-101" })).toBeInTheDocument();
    expect(releaseListCalls().some((url) => url.includes("cursor=cursor-2"))).toBe(true);
  });

  it("sends the status filter server-side", async () => {
    installFetchRouter([
      ...baseRoutes(),
      { method: "GET", url: "/api/v1/releases", status: 200, body: { items: [] } },
    ]);
    seedSession();
    renderReleases();

    await screen.findByTestId("releases-grid");
    const statusControl = screen.getByTestId("release-status-filter");
    await user.click(within(statusControl).getByRole("combobox"));
    await user.click(await screen.findByRole("option", { name: "Approval pending" }));

    expect(releaseListCalls().some((url) => url.includes("status=APPROVAL_PENDING"))).toBe(true);
  });

  it("filters by ring client-side over the loaded pages", async () => {
    installFetchRouter([
      ...baseRoutes(),
      { method: "GET", url: "/api/v1/releases", status: 200, body: { items: [RELEASE_DRAFT, RELEASE_ACTIVE] } },
    ]);
    seedSession();
    renderReleases();

    expect(await screen.findByRole("gridcell", { name: "R-101" })).toBeInTheDocument();
    const ringControl = screen.getByTestId("release-ring-filter");
    await user.click(within(ringControl).getByRole("combobox"));
    await user.click(await screen.findByRole("option", { name: "qa" }));

    expect(screen.queryByRole("gridcell", { name: "R-101" })).not.toBeInTheDocument();
    expect(screen.getByRole("gridcell", { name: "R-100" })).toBeInTheDocument();
    // The ring filter never rode the query string (no such param exists).
    expect(releaseListCalls().every((url) => !url.includes("ring="))).toBe(true);
  });

  it("renders the empty state when the application has no releases", async () => {
    installFetchRouter([
      ...baseRoutes(),
      { method: "GET", url: "/api/v1/releases", status: 200, body: { items: [] } },
    ]);
    seedSession();
    renderReleases();

    expect(await screen.findByTestId("releases-empty")).toBeInTheDocument();
  });

  it("renders the error state when the listing is denied", async () => {
    installFetchRouter([
      ...baseRoutes(),
      {
        method: "GET",
        url: "/api/v1/releases",
        status: 404,
        body: { title: "Not Found", status: 404, code: "APPLICATION_NOT_FOUND", detail: "Resource not found." },
      },
    ]);
    seedSession();
    renderReleases();

    expect(await screen.findByTestId("releases-error")).toBeInTheDocument();
  });

  it("navigates to the deep-linkable release dashboard", async () => {
    installFetchRouter([
      ...baseRoutes(),
      { method: "GET", url: "/api/v1/releases", status: 200, body: { items: [RELEASE_DRAFT] } },
    ]);
    seedSession();
    renderReleases();

    expect(await screen.findByRole("gridcell", { name: "R-101" })).toBeInTheDocument();
    await user.click(screen.getByTestId(`view-${RELEASE_DRAFT.id}`));
    expect(await screen.findByTestId("detail-stub")).toBeInTheDocument();
  });

  it("preselects the route's application (deep link into the listing)", async () => {
    installFetchRouter([
      ...baseRoutes(),
      { method: "GET", url: "/api/v1/releases", status: 200, body: { items: [RELEASE_DRAFT] } },
    ]);
    seedSession();
    renderReleases(`/releases/${APPLICATION_ID}`);

    expect(await screen.findByRole("gridcell", { name: "R-101" })).toBeInTheDocument();
    expect(releaseListCalls()[0]).toContain(`applicationId=${APPLICATION_ID}`);
  });

  it("sends the version and tenant filters server-side", async () => {
    installFetchRouter([
      ...baseRoutes(),
      { method: "GET", url: "/api/v1/releases", status: 200, body: { items: [] } },
    ]);
    seedSession();
    renderReleases();

    await screen.findByTestId("releases-grid");
    const versionPicker = screen.getByTestId("release-version-filter");
    await user.click(within(versionPicker).getByRole("combobox"));
    await user.click(await screen.findByRole("option", { name: "7.12.0" }));

    const tenantPicker = screen.getByTestId("release-tenant-filter");
    await user.click(within(tenantPicker).getByRole("combobox"));
    await user.click(await screen.findByRole("option", { name: "Acme" }));

    expect(
      releaseListCalls().some(
        (url) => url.includes(`versionId=${VERSION_ID}`) && url.includes(`tenantId=${TENANT_ACME}`),
      ),
    ).toBe(true);
  });

  it("resets the dependent filters when the application changes", async () => {
    const SECOND_APP_ID = "30000000-0000-4000-8000-000000000002";
    // The fetch router matches in order: the two-application registry
    // ride ahead of baseRoutes' single-application default.
    installFetchRouter([
      {
        method: "GET",
        url: "/api/v1/applications",
        status: 200,
        body: {
          items: [
            {
              id: APPLICATION_ID,
              organizationId: ORG_ID,
              tenantId: TENANT_ACME,
              ownershipScope: "TENANT",
              name: "Banking",
              description: null,
              platforms: ["IOS"],
              bundleId: "com.acme.banking",
              packageName: null,
              status: "ACTIVE",
              createdAt: "2026-10-01T10:00:00Z",
              updatedAt: "2026-10-01T10:00:00Z",
            },
            {
              id: SECOND_APP_ID,
              organizationId: ORG_ID,
              tenantId: TENANT_ACME,
              ownershipScope: "TENANT",
              name: "Wallet",
              description: null,
              platforms: ["ANDROID"],
              bundleId: null,
              packageName: "com.acme.wallet",
              status: "ACTIVE",
              createdAt: "2026-10-01T10:00:00Z",
              updatedAt: "2026-10-01T10:00:00Z",
            },
          ],
        },
      },
      ...baseRoutes(),
      { method: "GET", url: `/api/v1/applications/${SECOND_APP_ID}/versions`, status: 200, body: { items: [] } },
      { method: "GET", url: "/api/v1/releases", status: 200, body: { items: [] } },
    ]);
    seedSession();
    renderReleases();

    // Set the version filter on the first application...
    await screen.findByTestId("releases-grid");
    const versionPicker = screen.getByTestId("release-version-filter");
    await user.click(within(versionPicker).getByRole("combobox"));
    await user.click(await screen.findByRole("option", { name: "7.12.0" }));
    expect(releaseListCalls().some((url) => url.includes(`versionId=${VERSION_ID}`))).toBe(true);

    // ...then switch applications: the version filter belonged to the
    // old application's vocabulary and must not ride along.
    const appPicker = screen.getByTestId("release-application-picker");
    await user.click(within(appPicker).getByRole("combobox"));
    await user.click(await screen.findByRole("option", { name: "Wallet" }));

    await waitFor(() => {
      const walletCalls = releaseListCalls().filter((url) => url.includes(`applicationId=${SECOND_APP_ID}`));
      expect(walletCalls.length).toBeGreaterThan(0);
      expect(walletCalls.every((url) => !url.includes("versionId="))).toBe(true);
    });
  });
});
