/** Catalog screen tests (issue #36, S8-02): the server-computed
 * catalog's portal states — loading, the honest deny-by-default empty,
 * the problem+json error card, cursor paging through Load more, manual
 * refresh, and the anonymous redirect to login. */
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { act, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import App from "../App";
import { tokenStore } from "../auth/tokenStore";
import { installFetchRouter, unmatchedRequests, renderPortal, seedSession } from "../test/harness";

const CATALOG_URL = "/api/v1/authorization/decisions/applications";

function card(id: string, name: string, versionName: string, platforms: string[] = ["ANDROID"]) {
  const versionId = `40000000-0000-4000-8000-${id.slice(-12)}`;
  return {
    tenantId: "30000000-0000-4000-8000-000000000030",
    application: { applicationId: id, name, status: "ACTIVE" },
    latestAuthorizedVersion: { versionId, versionName, status: "DRAFT" },
    platforms: platforms.map((platform) => ({
      platform,
      tenantId: "30000000-0000-4000-8000-000000000030",
      versionId,
      versionName,
    })),
  };
}

const BANKING = card("10000000-0000-4000-8000-000000000001", "Banking", "7.12.0", ["IOS", "ANDROID"]);
const PAYMENTS = card("20000000-0000-4000-8000-000000000002", "Payments", "4.8.1");

beforeEach(() => {
  tokenStore.clear();
});

afterEach(() => {
  tokenStore.clear();
  vi.unstubAllGlobals();
});

describe("HomePage catalog", () => {
  it("shows the loading state until the catalog resolves, then the authorized cards", async () => {
    installFetchRouter([]);
    let releaseCatalog!: () => void;
    const gate = new Promise<void>((resolveGate) => {
      releaseCatalog = resolveGate;
    });
    const realFetch = globalThis.fetch;
    vi.stubGlobal(
      "fetch",
      vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const url = String(input);
        if (url.startsWith("/api/v1/authorization/decisions/applications")) {
          await gate;
          return new Response(
            JSON.stringify({ effectiveAccess: true, items: [BANKING] }),
            { status: 200, headers: { "Content-Type": "application/json" } },
          );
        }
        return realFetch(input, init);
      }),
    );
    seedSession();
    renderPortal(<App />);

    await waitFor(() => expect(screen.getByTestId("catalog-loading")).toBeInTheDocument());

    await act(async () => {
      releaseCatalog();
    });
    await waitFor(() => expect(screen.getByTestId("catalog-list")).toBeInTheDocument());

    const banking = screen.getByTestId(`catalog-card-${BANKING.application.applicationId}`);
    expect(banking).toHaveTextContent("Banking");
    expect(banking).toHaveTextContent("7.12.0");
    expect(screen.getByTestId("catalog-platform-IOS")).toHaveTextContent("iOS");
    expect(screen.getByTestId("catalog-platform-ANDROID")).toHaveTextContent("Android");
    expect(screen.queryByTestId("catalog-load-more")).not.toBeInTheDocument();
  });

  it("renders the honest empty catalog for a grantless tester (deny-by-default, not an error)", async () => {
    installFetchRouter([
      { method: "GET", url: CATALOG_URL, status: 200, body: { effectiveAccess: true, items: [] } },
    ]);
    seedSession();
    renderPortal(<App />);

    await waitFor(() => expect(screen.getByTestId("catalog-empty")).toBeInTheDocument());
    expect(screen.getByTestId("catalog-empty")).toHaveTextContent("Nothing is shared with you yet");
    expect(screen.queryByTestId("catalog-error")).not.toBeInTheDocument();
  });

  it("renders the problem envelope with a retry when the catalog call fails", async () => {
    installFetchRouter([
      {
        method: "GET",
        url: CATALOG_URL,
        sequence: [
          {
            status: 500,
            body: { title: "Internal Server Error", status: 500, code: "INTERNAL_ERROR", detail: "boom" },
          },
          { status: 200, body: { effectiveAccess: true, items: [BANKING] } },
        ],
      },
    ]);
    seedSession();
    renderPortal(<App />);

    await waitFor(() => expect(screen.getByTestId("catalog-error")).toBeInTheDocument());
    expect(screen.getByTestId("catalog-error-detail").textContent).toContain("INTERNAL_ERROR");

    await userEvent.click(screen.getByTestId("catalog-error-retry"));
    await waitFor(() => expect(screen.getByTestId("catalog-list")).toBeInTheDocument());
  });

  it("pages the catalog through Load more on the cursor envelope", async () => {
    installFetchRouter([
      {
        method: "GET",
        url: CATALOG_URL,
        sequence: [
          { status: 200, body: { effectiveAccess: true, items: [BANKING], next_cursor: "MQ" } },
          { status: 200, body: { effectiveAccess: true, items: [PAYMENTS] } },
        ],
      },
    ]);
    seedSession();
    renderPortal(<App />);

    await waitFor(() => expect(screen.getByTestId("catalog-list")).toBeInTheDocument());
    expect(screen.getByText("Banking")).toBeInTheDocument();
    expect(screen.queryByText("Payments")).not.toBeInTheDocument();

    await userEvent.click(screen.getByTestId("catalog-load-more"));
    await waitFor(() => expect(screen.getByText("Payments")).toBeInTheDocument());
    expect(screen.getByText("Banking")).toBeInTheDocument();
    // The last page carries no cursor: the button disappears.
    await waitFor(() => expect(screen.queryByTestId("catalog-load-more")).not.toBeInTheDocument());
  });

  it("refresh revalidates the catalog", async () => {
    installFetchRouter([
      {
        method: "GET",
        url: CATALOG_URL,
        sequence: [
          { status: 200, body: { effectiveAccess: true, items: [BANKING] } },
          {
            status: 200,
            body: {
              effectiveAccess: true,
              items: [BANKING, PAYMENTS],
              next_cursor: "MQ",
            },
          },
          { status: 200, body: { effectiveAccess: true, items: [PAYMENTS] } },
        ],
      },
    ]);
    seedSession();
    renderPortal(<App />);

    await waitFor(() => expect(screen.getByTestId("catalog-list")).toBeInTheDocument());
    expect(screen.queryByText("Payments")).not.toBeInTheDocument();

    await userEvent.click(screen.getByTestId("catalog-refresh"));
    await waitFor(() => expect(screen.getByText("Payments")).toBeInTheDocument());
  });

  it("shows the resolved version on a platform chip when it differs from the latest authorized version", async () => {
    // The latest authorized version is IOS-only; ANDROID resolves to an
    // older authorized build — the chip must say which version installs.
    const latestVersionId = "40000000-0000-4000-8000-000000000009";
    const androidVersionId = "40000000-0000-4000-8000-000000000008";
    const splitCard = {
      tenantId: "30000000-0000-4000-8000-000000000030",
      application: { applicationId: "10000000-0000-4000-8000-000000000009", name: "Wallet", status: "ACTIVE" },
      latestAuthorizedVersion: { versionId: latestVersionId, versionName: "7.12.0", status: "DRAFT" },
      platforms: [
        { platform: "IOS", tenantId: "30000000-0000-4000-8000-000000000030", versionId: latestVersionId, versionName: "7.12.0" },
        {
          platform: "ANDROID",
          tenantId: "30000000-0000-4000-8000-000000000030",
          versionId: androidVersionId,
          versionName: "7.11.0",
        },
      ],
    };
    installFetchRouter([
      { method: "GET", url: CATALOG_URL, status: 200, body: { effectiveAccess: true, items: [splitCard] } },
    ]);
    seedSession();
    renderPortal(<App />);

    await waitFor(() => expect(screen.getByTestId("catalog-list")).toBeInTheDocument());
    expect(screen.getByTestId("catalog-version").textContent).toBe("7.12.0");
    expect(screen.getByTestId("catalog-platform-IOS").textContent).toBe("iOS");
    expect(screen.getByTestId("catalog-platform-ANDROID").textContent).toContain("7.11.0");
  });

  it("redirects an anonymous visitor to login instead of fetching the catalog", async () => {
    installFetchRouter([]);
    renderPortal(<App />);

    // The router renders the login screen; the catalog is never fetched.
    await waitFor(() => expect(screen.getByTestId("login-email")).toBeInTheDocument());
    expect(unmatchedRequests.filter((request) => request.includes(CATALOG_URL))).toHaveLength(0);
  });
});
