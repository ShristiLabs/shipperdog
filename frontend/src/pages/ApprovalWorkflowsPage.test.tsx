/** Approval workflow editor behavior (issue #41, AC4): the
 * organization's definitions listed with their group-shaped chain
 * summaries, the create dialog's client-side validation refusing the
 * unreachable shapes (empty group, duplicate role, malformed name),
 * the ANY/ALL mode toggle, and the edit PUT that replaces the chain
 * and detaches the ring when emptied. */
import { cleanup, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it } from "vitest";
import { Route, Routes } from "react-router-dom";
import ApprovalWorkflowsPage from "./ApprovalWorkflowsPage";
import { installFetchRouter, renderConsole, seedSession } from "../test/harness";

const user = userEvent.setup();

const ORG_ID = "10000000-0000-4000-8000-000000000001";
const TENANT_ACME = "20000000-0000-4000-8000-000000000001";
const ACTOR_ID = "10000000-0000-4000-8000-000000000010";
const WORKFLOW_ID = "70000000-0000-4000-8000-000000000001";

/** The PRD FR-016 topology as a definition: QA -> (Security AND
 * Compliance, with Compliance conditional) -> Product. */
const ENTERPRISE = {
  id: WORKFLOW_ID,
  organizationId: ORG_ID,
  name: "enterprise",
  ring: "qa",
  steps: [
    {
      position: 1,
      requiredRole: "QA_ENGINEER",
      tenantId: null,
      groupPosition: 1,
      groupMode: "ALL",
      condition: null,
    },
    {
      position: 2,
      requiredRole: "SECURITY_REVIEWER",
      tenantId: null,
      groupPosition: 2,
      groupMode: "ALL",
      condition: null,
    },
    {
      position: 3,
      requiredRole: "COMPLIANCE_REVIEWER",
      tenantId: null,
      groupPosition: 2,
      groupMode: "ALL",
      condition: { type: "ENVIRONMENT_EQUALS", key: null, value: "production" },
    },
    {
      position: 4,
      requiredRole: "APP_OWNER",
      tenantId: TENANT_ACME,
      groupPosition: 3,
      groupMode: "ALL",
      condition: null,
    },
  ],
  createdBy: ACTOR_ID,
  createdAt: "2026-10-01T10:00:00Z",
  updatedAt: "2026-10-01T10:00:00Z",
};

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

