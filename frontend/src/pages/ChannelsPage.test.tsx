/** Distribution channels behavior (S13-01, issue #61): the channel
 * catalog renders the registered adapters with their capabilities and
 * credential providers, the bindings table carries the opaque secret
 * reference (never material), the bind dialog issues the PUT that
 * carries material exactly once at the addressed level, and the verify
 * action answers only whether the reference resolves. */
import { cleanup, fireEvent, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { Route, Routes } from "react-router-dom";
import ChannelsPage from "./ChannelsPage";
import { installFetchRouter, renderConsole, seedSession, type Route as FetchRoute } from "../test/harness";

const user = userEvent.setup();

const ORG_ID = "10000000-0000-4000-8000-000000000001";
const ACCOUNT_ID = "40000000-0000-4000-8000-000000000001";

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

function renderPage(extraRoutes: FetchRoute[] = []): void {
  installFetchRouter([
    {
      method: "GET",
      url: "/api/v1/distribution/channels",
      status: 200,
      body: {
        channels: [
          {
            channelId: "direct",
            displayName: "Direct Download",
            description: "ShipperDog's own signed-URL download tier.",
            capabilities: ["DIRECT_ISSUANCE"],
            credentialProviders: [],
          },
          {
            channelId: "testflight",
            displayName: "TestFlight",
            description: "Apple's beta channel (S13-02's connector).",
            capabilities: ["BUILD_UPLOAD", "BUILD_STATUS", "TESTER_GROUP_SYNC", "SUBMISSION"],
            credentialProviders: ["apple"],
          },
        ],
      },
    },
    {
      method: "GET",
      url: "/api/v1/distribution/appstore/metadata",
      status: 200,
      body: {
        items: [
          {
            id: "50000000-0000-4000-8000-000000000001",
            organizationId: ORG_ID,
            applicationId: "30000000-0000-4000-8000-000000000001",
            channel: "appstore",
            locale: "en-US",
            description: "The banking app your team pilots with.",
            keywords: "banking,pilot",
            screenshotRefs: ["asc-asset://screens/en-primary"],
            releaseNotes: "Stability first.",
            createdAt: "2026-10-01T10:00:00Z",
            updatedAt: "2026-10-01T10:00:00Z",
          },
        ],
      },
    },
    {
      method: "GET",
      url: "/api/v1/distribution/credentials",
      status: 200,
      body: {
        items: [
          {
            id: ACCOUNT_ID,
            level: "organization",
            organizationId: ORG_ID,
            applicationId: null,
            environmentId: null,
            provider: "apple",
            name: "Org Apple Account",
            metadata: "{}",
            secretReference: "local-envelope://org/apple/1",
            createdAt: "2026-10-01T10:00:00Z",
            updatedAt: "2026-10-01T10:00:00Z",
          },
        ],
      },
    },
    ...extraRoutes,
  ]);
  seedSession();
  renderConsole(
    <Routes>
      <Route path="/channels" element={<ChannelsPage />} />
    </Routes>,
    { route: "/channels" },
  );
}

/** MUI selects open on mousedown, not click (the jsdom recipe). */
function chooseSelectOption(label: string, option: string): void {
  fireEvent.mouseDown(screen.getByRole("combobox", { name: new RegExp(label) }));
  fireEvent.click(screen.getAllByRole("option").find((node) => node.textContent === option) as HTMLElement);
}

describe("ChannelsPage", () => {
  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it("renders the catalog with capabilities and the bindings with references only", async () => {
    renderPage();

    expect(await screen.findByTestId("channel-direct")).toBeTruthy();
    expect(screen.getByTestId("channel-testflight").textContent).toContain("BUILD_UPLOAD");
    expect(screen.getByTestId("channel-testflight").textContent).toContain("apple");
    expect(screen.getByTestId("account-apple-organization").textContent).toContain(
      "local-envelope://org/apple/1",
    );
    // The material is nowhere: it never existed on any answer.
    expect(document.body.textContent ?? "").not.toContain("secretMaterial");
  });

  it("issues the bind PUT at the organization level and reveals the override pickers", async () => {
    renderPage([
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
        method: "PUT",
        url: "/api/v1/distribution/credentials",
        status: 200,
        body: {
          id: "40000000-0000-4000-8000-000000000002",
          level: "organization",
          organizationId: ORG_ID,
          applicationId: null,
          environmentId: null,
          provider: "apple",
          name: "Org Apple Account",
          metadata: "{}",
          secretReference: "local-envelope://org/apple/2",
          createdAt: "2026-10-02T10:00:00Z",
          updatedAt: "2026-10-02T10:00:00Z",
        },
      },
    ]);

    await user.click(await screen.findByTestId("bind-account-button"));

    // The organization level hides the override pickers (PRD 25's chain
    // levels are the dialog's three states).
    expect(screen.queryByRole("combobox", { name: /Application/ })).toBeNull();

    await user.type(screen.getByRole("textbox", { name: "Provider" }), "apple");
    await user.type(screen.getByRole("textbox", { name: "Account name" }), "Org Apple Account");
    await user.type(screen.getByLabelText(/Secret material/), "the-org-key");
    await user.click(screen.getByTestId("bind-submit"));

    // Material rides exactly once, on the PUT, and never comes back.
    await waitFor(() => {
      const body = bodyOf("/api/v1/distribution/credentials", "PUT");
      expect(body).toMatchObject({
        organizationId: ORG_ID,
        applicationId: null,
        environmentId: null,
        provider: "apple",
        name: "Org Apple Account",
        secretMaterial: "the-org-key",
      });
    });

    // Choosing the application+environment level reveals both pickers.
    await user.click(await screen.findByTestId("bind-account-button"));
    chooseSelectOption("Level", "Application + environment override");
    await waitFor(() => {
      expect(screen.queryByRole("combobox", { name: /Application/ })).not.toBeNull();
      expect(screen.queryByRole("combobox", { name: /Environment/ })).not.toBeNull();
    });
  });


  it("authors a listing locale through the metadata dialog and removes a row", async () => {
    renderPage([
      {
        method: "GET",
        url: "/api/v1/applications",
        status: 200,
        body: {
          items: [
            {
              id: "30000000-0000-4000-8000-000000000001",
              name: "Banking",
              status: "ACTIVE",
            },
          ],
        },
      },
      {
        method: "PUT",
        url: "/api/v1/distribution/appstore/metadata",
        status: 200,
        body: {
          id: "50000000-0000-4000-8000-000000000002",
          organizationId: ORG_ID,
          applicationId: "30000000-0000-4000-8000-000000000001",
          channel: "appstore",
          locale: "de-DE",
          description: "Die Banking-App.",
          keywords: null,
          screenshotRefs: [],
          releaseNotes: null,
          createdAt: "2026-10-02T10:00:00Z",
          updatedAt: "2026-10-02T10:00:00Z",
        },
      },
      {
        method: "DELETE",
        url: "/api/v1/distribution/appstore/metadata/50000000-0000-4000-8000-000000000001",
        status: 204,
        body: undefined,
      },
    ]);

    expect((await screen.findByTestId("metadata-en-US")).textContent).toContain("banking,pilot");

    await user.click(await screen.findByTestId("add-metadata-button"));
    chooseSelectOption("Application", "Banking");
    await user.clear(screen.getByRole("textbox", { name: /Locale/ }));
    await user.type(screen.getByRole("textbox", { name: /Locale/ }), "de-DE");
    await user.type(screen.getByRole("textbox", { name: /Description/ }), "Die Banking-App.");
    await user.click(screen.getByTestId("save-metadata-button"));

    await waitFor(() => {
      const body = bodyOf("/api/v1/distribution/appstore/metadata", "PUT");
      expect(body).toMatchObject({
        organizationId: ORG_ID,
        applicationId: "30000000-0000-4000-8000-000000000001",
        locale: "de-DE",
        description: "Die Banking-App.",
      });
    });

    await user.click(screen.getByLabelText("remove en-US metadata"));
    await waitFor(() => {
      expect(
        fetchCalls().some(
          ([url, init]) =>
            url.includes("/distribution/appstore/metadata/50000000-0000-4000-8000-000000000001") &&
            init?.method === "DELETE",
        ),
      ).toBe(true);
    });
  });

  it("answers the verify probe and removes a binding through its id", async () => {
    renderPage([
      {
        method: "POST",
        url: `/api/v1/distribution/credentials/${ACCOUNT_ID}/verify`,
        status: 200,
        body: { resolved: true },
      },
      {
        method: "DELETE",
        url: `/api/v1/distribution/credentials/${ACCOUNT_ID}`,
        status: 204,
        body: undefined,
      },
    ]);

    await user.click(await screen.findByText("Verify"));
    await waitFor(() => {
      expect(fetchCalls().some(([url]) => url.includes(`/verify?organizationId=${ORG_ID}`))).toBe(true);
    });

    await user.click(screen.getByLabelText("remove apple organization"));
    await waitFor(() => {
      expect(
        fetchCalls().some(([url, init]) => url.includes(`/credentials/${ACCOUNT_ID}`) && init?.method === "DELETE"),
      ).toBe(true);
    });
  });
});
