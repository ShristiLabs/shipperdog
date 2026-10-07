/** Route-guard and shell tests (issue #35): anonymous deep links bounce
 * to /login and come back after sign-in; a stored session restores the
 * shell; logout clears local state and returns to the login screen. */
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import App from "./App";
import { tokenStore } from "./auth/tokenStore";
import { ensureFreshTokens } from "./api/client";
import { installFetchRouter, recordedRequests, renderPortal, seedSession } from "./test/harness";

beforeEach(() => {
  tokenStore.clear();
});

afterEach(() => {
  tokenStore.clear();
  vi.unstubAllGlobals();
});

describe("route guard", () => {
  it("bounces an anonymous visitor to /login with a from marker", () => {
    installFetchRouter([]);
    renderPortal(<App />, { route: "/" });
    expect(screen.getByTestId("portal-login-card")).toBeInTheDocument();
    expect(screen.queryByTestId("portal-home-empty")).not.toBeInTheDocument();
  });

  it("restores a stored session on reload and renders the shell", async () => {
    installFetchRouter([]);
    seedSession({ fullName: "Ana Tester" });
    renderPortal(<App />, { route: "/" });
    await waitFor(() => expect(screen.getByTestId("portal-home-title")).toBeInTheDocument());
    expect(screen.getByTestId("portal-home-empty")).toBeInTheDocument();
  });

  it("a dead stored session drops to anonymous, not an error screen", async () => {
    tokenStore.save("access", "refresh", 900);
    installFetchRouter([
      {
        method: "GET",
        url: "/api/v1/auth/me",
        status: 401,
        body: { title: "Unauthorized", status: 401, code: "UNAUTHENTICATED" },
      },
      {
        method: "POST",
        url: "/api/v1/auth/refresh",
        status: 401,
        body: { title: "Unauthorized", status: 401, code: "INVALID_REFRESH_TOKEN" },
      },
    ]);
    renderPortal(<App />, { route: "/" });
    await waitFor(() => expect(screen.getByTestId("portal-login-card")).toBeInTheDocument());
    expect(tokenStore.get()).toBeNull();
  });
});

describe("authenticated shell", () => {
  it("shows the tester identity and the honest My Applications placeholder", async () => {
    installFetchRouter([]);
    seedSession({ fullName: "Ana Tester" });
    renderPortal(<App />, { route: "/" });
    await waitFor(() => expect(screen.getByTestId("portal-user")).toBeInTheDocument());
    expect(screen.getByTestId("portal-user").textContent).toContain("Ana Tester");
    expect(screen.getByTestId("portal-home-empty").textContent).toContain("My Applications");
  });

  it("logout revokes the refresh token, clears local state, and returns to login", async () => {
    installFetchRouter([{ method: "POST", url: "/api/v1/auth/logout", status: 204 }]);
    // seedSession unshifts the /auth/me route onto the live table and
    // stores the session — exactly the authenticated-shell start state.
    seedSession({ fullName: "Ana Tester" });
    renderPortal(<App />, { route: "/" });
    await waitFor(() => expect(screen.getByTestId("portal-logout")).toBeInTheDocument());
    await userEvent.click(screen.getByTestId("portal-logout"));

    await waitFor(() => expect(screen.getByTestId("portal-login-card")).toBeInTheDocument());
    expect(tokenStore.get()).toBeNull();
    expect(recordedRequests.find((call) => call.path === "/api/v1/auth/logout")).toBeDefined();
  });

  it("logout still ends the local session when the revocation endpoint errors", async () => {
    // Revocation is best-effort: a 500 on /auth/logout must not strand the
    // tester in a local session (the family dies on its own TTL anyway).
    installFetchRouter([
      {
        method: "POST",
        url: "/api/v1/auth/logout",
        status: 500,
        body: { title: "Internal error", status: 500, code: "INTERNAL_ERROR" },
      },
    ]);
    seedSession({ fullName: "Ana Tester" });
    renderPortal(<App />, { route: "/" });
    await waitFor(() => expect(screen.getByTestId("portal-logout")).toBeInTheDocument());
    await userEvent.click(screen.getByTestId("portal-logout"));

    await waitFor(() => expect(screen.getByTestId("portal-login-card")).toBeInTheDocument());
    expect(tokenStore.get()).toBeNull();
  });

  it("a failed mid-session refresh bounces the authenticated tester to login", async () => {
    // The session dies while the tester is in the app (revoked refresh
    // token): the expiry event must drop the shell to anonymous, not
    // leave a signed-out user staring at authenticated screens.
    installFetchRouter([
      {
        method: "POST",
        url: "/api/v1/auth/refresh",
        status: 401,
        body: { title: "Unauthorized", status: 401, code: "INVALID_REFRESH_TOKEN" },
      },
    ]);
    seedSession({ fullName: "Ana Tester" });
    renderPortal(<App />, { route: "/" });
    await waitFor(() => expect(screen.getByTestId("portal-home-empty")).toBeInTheDocument());

    const refreshed = await ensureFreshTokens();
    expect(refreshed).toBe(false);
    await waitFor(() => expect(screen.getByTestId("portal-login-card")).toBeInTheDocument());
    expect(screen.queryByTestId("portal-home-empty")).not.toBeInTheDocument();
    expect(tokenStore.get()).toBeNull();
  });
});