function renderPage(
  workflows: unknown[] = [ENTERPRISE],
): Parameters<typeof installFetchRouter>[0] {
  const routes: Parameters<typeof installFetchRouter>[0] = [
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
      url: "/api/v1/approval-workflows",
      status: 200,
      body: { items: workflows },
    },
  ];
  installFetchRouter(routes);
  seedSession();
  renderConsole(
    <Routes>
      <Route path="/approval-workflows" element={<ApprovalWorkflowsPage />} />
    </Routes>,
    { route: "/approval-workflows" },
  );
  return routes;
}

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe("ApprovalWorkflowsPage", () => {
  it("lists the definitions with their group-shaped chain summaries and conditions", async () => {
    renderPage();

    const row = await screen.findByTestId("workflow-enterprise");
    expect(row).toHaveTextContent("enterprise");
    expect(row).toHaveTextContent("ring qa");
    // The parallel group renders parenthesized; sequential steps chain.
    expect(row).toHaveTextContent("QA → (Security + Compliance) → Product");
    // The conditional step's predicate renders in prose.
    expect(row).toHaveTextContent("Compliance if environment = production");
  });

  it("creates a grouped definition after validation refuses the unreachable shapes", async () => {
    const routes = renderPage();
    routes.push({
      method: "POST",
      url: "/api/v1/approval-workflows",
      status: 201,
      body: ENTERPRISE,
    });

    await user.click(await screen.findByTestId("workflow-create"));

    // A malformed name refuses before any write.
    const name = screen.getByTestId("workflow-name");
    await user.clear(name);
    await user.type(name, "Not Kebab");
    expect(await screen.findByTestId("workflow-validation")).toHaveTextContent("kebab");
    // Save is disabled while any validation error stands.
    expect(screen.getByTestId("workflow-save")).toBeDisabled();

    await user.clear(name);
    await user.type(name, "either-of");
    // The default draft is one ALL group with one QA step — valid now.
    await waitFor(() => expect(screen.getByTestId("workflow-save")).toBeEnabled());

    // Add the OR sibling and toggle the group to ANY.
    await user.click(screen.getByTestId("workflow-add-step-0"));
    const roleControls = screen.getAllByTestId("workflow-step-role");
    await user.click(within(roleControls[1]!).getByRole("combobox"));
    await user.click(screen.getByRole("option", { name: "SECURITY_REVIEWER" }));
    const mode = screen.getByTestId("workflow-mode-0");
    await user.click(within(mode).getByRole("combobox"));
    await user.click(screen.getByRole("option", { name: "ANY — first approval satisfies" }));

    // A duplicate role is the unreachable second demand: refuse it.
    await user.click(screen.getByTestId("workflow-add-step-0"));
    const roleControlsAgain = screen.getAllByTestId("workflow-step-role");
    await user.click(within(roleControlsAgain[2]!).getByRole("combobox"));
    await user.click(screen.getByRole("option", { name: "QA_ENGINEER" }));
    expect(screen.getByTestId("workflow-validation")).toHaveTextContent("Each role signs once");
    expect(screen.getByTestId("workflow-save")).toBeDisabled();
    // Remove the duplicate — the draft is valid again.
    const removes = screen.getAllByTestId("workflow-step-remove");
    await user.click(removes[removes.length - 1]!);

    await waitFor(() => expect(screen.getByTestId("workflow-save")).toBeEnabled());
    await user.click(screen.getByTestId("workflow-save"));

    await waitFor(() =>
      expect(bodyOf("/api/v1/approval-workflows", "POST")).toEqual({
        organizationId: ORG_ID,
        name: "either-of",
        ring: null,
        groups: [
          {
            mode: "ANY",
            steps: [
              { role: "QA_ENGINEER", tenantId: null, condition: null },
              { role: "SECURITY_REVIEWER", tenantId: null, condition: null },
            ],
          },
        ],
      }),
    );
  });

  it("edits a definition: the chain replaces and an emptied ring detaches", async () => {
    const routes = renderPage();
    routes.push({
      method: "PUT",
      url: `/api/v1/approval-workflows/${WORKFLOW_ID}?organizationId=${ORG_ID}`,
      status: 200,
      body: ENTERPRISE,
    });

    await user.click(await screen.findByTestId("workflow-edit-enterprise"));

    // The editor loads the definition's groups: three of them.
    expect(screen.getByTestId("workflow-group-0")).toHaveTextContent("Group 1");
    expect(screen.getByTestId("workflow-group-1")).toHaveTextContent("Group 2");
    expect(screen.getByTestId("workflow-group-2")).toHaveTextContent("Group 3");
    // The name is the binding vocabulary — not editable.
    expect(screen.getByTestId("workflow-name")).toBeDisabled();

    // Detach the ring by emptying it.
    const ring = screen.getByTestId("workflow-ring");
    await user.clear(ring);
    await user.click(screen.getByTestId("workflow-save"));

    await waitFor(() => {
      expect(
        bodyOf(`/api/v1/approval-workflows/${WORKFLOW_ID}?organizationId=${ORG_ID}`, "PUT"),
      ).toEqual({
        ring: null,
        groups: [
          { mode: "ALL", steps: [{ role: "QA_ENGINEER", tenantId: null, condition: null }] },
          {
            mode: "ALL",
            steps: [
              { role: "SECURITY_REVIEWER", tenantId: null, condition: null },
              {
                role: "COMPLIANCE_REVIEWER",
                tenantId: null,
                condition: { type: "ENVIRONMENT_EQUALS", key: null, value: "production" },
              },
            ],
          },
          {
            mode: "ALL",
            steps: [{ role: "APP_OWNER", tenantId: TENANT_ACME, condition: null }],
          },
        ],
      });
    });
  });
});
