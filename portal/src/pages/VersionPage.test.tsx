/** Version-detail deep-link tests (issue #37, S8-03, AC5): the
 * shareable version URL renders the authorized version's badge and
 * platform rows, and every miss — unknown id, unauthorized id — is
 * the SAME opaque 404 page (PRD 12: no existence leak). */
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import App from "../App";
import { tokenStore } from "../auth/tokenStore";
import {
  historyResponseBody,
  installFetchRouter,
  renderPortal,
  seedHistoryProbe,
  seedSession,
} from "../test/harness";

const TENANT = "30000000-0000-4000-8000-000000000030";
const APP_ID = "10000000-0000-4000-8000-000000000001";
const VERSION = "40000000-0000-4000-8000-000000000012";
const VERSION_ROUTE = `/applications/${TENANT}/${APP_ID}/versions/${VERSION}`;

const ENTRY = {
  versionId: VERSION,
  versionName: "7.12.0",
  status: "ARCHIVED",
  expiresAt: null,
  platforms: [
    {
      platform: "ANDROID",
      artifactId: "50000000-0000-4000-8000-000000000002",
      buildNumber: 842,
      sizeBytes: 9_437_184,
      uploadedAt: "2026-10-05T12:05:00Z",
    },
  ],
};

beforeEach(() => {
  tokenStore.clear();
});

afterEach(() => {
  tokenStore.clear();
  vi.unstubAllGlobals();
});

describe("VersionPage deep link", () => {
  it("opens the report dialog prefilled when the SDK handoff arrives as ?report=1&hint=", async () => {
    installFetchRouter([]);
    seedHistoryProbe(200, [ENTRY]);
    seedSession();
    renderPortal(<App />, { route: `${VERSION_ROUTE}?report=1&hint=${encodeURIComponent("crash near checkout")}` });

    // The S15-01 feedback handoff (issue #72): the dialog opened
    // itself with the capped crash summary in the description —
    // the tester edits and submits; nothing posts itself.
    const description = await screen.findByTestId("feedback-description");
    expect(description).toHaveValue("crash near checkout");
    expect(screen.getByTestId("feedback-title")).toBeInTheDocument();
  });
  it("renders the authorized version with its badge and platform rows", async () => {
    installFetchRouter([]);
    seedHistoryProbe(200, [ENTRY]);
    seedSession();
    renderPortal(<App />, { route: VERSION_ROUTE });

    await waitFor(() => expect(screen.getByTestId(`history-entry-${VERSION}`)).toBeInTheDocument());
    expect(screen.getByTestId("version-title")).toHaveTextContent("Banking");
    expect(screen.getByTestId("history-entry-name")).toHaveTextContent("7.12.0");
    expect(screen.getByTestId("history-entry-status")).toHaveTextContent("Archived");
    expect(screen.getByTestId(`history-slot-${VERSION}-ANDROID`)).toHaveTextContent("Build 842");
  });

  it("renders the opaque 404 page for an unauthorized or unknown version id", async () => {
    installFetchRouter([]);
    seedHistoryProbe(404);
    seedSession();
    renderPortal(<App />, { route: VERSION_ROUTE });

    await waitFor(() => expect(screen.getByTestId("not-found-page")).toBeInTheDocument());
    // The page carries no reason, no code, no trace of WHICH check
    // failed — unknown and unauthorized are indistinguishable.
    expect(screen.getByTestId("not-found-page")).toHaveTextContent(
      "This page does not exist, or you are not authorized to view it.",
    );
    expect(screen.queryByTestId("history-entry-name")).not.toBeInTheDocument();
  });

  it("returns an unauthenticated deep link to the version page after login", async () => {
    // The shareable-URL flow end to end: no session, RequireAuth parks
    // the deep link in the from-marker, login restores it — the #36
    // deferral that became load-bearing the moment version URLs exist.
    installFetchRouter([
      {
        method: "POST",
        url: "/api/v1/auth/login",
        status: 200,
        body: {
          accessToken: "tester-access",
          refreshToken: "tester-refresh",
          tokenType: "Bearer",
          expiresInSeconds: 900,
        },
      },
      {
        method: "GET",
        url: "/api/v1/auth/me",
        status: 200,
        body: {
          id: "20000000-0000-4000-8000-000000000020",
          email: "ana.tester@example.com",
          fullName: "Ana Tester",
          status: "ACTIVE",
          orgId: null,
          tenantId: null,
          authenticatedVia: "PASSWORD",
        },
      },
    ]);
    seedHistoryProbe(200, [ENTRY]);
    renderPortal(<App />, { route: VERSION_ROUTE });

    await screen.findByTestId("login-email");
    await userEvent.type(screen.getByTestId("login-email"), "ana.tester@example.com");
    await userEvent.type(screen.getByTestId("login-password"), "correct-horse-battery");
    await userEvent.click(screen.getByTestId("login-submit"));

    await waitFor(() => expect(screen.getByTestId(`history-entry-${VERSION}`)).toBeInTheDocument());
    expect(screen.getByTestId("version-title")).toHaveTextContent("Banking");
  });

  it("renders the problem envelope with a retry on a transport failure", async () => {
    installFetchRouter([
      {
        method: "GET",
        url: "/api/v1/authorization/decisions/eligibility",
        sequence: [
          { status: 503, body: { title: "Service Unavailable", status: 503, code: "UNAVAILABLE" } },
          { status: 200, body: historyResponseBody([ENTRY]) },
        ],
      },
    ]);
    seedSession();
    renderPortal(<App />, { route: VERSION_ROUTE });

    await waitFor(() => expect(screen.getByTestId("version-error")).toBeInTheDocument());

    await userEvent.click(screen.getByTestId("version-error-retry"));
    await waitFor(() => expect(screen.getByTestId(`history-entry-${VERSION}`)).toBeInTheDocument());
  });
});
