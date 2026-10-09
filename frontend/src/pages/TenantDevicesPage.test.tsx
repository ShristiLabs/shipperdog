/** Tenant device registry behavior (issue #48, S11-03): the listing
 * with owner and status, hand registration through the linked-tester
 * picker, the reversible deactivation, and the provisioning CSV
 * export. */
import { cleanup, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it } from "vitest";
import { Route, Routes } from "react-router-dom";
import TenantDevicesPage from "./TenantDevicesPage";
import { installFetchRouter, renderConsole, seedSession } from "../test/harness";

const user = userEvent.setup();

const TENANT_ID = "20000000-0000-4000-8000-000000000001";
const TESTER_ID = "30000000-0000-4000-8000-000000000001";
const DEVICE_ID = "40000000-0000-4000-8000-000000000001";

const DEVICE = {
  id: DEVICE_ID,
  testerId: TESTER_ID,
  ownerEmail: "tester@acme.example",
  ownerName: "Tess Tester",
  platform: "IOS",
  model: "iPhone 15 Pro",
  osVersion: "18.2.1",
  identifier: "00008101-0A1B2C3D4E5F6789",
  status: "ACTIVE",
  createdAt: "2026-10-01T10:00:00Z",
  updatedAt: "2026-10-01T10:00:00Z",
};

function tenantRoutes(): Parameters<typeof installFetchRouter>[0] {
  return [
    {
      method: "GET",
      url: `/api/v1/tenants/${TENANT_ID}`,
      status: 200,
      body: {
        id: TENANT_ID,
        organizationId: "10000000-0000-4000-8000-000000000001",
        name: "Acme Bank",
        externalId: "crm-acme",
        status: "ACTIVE",
        createdAt: "2026-10-01T10:00:00Z",
        updatedAt: "2026-10-01T10:00:00Z",
      },
    },
    {
      method: "GET",
      url: `/api/v1/tenants/${TENANT_ID}/devices`,
      status: 200,
      body: { items: [DEVICE] },
    },
  ];
}

function fetchCalls(): Array<[string, RequestInit | undefined]> {
  const mock = fetch as unknown as { mock: { calls: Array<[string, RequestInit]> } };
  return mock.mock.calls;
}

/** The blob download helpers are DOM-global; stub the anchor click so
 * the export test observes the CSV fetch without a real download. */
