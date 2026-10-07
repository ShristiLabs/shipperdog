/** Login screen tests (issue #35): success signs in and lands on the
 * portal home; failures keep the API's opaque message (suspended or
 * deactivated testers are indistinguishable from bad passwords). */
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import App from "../App";
import { tokenStore } from "../auth/tokenStore";
import { installFetchRouter, renderPortal } from "../test/harness";

beforeEach(() => {
  tokenStore.clear();
});

afterEach(() => {
  tokenStore.clear();
  vi.unstubAllGlobals();
});

function loginOkRouter(): void {
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
}

function loginFailsRouter(): void {
  installFetchRouter([
    {
      method: "POST",
      url: "/api/v1/auth/login",
      status: 401,
      body: {
        title: "Unauthorized",
        status: 401,
        code: "INVALID_CREDENTIALS",
        detail: "email or password is incorrect",
      },
    },
  ]);
}

describe("LoginPage", () => {
  it("signs an activated tester in and lands on the portal home", async () => {
    loginOkRouter();
    renderPortal(<App />, { route: "/login" });

    await userEvent.type(screen.getByTestId("login-email"), "ana.tester@example.com");
    await userEvent.type(screen.getByTestId("login-password"), "correct-horse-battery");
    await userEvent.click(screen.getByTestId("login-submit"));

    await waitFor(() => expect(screen.getByTestId("portal-home-title")).toBeInTheDocument());
    expect(screen.getByTestId("portal-home-empty")).toBeInTheDocument();
    expect(tokenStore.get()?.accessToken).toBe("tester-access");
  });

  it("shows the opaque failure for wrong credentials and stays on /login", async () => {
    loginFailsRouter();
    renderPortal(<App />, { route: "/login" });

    await userEvent.type(screen.getByTestId("login-email"), "ana.tester@example.com");
    await userEvent.type(screen.getByTestId("login-password"), "wrong-password");
    await userEvent.click(screen.getByTestId("login-submit"));

    await waitFor(() => expect(screen.getByTestId("login-error")).toBeInTheDocument());
    expect(screen.getByTestId("login-error").textContent).toContain("Sign-in failed");
    expect(screen.queryByTestId("portal-home-empty")).not.toBeInTheDocument();
    expect(tokenStore.get()).toBeNull();
  });

  it("suspended and deactivated testers get the same generic failure shape", async () => {
    // The API deliberately answers suspended/deactivated logins with the
    // identical 401 INVALID_CREDENTIALS problem (docs/authentication.md);
    // the portal must render it exactly like a wrong password.
    loginFailsRouter();
    renderPortal(<App />, { route: "/login" });

    await userEvent.type(screen.getByTestId("login-email"), "suspended.tester@example.com");
    await userEvent.type(screen.getByTestId("login-password"), "their-real-password");
    await userEvent.click(screen.getByTestId("login-submit"));

    await waitFor(() => expect(screen.getByTestId("login-error")).toBeInTheDocument());
    expect(screen.getByTestId("login-error").textContent).not.toContain("suspended");
    expect(tokenStore.get()).toBeNull();
  });

  it("links to the invitation-accept entry", () => {
    installFetchRouter([]);
    renderPortal(<App />, { route: "/login" });
    expect(screen.getByTestId("login-accept-link")).toHaveAttribute("href", "/accept-invitation");
  });
});
