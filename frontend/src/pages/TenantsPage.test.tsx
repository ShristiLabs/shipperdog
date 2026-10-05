/** Tenant administration screen behavior (issue #12): listing, search,
 * server-side status filter, and the guarded deactivation flow. */
import { cleanup, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it } from "vitest";
import TenantsPage from "./TenantsPage";
import { installFetchRouter, renderConsole, seedSession } from "../test/harness";

const user = userEvent.setup();

const TENANT_ACTIVE = {
  id: "20000000-0000-4000-8000-000000000001",
  organizationId: "10000000-0000-4000-8000-000000000001",
  name: "Acme Bank",
  externalId: "crm-acme",
  status: "ACTIVE",
  createdAt: "2026-10-01T10:00:00Z",
  updatedAt: "2026-10-02T10:00:00Z",
};
const TENANT_DEACTIVATED = {
  ...TENANT_ACTIVE,
  id: "20000000-0000-4000-8000-000000000002",
  name: "Contoso",
  externalId: "crm-contoso",
  status: "DEACTIVATED",
};

function tenantRoutes(): Parameters<typeof installFetchRouter>[0] {
  return [
    {
      method: "GET",
      url: "/api/v1/tenants",
      status: 200,
      body: { items: [TENANT_ACTIVE, TENANT_DEACTIVATED] },
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

describe("TenantsPage", () => {
  it("lists tenants with status chips", async () => {
    installFetchRouter(tenantRoutes());
    seedSession();
    renderConsole(<TenantsPage />);

    expect(await screen.findByRole("gridcell", { name: "Acme Bank" })).toBeInTheDocument();
    expect(screen.getByRole("gridcell", { name: "crm-contoso" })).toBeInTheDocument();
    expect(screen.getAllByText("Active").length).toBeGreaterThanOrEqual(1);
    expect(screen.getByText("Deactivated")).toBeInTheDocument();
  });

  it("narrows rows by search over name and external id", async () => {
    installFetchRouter(tenantRoutes());
    seedSession();
    renderConsole(<TenantsPage />);
    await screen.findByRole("gridcell", { name: "Acme Bank" });

    await user.type(screen.getByTestId("tenant-search"), "crm-contoso");
    expect(screen.getByRole("gridcell", { name: "crm-contoso" })).toBeInTheDocument();
    expect(screen.queryByRole("gridcell", { name: "Acme Bank" })).not.toBeInTheDocument();
  });

  it("applies the status filter as a server-side query parameter", async () => {
    installFetchRouter(tenantRoutes());
    seedSession();
    renderConsole(<TenantsPage />);
    await screen.findByRole("gridcell", { name: "Acme Bank" });

    const filterControl = screen.getByTestId("tenant-status-filter");
    await user.click(within(filterControl).getByRole("combobox"));
    await user.click(await screen.findByRole("option", { name: "Active" }));

    const filtered = fetchCalls().filter(([url]) => url.includes("status=ACTIVE"));
    expect(filtered.length).toBeGreaterThanOrEqual(1);
  });

  it("deactivates only after confirmation, sending the lifecycle transition", async () => {
    installFetchRouter([
      ...tenantRoutes(),
      {
        method: "PUT",
        url: `/api/v1/tenants/${TENANT_ACTIVE.id}`,
        status: 200,
        body: { ...TENANT_ACTIVE, status: "DEACTIVATED" },
      },
    ]);
    seedSession();
    renderConsole(<TenantsPage />);
    await screen.findByRole("gridcell", { name: "Acme Bank" });

    await user.click(screen.getByTestId(`deactivate-${TENANT_ACTIVE.id}`));
    expect(
      await screen.findByText(/Confirm deactivation of/i, { exact: false }),
    ).toBeInTheDocument();
    expect(screen.getByTestId(`deactivate-${TENANT_ACTIVE.id}`)).toBeInTheDocument();

    await user.click(screen.getByTestId("confirm-deactivate"));
    const puts = fetchCalls().filter(
      ([url, init]) =>
        url.includes(`/api/v1/tenants/${TENANT_ACTIVE.id}`) && init?.method === "PUT",
    );
    expect(puts).toHaveLength(1);
    expect(JSON.parse(String(puts[0]?.[1]?.body))).toEqual({ status: "DEACTIVATED" });
    await screen.findByText("Tenant Acme Bank deactivated");
  });
});
