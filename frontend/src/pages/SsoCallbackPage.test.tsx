/** SSO landing route behavior (S14-01): the one-time code exchange
 * enters the console; a failed or missing code renders the retry path
 * with the same opaque message the backend's uniform redirect implies. */
import { cleanup, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { Route, Routes } from "react-router-dom";
import SsoCallbackPage from "./SsoCallbackPage";
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

const meBody = {
  id: "10000000-0000-4000-8000-000000000030",
  email: "corp.user@example.com",
  fullName: "Corp User",
  status: "ACTIVE",
  orgId: "10000000-0000-4000-8000-000000000003",
  tenantId: null,
  authenticatedVia: "jwt",
};

describe("SsoCallbackPage", () => {
  it("exchanges the one-time code, stores the session, and enters the console", async () => {
    installFetchRouter([
      {
        method: "POST",
        url: "/api/v1/auth/sso/exchange",
        status: 200,
        body: {
          accessToken: "sso-access",
          refreshToken: "sso-refresh",
          tokenType: "Bearer",
          expiresInSeconds: 900,
        },
      },
      { method: "GET", url: "/api/v1/auth/me", status: 200, body: meBody },
    ]);
    renderConsole(
      <Routes>
        <Route path="/sso/callback" element={<SsoCallbackPage />} />
        <Route path="/dashboard" element={<div data-testid="reached-dashboard" />} />
      </Routes>,
      { route: "/sso/callback?code=one-time-code" },
    );

    expect(await screen.findByTestId("reached-dashboard")).toBeInTheDocument();
    expect(tokenStore.get()?.accessToken).toBe("sso-access");
  });

  it("renders the opaque retry message when the exchange fails", async () => {
    installFetchRouter([
      {
        method: "POST",
        url: "/api/v1/auth/sso/exchange",
        status: 401,
        body: { status: 401, code: "INVALID_SSO_SESSION_CODE" },
      },
    ]);
    renderConsole(
      <Routes>
        <Route path="/sso/callback" element={<SsoCallbackPage />} />
      </Routes>,
      { route: "/sso/callback?code=already-used" },
    );

    expect(await screen.findByTestId("sso-callback-error")).toHaveTextContent(
      "Single sign-on could not be completed",
    );
    expect(tokenStore.get()).toBeNull();
  });

  it("answers a code-less landing with the same retry message", async () => {
    renderConsole(
      <Routes>
        <Route path="/sso/callback" element={<SsoCallbackPage />} />
      </Routes>,
      { route: "/sso/callback?error=sso" },
    );

    expect(await screen.findByTestId("sso-callback-error")).toBeInTheDocument();
    await user.click(screen.getByTestId("sso-callback-retry"));
  });
});
