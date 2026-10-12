/** ABAC policies editor behavior (S14-04, AC4): the policies listed
 * in the defined evaluation order with effect, action, and version
 * chips beside the server-rendered condition summary, the
 * template-to-draft authoring path whose save carries the parsed
 * condition tree (the PRD 25 example one click away), the
 * client-side JSON gate on a malformed condition, the immutable
 * version history dialog, and the inline rendering of the server's
 * field validation (the backend parse is the authority). */
import { cleanup, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { Route, Routes } from "react-router-dom";
import AbacPoliciesPage from "./AbacPoliciesPage";
import { installFetchRouter, renderConsole, seedSession } from "../test/harness";

const user = userEvent.setup();

const ORG_ID = "10000000-0000-4000-8000-000000000001";
const POLICY_ID = "50000000-0000-4000-8000-000000000001";

function policy(overrides: Record<string, unknown> = {}) {
  return {
    id: POLICY_ID,
    organizationId: ORG_ID,
    name: "banking-production-promote",
    version: 2,
    effect: "REQUIRE",
    action: "PROMOTE",
    applicationId: null,
    environmentId: null,
    ring: null,
    condition: { allOf: [{ subjectOrgRole: "RELEASE_MANAGER" }, { securityStatus: "APPROVED" }] },
    conditionSummary: "allOf(subject.orgRole = RELEASE_MANAGER, security.status = APPROVED)",
    position: 10,
    enabled: true,
    createdAt: "2026-10-01T10:00:00Z",
    updatedAt: "2026-10-02T10:00:00Z",
    ...overrides,
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

function renderPage(
  policies: unknown[] = [policy()],
  extraRoutes: Parameters<typeof installFetchRouter>[0] = [],
): Parameters<typeof installFetchRouter>[0] {
  const routes: Parameters<typeof installFetchRouter>[0] = [
    {
      method: "GET",
      url: "/api/v1/abac-policies",
      status: 200,
      body: { items: policies },
    },
    {
      method: "GET",
      url: "/api/v1/applications",
      status: 200,
      body: { items: [] },
    },
    {
      method: "GET",
      url: "/api/v1/environments",
      status: 200,
      body: { items: [] },
    },
    {
      method: "GET",
      url: "/api/v1/rings",
      status: 200,
      body: { items: [] },
    },
    ...extraRoutes,
  ];
  installFetchRouter(routes);
  seedSession();
  renderConsole(
    <Routes>
      <Route path="/abac-policies" element={<AbacPoliciesPage />} />
    </Routes>,
    { route: "/abac-policies" },
  );
  return routes;
}

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe("AbacPoliciesPage", () => {
  it("lists the policies in the defined order with effect, action, version, and the condition summary", async () => {
    renderPage([
      policy({ position: 10, name: "banking-production-promote" }),
      policy({
        id: "50000000-0000-4000-8000-000000000002",
        position: 20,
        name: "no-viewer-downloads",
        effect: "DENY",
        action: "DOWNLOAD",
        version: 1,
        enabled: false,
      }),
    ]);

    const list = await screen.findByTestId("abac-list");
    const names = Array.from(list.querySelectorAll('[data-testid^="abac-"]'))
      .map((row) => row.getAttribute("data-testid")!.slice("abac-".length))
      .filter((name) => !name.startsWith("edit-") && !name.startsWith("delete-"));
    expect(names).toEqual(["banking-production-promote", "no-viewer-downloads"]);
    expect(screen.getByTestId("abac-banking-production-promote")).toHaveTextContent("REQUIRE");
    expect(screen.getByTestId("abac-banking-production-promote")).toHaveTextContent("action: PROMOTE");
    expect(screen.getByTestId("abac-banking-production-promote")).toHaveTextContent("v2");
    expect(screen.getByTestId("abac-banking-production-promote")).toHaveTextContent(
      "subject.orgRole = RELEASE_MANAGER",
    );
    expect(screen.getByTestId("abac-no-viewer-downloads")).toHaveTextContent("DENY");
    expect(screen.getByTestId("abac-no-viewer-downloads")).toHaveTextContent("disabled");
  });

  it("authors the PRD 25 example from a template and saves the parsed condition tree", async () => {
    const routes = renderPage([]);
    routes.push({
      method: "POST",
      url: "/api/v1/abac-policies",
      status: 201,
      body: policy(),
    });

    await screen.findByTestId("abac-list");
    await user.click(screen.getByTestId("abac-create"));
    await user.click(screen.getByTestId("abac-template-banking-production-promote"));
    expect(
      (screen.getByTestId("abac-field-name").querySelector("input") as HTMLInputElement).value,
    ).toBe("banking-production-promote");
    await user.click(screen.getByTestId("abac-save"));

    const body = bodyOf("/api/v1/abac-policies", "POST");
    expect(body.name).toBe("banking-production-promote");
    expect(body.effect).toBe("REQUIRE");
    expect(body.action).toBe("PROMOTE");
    expect(body.condition).toEqual({
      allOf: [{ subjectOrgRole: "RELEASE_MANAGER" }, { securityStatus: "APPROVED" }],
    });
    expect(body.position).toBe(10);
    expect(body.enabled).toBe(true);
  });

  it("refuses a malformed condition before any request (the JSON gate)", async () => {
    renderPage([]);

    await screen.findByTestId("abac-list");
    await user.click(screen.getByTestId("abac-create"));
    await user.type(screen.getByTestId("abac-field-name").querySelector("input")!, "broken");
    const conditionBox = screen
      .getByTestId("abac-field-condition")
      .querySelector("textarea") as HTMLTextAreaElement;
    await user.clear(conditionBox);
    await user.type(conditionBox, "not json at all");
    await user.click(screen.getByTestId("abac-save"));

    expect(screen.getByTestId("abac-field-condition").textContent ?? "").toContain("does not parse");
    expect(
      fetchCalls().some(([candidate, init]) => candidate === "/api/v1/abac-policies" && init?.method === "POST"),
    ).toBe(false);
  });

  it("reads the immutable version history back in the history dialog", async () => {
    renderPage(
      [policy()],
      [
        {
          method: "GET",
          url: `/api/v1/abac-policies/${POLICY_ID}/versions`,
          status: 200,
          body: {
            items: [
              {
                id: "60000000-0000-4000-8000-000000000002",
                policyId: POLICY_ID,
                name: "banking-production-promote",
                version: 2,
                effect: "REQUIRE",
                action: "PROMOTE",
                applicationId: null,
                environmentId: null,
                ring: null,
                condition: { securityStatus: "APPROVED" },
                conditionSummary: "security.status = APPROVED",
                position: 10,
                enabled: true,
                authoredBy: "10000000-0000-4000-8000-000000000009",
                createdAt: "2026-10-02T10:00:00Z",
              },
              {
                id: "60000000-0000-4000-8000-000000000001",
                policyId: POLICY_ID,
                name: "banking-production-promote",
                version: 1,
                effect: "REQUIRE",
                action: "PROMOTE",
                applicationId: null,
                environmentId: null,
                ring: null,
                condition: { subjectOrgRole: "RELEASE_MANAGER" },
                conditionSummary: "subject.orgRole = RELEASE_MANAGER",
                position: 10,
                enabled: true,
                authoredBy: "10000000-0000-4000-8000-000000000009",
                createdAt: "2026-10-01T10:00:00Z",
              },
            ],
          },
        },
      ],
    );

    await screen.findByTestId("abac-list");
    await user.click(screen.getByText("History"));

    const history = await screen.findByTestId("abac-history-list");
    expect(history.textContent).toContain("v2");
    expect(history.textContent).toContain("v1");
    expect(history.textContent).toContain("subject.orgRole = RELEASE_MANAGER");
  });

  it("renders the server's field validation inline (the action/attribute pairing)", async () => {
    renderPage(
      [],
      [
        {
          method: "POST",
          url: "/api/v1/abac-policies",
          status: 400,
          body: {
            status: 400,
            title: "Bad Request",
            detail: "The request is invalid.",
            code: "INVALID_REQUEST",
            errors: {
              condition:
                "environmentName, ringCode, and securityStatus are release attributes carried by the PROMOTE consult alone",
            },
          },
        },
      ],
    );

    await screen.findByTestId("abac-list");
    await user.click(screen.getByTestId("abac-create"));
    await user.type(screen.getByTestId("abac-field-name").querySelector("input")!, "guard");
    await user.click(screen.getByTestId("abac-template-banking-production-promote"));
    // A well-formed condition; the server's refusal is about the
    // action/attribute pairing, not the grammar.
    const actionSelect = screen
      .getByTestId("abac-field-action")
      .querySelector('[role="combobox"]') as HTMLElement;
    await user.click(actionSelect);
    await user.click(await screen.findByRole("option", { name: "DOWNLOAD" }));
    await user.click(screen.getByTestId("abac-save"));

    await waitFor(() => {
      expect(screen.getByTestId("abac-field-condition").textContent ?? "").toContain(
        "release attributes",
      );
    });
  });
});
