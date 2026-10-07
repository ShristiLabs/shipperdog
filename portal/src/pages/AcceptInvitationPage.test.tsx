/** Invitation-accept tests (issue #35): the entry point behind every
 * invitation link — set password via /auth/register, then the standard
 * login, landing on the portal home. Expired/used tokens surface the
 * problem+json envelope honestly; client validation mirrors the API. */
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import App from "../App";
import { tokenStore } from "../auth/tokenStore";
import { installFetchRouter, recordedRequests, renderPortal } from "../test/harness";

beforeEach(() => {
  tokenStore.clear();
});

afterEach(() => {
  tokenStore.clear();
  vi.unstubAllGlobals();
});

const ME_OK = {
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
};

function acceptOkRouter(): void {
  installFetchRouter([
    { method: "POST", url: "/api/v1/auth/register", status: 201, body: { id: "u1" } },
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
    ME_OK,
  ]);
}

async function fillAcceptForm(): Promise<void> {
  await userEvent.type(screen.getByTestId("accept-name"), "Ana Tester");
  await userEvent.type(screen.getByTestId("accept-email"), "ana.tester@example.com");
  await userEvent.type(screen.getByTestId("accept-password"), "correct-horse-battery");
  await userEvent.type(screen.getByTestId("accept-confirm"), "correct-horse-battery");
}

describe("AcceptInvitationPage", () => {
  it("activates the invitation and signs the tester in", async () => {
    acceptOkRouter();
    renderPortal(<App />, { route: "/accept-invitation?token=invite-token-1" });

    await fillAcceptForm();
    await userEvent.click(screen.getByTestId("accept-submit"));

    await waitFor(() => expect(screen.getByTestId("portal-home-title")).toBeInTheDocument());
    expect(tokenStore.get()?.accessToken).toBe("tester-access");

    const register = recordedRequests.find((call) => call.path === "/api/v1/auth/register");
    expect(register).toBeDefined();
    const login = recordedRequests.find((call) => call.path === "/api/v1/auth/login");
    expect(login).toBeDefined();
  });

  it("renders the honest missing-token card without a token", () => {
    installFetchRouter([]);
    renderPortal(<App />, { route: "/accept-invitation" });
    expect(screen.getByTestId("accept-missing-token")).toBeInTheDocument();
    expect(screen.queryByTestId("accept-card")).not.toBeInTheDocument();
  });

  it("surfaces expired or already-used invitations from the problem envelope", async () => {
    installFetchRouter([
      {
        method: "POST",
        url: "/api/v1/auth/register",
        status: 400,
        body: {
          title: "Invalid invitation",
          status: 400,
          code: "INVALID_INVITATION_TOKEN",
          detail: "invitation token is expired, revoked, or already used",
        },
      },
    ]);
    renderPortal(<App />, { route: "/accept-invitation?token=stale-token" });

    await fillAcceptForm();
    await userEvent.click(screen.getByTestId("accept-submit"));

    await waitFor(() => expect(screen.getByTestId("accept-error")).toBeInTheDocument());
    expect(screen.getByTestId("accept-error").textContent).toContain("expired");
    expect(tokenStore.get()).toBeNull();
    expect(screen.queryByTestId("portal-home-title")).not.toBeInTheDocument();
  });

  it("fails fast on client validation that mirrors the API (mismatch, short password)", async () => {
    installFetchRouter([]);
    renderPortal(<App />, { route: "/accept-invitation?token=invite-token-1" });

    await userEvent.type(screen.getByTestId("accept-name"), "Ana Tester");
    await userEvent.type(screen.getByTestId("accept-email"), "ana.tester@example.com");
    await userEvent.type(screen.getByTestId("accept-password"), "short");
    await userEvent.type(screen.getByTestId("accept-confirm"), "different");
    await userEvent.click(screen.getByTestId("accept-submit"));

    await waitFor(() => expect(screen.getByText("Passwords do not match.")).toBeInTheDocument());
    expect(screen.getByText("Password must be 12-128 characters.")).toBeInTheDocument();
    // The server is the authority and was never asked.
    expect(recordedRequests).toHaveLength(0);
  });
});
