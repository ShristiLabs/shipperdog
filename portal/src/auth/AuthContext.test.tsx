/** Silent-refresh tests (issue #35): the timer that rotates the access
 * token one minute before expiry. Covers both branches — a stored token
 * already inside the 60s lead window refreshes immediately after the
 * session validates, and a token with time left refreshes exactly at
 * expiry-minus-lead, storing the rotated pair atomically (refresh
 * rotation is single-use server-side, docs/authentication.md). */
import { afterEach, describe, expect, it } from "vitest";
import { act, screen } from "@testing-library/react";
import App from "../App";
import { tokenStore } from "./tokenStore";
import { installFetchRouter, recordedRequests, renderPortal } from "../test/harness";

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

const REFRESH_OK = {
  method: "POST",
  url: "/api/v1/auth/refresh",
  status: 200,
  body: {
    accessToken: "rotated-access",
    refreshToken: "rotated-refresh",
    tokenType: "Bearer",
    expiresInSeconds: 900,
  },
};

afterEach(() => {
  tokenStore.clear();
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

function refreshCalls(): number {
  return recordedRequests.filter((call) => call.path === "/api/v1/auth/refresh").length;
}

describe("silent refresh", () => {
  it("refreshes immediately when a reloaded session is already inside the lead window", async () => {
    // 30s to expiry is inside the 60s lead: mount must refresh at once,
    // not schedule a timer that would fire after the token is dead.
    installFetchRouter([ME_OK, REFRESH_OK]);
    tokenStore.save("near-expiry-access", "refresh-token", 30);
    renderPortal(<App />, { route: "/" });

    await screen.findByTestId("portal-home-title");
    await vi.waitFor(() => expect(tokenStore.get()?.accessToken).toBe("rotated-access"));
    expect(refreshCalls()).toBe(1);
  });

  it("rotates the token at expiry minus the lead, not before", async () => {
    vi.useFakeTimers();
    // 120s to expiry -> the timer is armed for 60s out (expiry minus lead).
    installFetchRouter([ME_OK, REFRESH_OK]);
    tokenStore.save("fresh-access", "refresh-token", 120);
    renderPortal(<App />, { route: "/" });

    // Flush the mount session validation; the token must NOT rotate yet.
    await act(async () => {
      await vi.advanceTimersByTimeAsync(59_999);
    });
    expect(screen.getByTestId("portal-home-title")).toBeInTheDocument();
    expect(tokenStore.get()?.accessToken).toBe("fresh-access");
    expect(refreshCalls()).toBe(0);

    await act(async () => {
      await vi.advanceTimersByTimeAsync(1);
    });
    expect(tokenStore.get()?.accessToken).toBe("rotated-access");
    expect(tokenStore.get()?.refreshToken).toBe("rotated-refresh");
    expect(refreshCalls()).toBe(1);
  });
});
