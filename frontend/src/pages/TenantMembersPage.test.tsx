/** Tenant member management behavior (issue #12): roster with role and
 * status surface, add-member dialog with the M1 role picker, and
 * removal confirmation. */
import { cleanup, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it } from "vitest";
import { Route, Routes } from "react-router-dom";
import TenantMembersPage from "./TenantMembersPage";
import { installFetchRouter, renderConsole, seedSession } from "../test/harness";

const user = userEvent.setup();

const TENANT_ID = "20000000-0000-4000-8000-000000000001";
const MEMBER = {
  userId: "10000000-0000-4000-8000-000000000010",
  email: "dev@shipperdog.local",
  fullName: "Dev Admin",
  role: "TENANT_ADMIN",
  status: "ACTIVE",
  createdAt: "2026-10-01T10:00:00Z",
};

function memberRoutes(): Parameters<typeof installFetchRouter>[0] {
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
      url: `/api/v1/tenants/${TENANT_ID}/members`,
      status: 200,
      body: { items: [MEMBER] },
    },
    {
      method: "GET",
      url: "/api/v1/organizations/10000000-0000-4000-8000-000000000001/members",
      status: 200,
      body: {
        items: [
          {
            userId: "10000000-0000-4000-8000-000000000099",
            email: "qa@shipperdog.local",
            fullName: "Quinn QA",
            role: "QA_ENGINEER",
            createdAt: "2026-10-01T10:00:00Z",
          },
        ],
      },
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

describe("TenantMembersPage", () => {
  it("shows the roster with the membership status surfaced", async () => {
    installFetchRouter(memberRoutes());
    seedSession();
    renderConsole(
      <Routes>
        <Route path="/tenants/:tenantId/members" element={<TenantMembersPage />} />
      </Routes>,
      { route: `/tenants/${TENANT_ID}/members` },
    );

    const title = await screen.findByTestId("members-title");
    expect(title).toHaveTextContent("Acme Bank");
    expect(await screen.findByText("dev@shipperdog.local")).toBeInTheDocument();
    expect(screen.getByTestId(`status-${MEMBER.userId}`)).toHaveTextContent("Active");
  });

  it("adds a member with the fixed M1 role picker (Tenant Admin / Tenant Tester only)", async () => {
    installFetchRouter([
      ...memberRoutes(),
      {
        method: "POST",
        url: `/api/v1/tenants/${TENANT_ID}/members`,
        status: 201,
        body: {
          ...MEMBER,
          userId: "10000000-0000-4000-8000-000000000099",
          email: "qa@shipperdog.local",
          role: "TENANT_TESTER",
        },
      },
    ]);
    seedSession();
    renderConsole(
      <Routes>
        <Route path="/tenants/:tenantId/members" element={<TenantMembersPage />} />
      </Routes>,
      { route: `/tenants/${TENANT_ID}/members` },
    );
    await screen.findByText("dev@shipperdog.local");
    await user.click(screen.getByTestId("add-member-button"));
    await user.type(
      screen.getByTestId("member-user-id"),
      "10000000-0000-4000-8000-000000000099",
    );

    const roleControl = screen.getByTestId("member-role");
    await user.click(within(roleControl).getByRole("combobox"));
    const options = await screen.findAllByRole("option");
    expect(options.map((option) => option.textContent)).toEqual([
      "Tenant Admin",
      "Tenant Tester",
    ]);
    await user.click(screen.getByRole("option", { name: "Tenant Tester" }));
    await user.click(screen.getByTestId("member-submit"));

    await waitFor(() => {
      const posts = fetchCalls().filter(
        ([url, init]) =>
          url.includes(`/api/v1/tenants/${TENANT_ID}/members`) && init?.method === "POST",
      );
      expect(posts).toHaveLength(1);
      expect(JSON.parse(String(posts[0]?.[1]?.body))).toEqual({
        userId: "10000000-0000-4000-8000-000000000099",
        role: "TENANT_TESTER",
      });
    });
  });

  it("suspends through the status endpoint and removes only after confirmation", async () => {
    installFetchRouter([
      ...memberRoutes(),
      {
        method: "PUT",
        url: `/api/v1/tenants/${TENANT_ID}/members/${MEMBER.userId}/status`,
        status: 200,
        body: { ...MEMBER, status: "SUSPENDED" },
      },
      {
        method: "DELETE",
        url: `/api/v1/tenants/${TENANT_ID}/members/${MEMBER.userId}`,
        status: 204,
      },
    ]);
    seedSession();
    renderConsole(
      <Routes>
        <Route path="/tenants/:tenantId/members" element={<TenantMembersPage />} />
      </Routes>,
      { route: `/tenants/${TENANT_ID}/members` },
    );
    await user.click(await screen.findByTestId(`suspend-${MEMBER.userId}`));
    await waitFor(() => {
      const puts = fetchCalls().filter(
        ([url, init]) => url.includes("/status") && init?.method === "PUT",
      );
      expect(puts).toHaveLength(1);
      expect(JSON.parse(String(puts[0]?.[1]?.body))).toEqual({ status: "SUSPENDED" });
    });

    await user.click(screen.getByTestId(`remove-${MEMBER.userId}`));
    expect(await screen.findByText("Remove member?")).toBeInTheDocument();
    await user.click(screen.getByTestId("confirm-remove"));
    await waitFor(() => {
      const deletes = fetchCalls().filter(
        ([url, init]) => url.endsWith(`/members/${MEMBER.userId}`) && init?.method === "DELETE",
      );
      expect(deletes).toHaveLength(1);
    });
  });
});
