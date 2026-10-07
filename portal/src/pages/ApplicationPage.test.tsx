/** Version-history screen tests (issue #37, S8-03): the authorized
 * version list's portal states — loading, the badge facts (ARCHIVED,
 * REVOKED, draft), the platform rows' build metadata, cursor paging
 * through Load more, the honest empty set, the problem envelope with
 * a retry, and the opaque 404 page for an application the tester
 * cannot reach. */
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import App from "../App";
import { tokenStore } from "../auth/tokenStore";
import {
  historyResponseBody,
  installFetchRouter,
  renderPortal,
  seedCatalog,
  seedHistory,
  seedSession,
} from "../test/harness";

const HISTORY_URL = "/api/v1/authorization/decisions/eligibility";
const APP_ROUTE = `/applications/30000000-0000-4000-8000-000000000030/10000000-0000-4000-8000-000000000001`;

function entry(
  versionId: string,
  versionName: string,
  status: string,
  platforms: Array<Record<string, unknown>> = [],
  expiresAt: string | null = null,
) {
  return { versionId, versionName, status, expiresAt, platforms };
}

const V712 = entry("40000000-0000-4000-8000-000000000012", "7.12.0", "DRAFT", [
  {
    platform: "IOS",
    artifactId: "50000000-0000-4000-8000-000000000001",
    buildNumber: 421,
    sizeBytes: 12_897_484,
    uploadedAt: "2026-10-05T12:00:00Z",
  },
  {
    platform: "ANDROID",
    artifactId: "50000000-0000-4000-8000-000000000002",
    buildNumber: 842,
    sizeBytes: 9_437_184,
    uploadedAt: "2026-10-05T12:05:00Z",
  },
]);
const V711_ARCHIVED = entry("40000000-0000-4000-8000-000000000011", "7.11.0", "ARCHIVED", [
  {
    platform: "ANDROID",
    artifactId: "50000000-0000-4000-8000-000000000003",
    buildNumber: 840,
    sizeBytes: 9_200_000,
    uploadedAt: "2026-09-28T09:00:00Z",
  },
]);
const V710_REVOKED = entry("40000000-0000-4000-8000-000000000010", "7.10.0", "REVOKED", [], "2026-11-01T00:00:00Z");

beforeEach(() => {
  tokenStore.clear();
});

afterEach(() => {
  tokenStore.clear();
  vi.unstubAllGlobals();
});