function stubDownload() {
  const click = vi.fn();
  const revokeObjectURL = vi.fn();
  vi.stubGlobal(
    "URL",
    Object.assign(URL, { createObjectURL: vi.fn(() => "blob:fixture"), revokeObjectURL }),
  );
  HTMLAnchorElement.prototype.click = click as unknown as () => void;
  return { click, revokeObjectURL };
}

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe("TenantDevicesPage", () => {
  it("shows the registry with owner and status surfaced", async () => {
    installFetchRouter(tenantRoutes());
    seedSession();
    renderConsole(
      <Routes>
        <Route path="/tenants/:tenantId/devices" element={<TenantDevicesPage />} />
      </Routes>,
      { route: `/tenants/${TENANT_ID}/devices` },
    );

    expect(await screen.findByText("00008101-0A1B2C3D4E5F6789")).toBeInTheDocument();
    expect(screen.getByTestId("devices-title")).toHaveTextContent("Acme Bank");
    expect(screen.getByText("tester@acme.example")).toBeInTheDocument();
    expect(screen.getByTestId(`device-status-${DEVICE_ID}`)).toHaveTextContent("active");
  });

  it("registers a device through the linked-tester picker", async () => {
    installFetchRouter([
      ...tenantRoutes(),
      {
        method: "GET",
        url: `/api/v1/tenants/${TENANT_ID}/testers`,
        status: 200,
        body: {
          items: [
            {
              testerId: TESTER_ID,
              email: "tester@acme.example",
              fullName: "Tess Tester",
              testerStatus: "ACTIVE",
              membershipStatus: "ACTIVE",
              effectiveAccess: true,
              linkedAt: "2026-09-01T10:00:00Z",
              updatedAt: "2026-09-01T10:00:00Z",
            },
          ],
        },
      },
      { method: "POST", url: `/api/v1/tenants/${TENANT_ID}/devices`, status: 201, body: DEVICE },
    ]);
    seedSession();
    renderConsole(
      <Routes>
        <Route path="/tenants/:tenantId/devices" element={<TenantDevicesPage />} />
      </Routes>,
      { route: `/tenants/${TENANT_ID}/devices` },
    );
    await screen.findByText("00008101-0A1B2C3D4E5F6789");

    await user.click(screen.getByTestId("register-device"));
    const dialog = await screen.findByTestId("register-dialog");
    const ownerInput = within(dialog).getByLabelText(/owner/i);
    await user.click(ownerInput);
    // The Autocomplete listbox renders in a portal at the document
    // body, outside the dialog's subtree.
    await user.click(await screen.findByText("Tess Tester (tester@acme.example)"));
    await user.type(within(dialog).getByLabelText(/identifier/i), "00008101-0A1B2C3D4E5F6789");
    await user.click(within(dialog).getByTestId("register-submit"));

    await waitFor(() => {
      const registerCall = fetchCalls().find(([url, init]) => url.endsWith("/devices") && init?.method === "POST");
      expect(registerCall).toBeDefined();
      expect(JSON.parse(String(registerCall?.[1]?.body))).toEqual({
        testerId: TESTER_ID,
        platform: "IOS",
        identifier: "00008101-0A1B2C3D4E5F6789",
      });
    });
  });

  it("deactivates a device reversibly", async () => {
    installFetchRouter([
      {
        method: "GET",
        url: `/api/v1/tenants/${TENANT_ID}`,
        status: 200,
        body: {
          id: TENANT_ID,
          organizationId: "10000000-0000-4000-8000-000000000001",
          name: "Acme Bank",
          externalId: "crm-acme",
          status: "ACTIVE",
          createdAt: "2026-10-01T10:00:00Z",
          updatedAt: "2026-10-01T10:00:00Z",
        },
      },
      {
        method: "GET",
        url: `/api/v1/tenants/${TENANT_ID}/devices`,
        status: 200,
        // The listing answers ACTIVE first, then the invalidated
        // refetch after the switch answers DEACTIVATED (sequence).
        sequence: [
          { status: 200, body: { items: [DEVICE] } },
          { status: 200, body: { items: [{ ...DEVICE, status: "DEACTIVATED" }] } },
        ],
      },
      {
        method: "PUT",
        url: `/api/v1/tenants/${TENANT_ID}/devices/${DEVICE_ID}/status`,
        status: 200,
        body: { ...DEVICE, status: "DEACTIVATED" },
      },
    ]);
    seedSession();
    renderConsole(
      <Routes>
        <Route path="/tenants/:tenantId/devices" element={<TenantDevicesPage />} />
      </Routes>,
      { route: `/tenants/${TENANT_ID}/devices` },
    );
    await screen.findByTestId(`deactivate-${DEVICE_ID}`);
    await user.click(screen.getByTestId(`deactivate-${DEVICE_ID}`));

    await waitFor(() => {
      const statusCall = fetchCalls().find(
        ([url, init]) => url.endsWith(`/devices/${DEVICE_ID}/status`) && init?.method === "PUT",
      );
      expect(statusCall).toBeDefined();
      expect(JSON.parse(String(statusCall?.[1]?.body))).toEqual({ status: "DEACTIVATED" });
    });
    expect(await screen.findByTestId(`reactivate-${DEVICE_ID}`)).toBeInTheDocument();
  });

  it("renders the empty registry without rows or pagination", async () => {
    installFetchRouter([
      ...tenantRoutes().filter((route) => !route.url.endsWith("/devices")),
      { method: "GET", url: `/api/v1/tenants/${TENANT_ID}/devices`, status: 200, body: { items: [] } },
    ]);
    seedSession();
    renderConsole(
      <Routes>
        <Route path="/tenants/:tenantId/devices" element={<TenantDevicesPage />} />
      </Routes>,
      { route: `/tenants/${TENANT_ID}/devices` },
    );

    expect(await screen.findByText("No rows")).toBeInTheDocument();
    expect(screen.queryByTestId("devices-load-more")).not.toBeInTheDocument();
  });

  it("surfaces the registry load failure", async () => {
    installFetchRouter([
      ...tenantRoutes().filter((route) => !route.url.endsWith("/devices")),
      {
        method: "GET",
        url: `/api/v1/tenants/${TENANT_ID}/devices`,
        status: 500,
        body: { title: "Internal Server Error", status: 500, code: "INTERNAL_ERROR" },
      },
    ]);
    seedSession();
    renderConsole(
      <Routes>
        <Route path="/tenants/:tenantId/devices" element={<TenantDevicesPage />} />
      </Routes>,
      { route: `/tenants/${TENANT_ID}/devices` },
    );

    expect(await screen.findByText("Failed to load the device registry.")).toBeInTheDocument();
    expect(screen.queryByTestId("device-grid")).not.toBeInTheDocument();
  });

  it("downloads the provisioning CSV export", async () => {
    const { click } = stubDownload();
    installFetchRouter([
      ...tenantRoutes(),
      {
        method: "GET",
        url: `/api/v1/tenants/${TENANT_ID}/devices/export`,
        status: 200,
        body: "identifier,platform,model,os_version,owner_email\n00008101-0A1B2C3D4E5F6789,IOS,iPhone 15 Pro,18.2.1,tester@acme.example\n",
      },
      { method: "GET", url: `/api/v1/tenants/${TENANT_ID}/devices`, status: 200, body: { items: [] } },
    ]);
    seedSession();
    renderConsole(
      <Routes>
        <Route path="/tenants/:tenantId/devices" element={<TenantDevicesPage />} />
      </Routes>,
      { route: `/tenants/${TENANT_ID}/devices` },
    );

    await user.click(await screen.findByTestId("export-devices"));

    await waitFor(() => {
      expect(click).toHaveBeenCalled();
    });
    const exportCall = fetchCalls().find(([url]) => url.endsWith("/devices/export"));
    expect(exportCall).toBeDefined();
  });
});
