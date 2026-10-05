/** Login screen behavior (issue #12): session bootstrap, opaque failure
 * display, and post-login navigation. */
import { cleanup, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { Route, Routes } from "react-router-dom";
import LoginPage from "./LoginPage";
import { installFetchRouter, renderConsole } from "../test/harness";
import { tokenStore } from "../auth/tokenStore";

const user = userEvent.setup();

beforeEach(() => {
  tokenStore.clear();
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe("LoginPage", () => {
  it("signs in, stores the token pair, and navigates to the console", async () => {
    installFetchRouter([
      {
        method: "POST",
        url: "/api/v1/auth/login",
        status: 200,
        body: {
          accessToken: "fresh-access",
          refreshToken: "fresh-refresh",
          tokenType: "Bearer",
          expiresInSeconds: 900,
        },
      },
      {
        method: "GET",
        url: "/api/v1/auth/me",
        status: 200,
        body: {
          id: "10000000-0000-4000-8000-000000000010",
          email: "dev@shipperdog.local",
          fullName: "Dev Admin",
          status: "ACTIVE",
          orgId: "10000000-0000-4000-8000-000000000001",
          tenantId: null,
          authenticatedVia: "PASSWORD",
        },
      },
    ]);
    renderConsole(
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route path="/tenants" element={<div data-testid="reached-tenants" />} />
      </Routes>,
      { route: "/login" },
    );

    await user.type(screen.getByTestId("login-email"), "dev@shipperdog.local");
    await user.type(screen.getByTestId("login-password"), "shipperdog-dev-admin");
    await user.click(screen.getByTestId("login-submit"));

    expect(await screen.findByTestId("reached-tenants")).toBeInTheDocument();
    expect(tokenStore.get()).toMatchObject({
      accessToken: "fresh-access",
      refreshToken: "fresh-refresh",
    });
  });

  it("shows the uniform failure message and stays on the form for bad credentials", async () => {
    installFetchRouter([
      {
        method: "POST",
        url: "/api/v1/auth/login",
        status: 401,
        body: {
          title: "Unauthorized",
          status: 401,
          code: "INVALID_CREDENTIALS",
          detail: "email or password is incorrect, or the account is locked or disabled",
        },
      },
    ]);
    renderConsole(<LoginPage />, { route: "/login" });

    await user.type(screen.getByTestId("login-email"), "dev@shipperdog.local");
    await user.type(screen.getByTestId("login-password"), "nope");
    await user.click(screen.getByTestId("login-submit"));

    const error = await screen.findByTestId("login-error");
    expect(error).toHaveTextContent("Sign-in failed");
    expect(error).toHaveTextContent("incorrect");
    expect(tokenStore.get()).toBeNull();
  });
});