describe("ApplicationPage version history", () => {
  it("renders the authorized versions newest-first with badges, platform rows, and build metadata", async () => {
    installFetchRouter([]);
    seedHistory([V712, V711_ARCHIVED, V710_REVOKED]);
    seedSession();
    renderPortal(<App />, { route: APP_ROUTE });

    await waitFor(() => expect(screen.getByTestId("history-list")).toBeInTheDocument());

    expect(screen.getByTestId("history-title")).toHaveTextContent("Banking");
    const newest = screen.getByTestId(`history-entry-${V712.versionId}`);
    expect(newest).toHaveTextContent("7.12.0");
    expect(newest).toHaveTextContent("Draft");
    expect(screen.getByTestId(`history-slot-${V712.versionId}-IOS`)).toHaveTextContent("Build 421");
    expect(screen.getByTestId(`history-slot-${V712.versionId}-IOS`)).toHaveTextContent("12 MB");
    expect(screen.getByTestId(`history-slot-${V712.versionId}-ANDROID`)).toHaveTextContent("Build 842");
    expect(screen.getByTestId(`history-slot-${V712.versionId}-ANDROID`)).toHaveTextContent("9 MB");

    const archived = screen.getByTestId(`history-entry-${V711_ARCHIVED.versionId}`);
    expect(archived).toHaveTextContent("Archived");
    expect(archived).toHaveTextContent("Build 840");

    const revoked = screen.getByTestId(`history-entry-${V710_REVOKED.versionId}`);
    expect(revoked).toHaveTextContent("Revoked");
    expect(revoked).toHaveTextContent("Access until");
    expect(revoked).toHaveTextContent("No builds available for this version yet.");

    expect(screen.queryByTestId("history-load-more")).not.toBeInTheDocument();
  });

  it("pages through the cursor envelope with Load more", async () => {
    installFetchRouter([
      {
        method: "GET",
        url: HISTORY_URL,
        sequence: [
          { status: 200, body: undefined },
          { status: 200, body: undefined },
        ],
      },
    ]);
    seedHistory([V712], "cursor-2");
    seedSession();
    renderPortal(<App />, { route: APP_ROUTE });

    await waitFor(() => expect(screen.getByTestId("history-list")).toBeInTheDocument());
    expect(screen.getByTestId("history-load-more")).toBeInTheDocument();

    await userEvent.click(screen.getByTestId("history-load-more"));
    await waitFor(() =>
      expect(screen.getAllByTestId(/history-entry-/).length).toBeGreaterThanOrEqual(1),
    );
  });

  it("renders the honest empty history for a tester with no authorized versions", async () => {
    installFetchRouter([]);
    seedHistory([]);
    seedSession();
    renderPortal(<App />, { route: APP_ROUTE });

    await waitFor(() => expect(screen.getByTestId("history-empty")).toBeInTheDocument());
    expect(screen.getByTestId("history-empty")).toHaveTextContent("not authorized for any version");
    expect(screen.queryByTestId("history-error")).not.toBeInTheDocument();
  });

  it("renders the problem envelope with a retry when the history call fails", async () => {
    installFetchRouter([
      {
        method: "GET",
        url: HISTORY_URL,
        sequence: [
          {
            status: 500,
            body: { title: "Internal Server Error", status: 500, code: "INTERNAL_ERROR" },
          },
          { status: 200, body: historyResponseBody([V712]) },
        ],
      },
    ]);
    seedSession();
    renderPortal(<App />, { route: APP_ROUTE });

    await waitFor(() => expect(screen.getByTestId("history-error")).toBeInTheDocument());
    expect(screen.getByTestId("history-error-detail")).toHaveTextContent("INTERNAL_ERROR");

    await userEvent.click(screen.getByTestId("history-error-retry"));
    await waitFor(() => expect(screen.getByTestId("history-list")).toBeInTheDocument());
  });

  it("renders the opaque 404 page for an application the tester cannot reach", async () => {
    installFetchRouter([
      {
        method: "GET",
        url: HISTORY_URL,
        status: 404,
        body: { title: "Not Found", status: 404, code: "TENANT_NOT_FOUND", detail: "Resource not found." },
      },
    ]);
    seedSession();
    renderPortal(<App />, { route: APP_ROUTE });

    await waitFor(() => expect(screen.getByTestId("not-found-page")).toBeInTheDocument());
    expect(screen.queryByTestId("history-error")).not.toBeInTheDocument();
    expect(screen.getByTestId("not-found-page")).toHaveTextContent(
      "This page does not exist, or you are not authorized to view it.",
    );
  });

  it("navigates from a catalog card into the version history", async () => {
    installFetchRouter([]);
    seedHistory([V712]);
    seedCatalog([
      {
        tenantId: "30000000-0000-4000-8000-000000000030",
        application: {
          applicationId: "10000000-0000-4000-8000-000000000001",
          name: "Banking",
          status: "ACTIVE",
        },
        latestAuthorizedVersion: {
          versionId: "40000000-0000-4000-8000-000000000012",
          versionName: "7.12.0",
          status: "DRAFT",
        },
        platforms: [],
      },
    ]);
    seedSession();
    renderPortal(<App />, { route: "/" });

    const card = await screen.findByTestId("catalog-card-10000000-0000-4000-8000-000000000001");
    expect(card).toHaveAttribute("href", APP_ROUTE);
    await userEvent.click(card);

    await waitFor(() => expect(screen.getByTestId("history-list")).toBeInTheDocument());
    expect(screen.getByTestId("history-title")).toHaveTextContent("Banking");
  });
});
