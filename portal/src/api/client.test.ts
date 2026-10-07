/**
 * API client contract tests (issue #35): problem+json decoding, the
 * single-flight refresh on 401 with exactly one retry, and the
 * session-expiry path when refresh fails — the same guarantees the
 * console's client carries (docs/frontend.md conventions).
 */
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { apiFetch, ensureFreshTokens, ApiError, SESSION_EXPIRED_EVENT } from "./client";
import { tokenStore } from "../auth/tokenStore";
import { installFetchRouter, recordedRequests, unmatchedRequests } from "../test/harness";

const OK_REFRESH = {
  method: "POST",
  url: "/api/v1/auth/refresh",
  status: 200,
  body: {
    accessToken: "new-access",
    refreshToken: "new-refresh",
    tokenType: "Bearer",
    expiresInSeconds: 900,
  },
};

const PROBLEM_401 = {
  title: "Unauthorized",
  status: 401,
  code: "UNAUTHENTICATED",
};

beforeEach(() => {
  tokenStore.clear();
});

afterEach(() => {
  tokenStore.clear();
  vi.unstubAllGlobals();
});

describe("apiFetch problem decoding", () => {
  it("decodes the problem+json envelope into ApiError fields", async () => {
    installFetchRouter([
      {
        method: "GET",
        url: "/api/v1/anything",
        status: 400,
        body: {
          title: "Invalid request",
          status: 400,
          code: "INVALID_REQUEST",
          detail: "limit must be 1-100",
          trace_id: "trace-1",
          errors: { limit: "must be a number" },
        },
      },
    ]);

    const failure = await apiFetch<never>("/anything").catch((cause): ApiError => cause as ApiError);
    expect(failure).toBeInstanceOf(ApiError);
    expect(failure.status).toBe(400);
    expect(failure.code).toBe("INVALID_REQUEST");
    expect(failure.detail).toBe("limit must be 1-100");
    expect(failure.traceId).toBe("trace-1");
    expect(failure.fieldErrors).toEqual({ limit: "must be a number" });
  });
});

describe("401 single-flight refresh", () => {
  it("refreshes once, retries once, and succeeds with the new token", async () => {
    tokenStore.save("expired-access", "refresh-token", 900);
    installFetchRouter([
      {
        method: "GET",
        url: "/api/v1/auth/me",
        sequence: [{ status: 401, body: PROBLEM_401 }, { status: 200, body: { id: "u1" } }],
      },
      OK_REFRESH,
    ]);

    const me = await apiFetch<{ id: string }>("/auth/me");
    expect(me.id).toBe("u1");
    expect(tokenStore.get()?.accessToken).toBe("new-access");

    const refreshCalls = recordedRequests.filter((call) => call.path === "/api/v1/auth/refresh");
    expect(refreshCalls).toHaveLength(1);
    const meCalls = recordedRequests.filter((call) => call.path === "/api/v1/auth/me");
    expect(meCalls).toHaveLength(2);
    expect(meCalls[0]?.authorization).toBe("Bearer expired-access");
    expect(meCalls[1]?.authorization).toBe("Bearer new-access");
  });

  it("shares one refresh across concurrent 401s (single-flight)", async () => {
    tokenStore.save("expired-access", "refresh-token", 900);
    installFetchRouter([
      {
        method: "GET",
        url: "/api/v1/auth/me",
        sequence: [{ status: 401, body: PROBLEM_401 }, { status: 200, body: { id: "u1" } }],
      },
      {
        method: "GET",
        url: "/api/v1/other",
        sequence: [{ status: 401, body: PROBLEM_401 }, { status: 200, body: { ok: true } }],
      },
      OK_REFRESH,
    ]);

    const [me, other] = await Promise.all([
      apiFetch<{ id: string }>("/auth/me"),
      apiFetch<{ ok: boolean }>("/other"),
    ]);
    expect(me.id).toBe("u1");
    expect(other.ok).toBe(true);
    expect(tokenStore.get()?.refreshToken).toBe("new-refresh");
    expect(recordedRequests.filter((call) => call.path === "/api/v1/auth/refresh")).toHaveLength(1);
  });

  it("bounds the retry to one attempt — a second 401 propagates", async () => {
    tokenStore.save("expired-access", "refresh-token", 900);
    installFetchRouter([
      { method: "GET", url: "/api/v1/auth/me", status: 401, body: PROBLEM_401 },
      OK_REFRESH,
    ]);

    const failure = await apiFetch<never>("/auth/me").catch((cause): ApiError => cause as ApiError);
    expect(failure).toBeInstanceOf(ApiError);
    expect(failure.status).toBe(401);
    expect(recordedRequests.filter((call) => call.path === "/api/v1/auth/me")).toHaveLength(2);
  });

  it("auth paths never trigger the refresh loop (login 401 propagates)", async () => {
    tokenStore.save("access", "refresh", 900);
    installFetchRouter([
      {
        method: "POST",
        url: "/api/v1/auth/login",
        status: 401,
        body: { title: "Unauthorized", status: 401, code: "INVALID_CREDENTIALS" },
      },
    ]);

    const failure = await apiFetch<never>("/auth/login", {
      method: "POST",
      body: { email: "x@example.com", password: "y" },
    }).catch((cause): ApiError => cause as ApiError);
    expect(failure).toBeInstanceOf(ApiError);
    expect(failure.status).toBe(401);
    expect(recordedRequests.filter((call) => call.path === "/api/v1/auth/refresh")).toHaveLength(0);
    expect(unmatchedRequests).toHaveLength(0);
  });
});

describe("failed refresh ends the session", () => {
  it("clears the stored session and emits the expiry event once", async () => {
    tokenStore.save("expired-access", "rotated-away", 900);
    installFetchRouter([
      {
        method: "POST",
        url: "/api/v1/auth/refresh",
        status: 401,
        body: { title: "Unauthorized", status: 401, code: "INVALID_REFRESH_TOKEN" },
      },
    ]);

    const events: string[] = [];
    const listener = () => events.push(SESSION_EXPIRED_EVENT);
    window.addEventListener(SESSION_EXPIRED_EVENT, listener);
    const refreshed = await ensureFreshTokens();
    window.removeEventListener(SESSION_EXPIRED_EVENT, listener);

    expect(refreshed).toBe(false);
    expect(tokenStore.get()).toBeNull();
    expect(events).toHaveLength(1);
  });
});
