/** Release rings editor behavior (S10-04, AC1): the effective
 * vocabulary listed in progressive order with Production last, the
 * organization/system provenance and the PRD 8.10 property chips, and
 * the edit PATCH that carries name, position, the composed eligibility
 * object (empty CLEARS the rules), and the three references. */
import { cleanup, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { Route, Routes } from "react-router-dom";
import RingsPage from "./RingsPage";
import { installFetchRouter, renderConsole, seedSession } from "../test/harness";

const user = userEvent.setup();

const ORG_ID = "10000000-0000-4000-8000-000000000001";
const TENANT_ACME = "20000000-0000-4000-8000-000000000001";
const RING_QA = "30000000-0000-4000-8000-000000000002";

const SEEDED = [
  ring("engineering", "Engineering", 0, true),
  ring("qa", "QA", 1, true),
  ring("tenant-pilot", "Tenant Pilot", 2, true, { tenants: [TENANT_ACME] }),
  ring("uat", "UAT", 3, true),
  ring("production", "Production", 4, true, undefined, "final-signoff", [
    { gateType: "coverage-threshold", required: true },
  ]),
];

function ring(
  code: string,
  name: string,
  position: number,
  organizationScoped: boolean,
  eligibility: Record<string, string[]> = {},
  approvalPolicy: string | null = null,
  gateSet: Array<{ gateType: string; required: boolean }> = [],
) {
  return {
    id: `30000000-0000-4000-8000-00000000000${position + 1}`,
    code,
    name,
    position,
    organizationScoped,
    eligibility,
    approvalPolicy,
    promotionPolicy: "MANUAL",
    distributionChannel: null,
    gateSet,
  };
}

function fetchCalls(): Array<[string, RequestInit | undefined]> {
  const mock = fetch as unknown as { mock: { calls: Array<[string, RequestInit]> } };
  return mock.mock.calls;
}

function bodyOf(url: string, method: string): Record<string, unknown> {
  const call = fetchCalls().find(
    ([candidate, init]) => candidate === url && (init?.method ?? "GET") === method,
  );
  expect(call, `${method} ${url} was issued`).toBeDefined();
  return JSON.parse((call![1] as RequestInit).body as string) as Record<string, unknown>;
}

function renderPage(rings: unknown[] = SEEDED): Parameters<typeof installFetchRouter>[0] {
  const routes: Parameters<typeof installFetchRouter>[0] = [
    {
      method: "GET",
      url: "/api/v1/rings",
      status: 200,
      body: { items: rings },
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
  ];
  installFetchRouter(routes);
  seedSession();
  renderConsole(
    <Routes>
      <Route path="/rings" element={<RingsPage />} />
    </Routes>,
    { route: "/rings" },
  );
  return routes;
}

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe("RingsPage", () => {
  it("lists the ladder in progressive order with provenance and property chips", async () => {
    renderPage();

    const list = await screen.findByTestId("ring-list");
    const codes = Array.from(
      list.querySelectorAll('[data-testid^="ring-"]:not([data-testid^="ring-edit-"])'),
    ).map((row) => row.getAttribute("data-testid")!.slice("ring-".length));
    expect(codes).toEqual([
      "engineering",
      "qa",
      "tenant-pilot",
      "uat",
      "production",
    ]);
    expect(screen.getByTestId("ring-production")).toHaveTextContent("organization");
    expect(screen.getByTestId("ring-production")).toHaveTextContent("policy final-signoff");
    expect(screen.getByTestId("ring-production")).toHaveTextContent("1 required gates");
    expect(screen.getByTestId("ring-tenant-pilot")).toHaveTextContent("eligibility: 1 tenants, 0 groups");
    expect(screen.getByTestId("ring-engineering")).toHaveTextContent("eligibility: everyone");
  });

  it("edits a ring with the composed eligibility object and the references", async () => {
    const routes = renderPage();
    routes.push({
      method: "PATCH",
      url: `/api/v1/rings/${RING_QA}?organizationId=${ORG_ID}`,
      status: 200,
      body: { ...SEEDED[1], name: "Hardened QA" },
    });

    await screen.findByTestId("ring-list");
    await user.click(screen.getByTestId("ring-edit-qa"));
    const nameInput = (await screen.findByTestId("ring-name")).querySelector("input")!;
    await user.clear(nameInput);
    await user.type(nameInput, "Hardened QA");
    await user.click(screen.getByTestId("ring-save"));

    await waitFor(() => {
      expect(
        fetchCalls().some(
          ([candidate, init]) =>
            candidate === `/api/v1/rings/${RING_QA}?organizationId=${ORG_ID}` &&
            init?.method === "PATCH",
        ),
      ).toBe(true);
    });
    const body = bodyOf(`/api/v1/rings/${RING_QA}?organizationId=${ORG_ID}`, "PATCH");
    expect(body.name).toBe("Hardened QA");
    expect(body.position).toBe(1);
    // The empty eligibility object CLEARS the standing rules.
    expect(body.eligibility).toEqual({});
    expect(body.promotionPolicy).toBe("MANUAL");
  });
});
