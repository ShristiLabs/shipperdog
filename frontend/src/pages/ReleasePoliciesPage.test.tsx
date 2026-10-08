/** Release policies editor behavior (S10-06, AC6): the rules listed
 * in the defined evaluation order with action and scope chips and
 * the server-rendered condition summary, the template-to-draft
 * authoring path whose save carries the parsed condition tree, the
 * client-side JSON gate on a malformed condition, and the inline
 * rendering of the server's field validation (references resolvable,
 * the condition compiles — the backend parse is the authority). */
import { cleanup, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { Route, Routes } from "react-router-dom";
import ReleasePoliciesPage from "./ReleasePoliciesPage";
import { installFetchRouter, renderConsole, seedSession } from "../test/harness";

const user = userEvent.setup();

const ORG_ID = "10000000-0000-4000-8000-000000000001";
const RULE_ID = "40000000-0000-4000-8000-000000000001";

function rule(overrides: Record<string, unknown> = {}) {
  return {
    id: RULE_ID,
    organizationId: ORG_ID,
    name: "no-critical-vulns",
    scope: "ORGANIZATION",
    applicationId: null,
    ring: null,
    environmentId: null,
    action: "BLOCK",
    condition: { metric: "CRITICAL_VULNERABILITIES", operator: ">", value: 0 },
    conditionSummary: "metric CRITICAL_VULNERABILITIES > 0.0",
    position: 10,
    enabled: true,
    createdAt: "2026-10-01T10:00:00Z",
    updatedAt: "2026-10-01T10:00:00Z",
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
  rules: unknown[] = [rule()],
  extraRoutes: Parameters<typeof installFetchRouter>[0] = [],
): Parameters<typeof installFetchRouter>[0] {
  const routes: Parameters<typeof installFetchRouter>[0] = [
    {
      method: "GET",
      url: "/api/v1/release-policies",
      status: 200,
      body: { items: rules },
    },
    {
      method: "GET",
      url: "/api/v1/rings",
      status: 200,
      body: {
        items: [
          {
            id: "30000000-0000-4000-8000-000000000002",
            code: "qa",
            name: "QA",
            position: 1,
            organizationScoped: true,
            eligibility: {},
            approvalPolicy: null,
            promotionPolicy: "MANUAL",
            distributionChannel: null,
            gateSet: [],
          },
        ],
      },
    },
    {
      method: "GET",
      url: "/api/v1/release-policies/skip-allowances",
      status: 200,
      body: { organizationId: ORG_ID, rings: ["uat"] },
    },
    ...extraRoutes,
  ];
  installFetchRouter(routes);
  seedSession();
  renderConsole(
    <Routes>
      <Route path="/release-policies" element={<ReleasePoliciesPage />} />
    </Routes>,
    { route: "/release-policies" },
  );
  return routes;
}

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe("ReleasePoliciesPage", () => {
  it("lists the rules in the defined order with action, scope, and the condition summary", async () => {
    renderPage([
      rule({ position: 10, name: "no-critical-vulns" }),
      rule({ id: "40000000-0000-4000-8000-000000000002", position: 20, name: "engagement-floor", action: "HOLD_FOR_REVIEW" }),
    ]);

    const list = await screen.findByTestId("policy-list");
    const names = Array.from(list.querySelectorAll('[data-testid^="policy-"]'))
      .map((row) => row.getAttribute("data-testid")!.slice("policy-".length))
      .filter((name) => !name.startsWith("edit-") && !name.startsWith("delete-"));
    expect(names).toEqual(["no-critical-vulns", "engagement-floor"]);
    expect(screen.getByTestId("policy-no-critical-vulns")).toHaveTextContent("BLOCK");
    expect(screen.getByTestId("policy-no-critical-vulns")).toHaveTextContent("scope: organization");
    expect(screen.getByTestId("policy-engagement-floor")).toHaveTextContent("HOLD_FOR_REVIEW");
    expect(screen.getByTestId("policy-skip-allowances")).toHaveTextContent("uat");
  });

  it("authors from a template and saves the parsed condition tree", async () => {
    const routes = renderPage([]);
    routes.push({
      method: "POST",
      url: "/api/v1/release-policies",
      status: 201,
      body: rule(),
    });

    await screen.findByTestId("policy-list");
    await user.click(screen.getByTestId("policy-create"));
    // The template seeds the name, the action, and the condition.
    await user.click(screen.getByTestId("policy-template-no-critical-vulns"));
    expect(
      (screen.getByTestId("policy-field-name").querySelector("input") as HTMLInputElement).value,
    ).toBe("no-critical-vulns");
    await user.click(screen.getByTestId("policy-save"));

    await waitFor(() => {
      expect(
        fetchCalls().some(([candidate, init]) => candidate === "/api/v1/release-policies" && init?.method === "POST"),
      ).toBe(true);
    });
    const body = bodyOf("/api/v1/release-policies", "POST");
    expect(body.name).toBe("no-critical-vulns");
    expect(body.action).toBe("BLOCK");
    expect(body.condition).toEqual({ metric: "CRITICAL_VULNERABILITIES", operator: ">", value: 0 });
    expect(body.position).toBe(10);
    expect(body.enabled).toBe(true);
  });

  it("refuses a malformed condition before any request (the JSON gate)", async () => {
    renderPage([]);

    await screen.findByTestId("policy-list");
    await user.click(screen.getByTestId("policy-create"));
    await user.type(screen.getByTestId("policy-field-name").querySelector("input")!, "broken");
    const conditionBox = screen
      .getByTestId("policy-field-condition")
      .querySelector("textarea") as HTMLTextAreaElement;
    await user.clear(conditionBox);
    await user.type(conditionBox, "not json at all");
    await user.click(screen.getByTestId("policy-save"));

    expect(
      screen.getByTestId("policy-field-condition").textContent ?? "",
    ).toContain("does not parse");
    expect(
      fetchCalls().some(([candidate, init]) => candidate === "/api/v1/release-policies" && init?.method === "POST"),
    ).toBe(false);
  });

  it("saving the skip allowances untouched re-puts the loaded set, never a silent wipe (H1)", async () => {
    const routes = renderPage([]);
    routes.push({
      method: "PUT",
      url: "/api/v1/release-policies/skip-allowances",
      status: 200,
      body: { organizationId: ORG_ID, rings: ["uat"] },
    });

    await screen.findByTestId("policy-skip-allowances");
    // The input DISPLAYS the loaded standing ("uat") while the draft
    // is untouched: Save must carry what the screen shows.
    await user.click(screen.getByTestId("policy-skip-save"));

    await waitFor(() => {
      const calls = (fetch as unknown as { mock: { calls: Array<[string, RequestInit]> } })
        .mock.calls;
      const call = calls.find(
        ([candidate, init]) =>
          candidate === "/api/v1/release-policies/skip-allowances" && init?.method === "PUT",
      );
      expect(call, "the skip-allowances PUT was issued").toBeDefined();
      expect(JSON.parse((call![1] as RequestInit).body as string)).toEqual({
        organizationId: ORG_ID,
        rings: ["uat"],
      });
    });

    // An EDITED save carries the typed set (the whole-set PUT
    // semantics, from the operator's own hand).
    const input = screen
      .getByTestId("policy-skip-input")
      .querySelector("input") as HTMLInputElement;
    await user.clear(input);
    await user.type(input, "uat, production");
    await user.click(screen.getByTestId("policy-skip-save"));
    await waitFor(() => {
      const calls = (fetch as unknown as { mock: { calls: Array<[string, RequestInit]> } })
        .mock.calls;
      const put = calls
        .filter(
          ([candidate, init]) =>
            candidate === "/api/v1/release-policies/skip-allowances" && init?.method === "PUT",
        )
        .map(([, init]) => JSON.parse((init as RequestInit).body as string));
      expect(put).toEqual([{ organizationId: ORG_ID, rings: ["uat"] }, { organizationId: ORG_ID, rings: ["uat", "production"] }]);
    });
  });

  it("renders the server's field validation inline (references resolvable)", async () => {
    renderPage(
      [],
      [
        {
          method: "POST",
          url: "/api/v1/release-policies",
          status: 400,
          body: {
            status: 400,
            title: "Bad Request",
            detail: "The request is invalid.",
            code: "INVALID_REQUEST",
            errors: { ring: "unknown ring code 'staging' for this organization" },
          },
        },
      ],
    );

    await screen.findByTestId("policy-list");
    await user.click(screen.getByTestId("policy-create"));
    const scopeSelect = screen
      .getByTestId("policy-field-scope")
      .querySelector('[role="combobox"]') as HTMLElement;
    await user.click(scopeSelect);
    await user.click(await screen.findByRole("option", { name: "Ring (entry)" }));
    await user.type(screen.getByTestId("policy-field-name").querySelector("input")!, "ring-guard");
    // A well-formed condition: the server's refusal is about the
    // ring reference, not the grammar.
    await user.click(screen.getByTestId("policy-template-no-critical-vulns"));
    await user.click(screen.getByTestId("policy-save"));

    await waitFor(() => {
      expect(screen.getByTestId("policy-field-ring").textContent ?? "").toContain("unknown ring code");
    });
  });
});
