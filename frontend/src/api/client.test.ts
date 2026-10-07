/** Client contract tests (issue #12): the problem+json envelope, cursor
 * query encoding, and the single-flight refresh-retry loop are the
 * pieces every console screen depends on. */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  apiFetch,
  apiFetchText,
  ensureFreshTokens,
  pageFetch,
  SESSION_EXPIRED_EVENT,
} from "./client";
import { tokenStore } from "../auth/tokenStore";

type FetchMock = ReturnType<typeof vi.fn>;

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/problem+json" },
  });
}

let fetchMock: FetchMock;

beforeEach(() => {
  tokenStore.clear();
  fetchMock = vi.fn();
  vi.stubGlobal("fetch", fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("apiFetch error envelope", () => {
  it("decodes problem+json into ApiError with code, detail, and trace id", async () => {
    fetchMock.mockResolvedValueOnce(
      jsonResponse(404, {
        type: "https://api.shipperdog.dev/problems/tenant-not-found",
        title: "Not Found",
        status: 404,
        code: "TENANT_NOT_FOUND",
        detail: "Resource not found.",
        trace_id: "7f9c24a1-3e55-4b0e-9d1f-2a8b76c5dd90",
      }),
    );
    const error = await apiFetch("/tenants/00000000-0000-0000-0000-000000000000").catch(
      (cause: unknown) => cause,
    );
    expect(error).toBeInstanceOf(Error);
    expect(error).toMatchObject({
      status: 404,
      code: "TENANT_NOT_FOUND",
      detail: "Resource not found.",
      traceId: "7f9c24a1-3e55-4b0e-9d1f-2a8b76c5dd90",
    });
    expect((error as Error).message).toContain("Not Found");
  });

  it("keeps 400 field errors for form display", async () => {
    fetchMock.mockResolvedValueOnce(
      jsonResponse(400, {
        title: "Bad Request",
        status: 400,
        code: "INVALID_REQUEST",
        detail: "Validation failed.",
        errors: { name: "name is required (max 200 characters)" },
      }),
    );
    const error = await apiFetch("/tenants", { method: "POST", body: {} }).catch(
      (cause: unknown) => cause,
    );
    expect(error).toMatchObject({
      status: 400,
      fieldErrors: { name: "name is required (max 200 characters)" },
    });
  });

  it("survives non-JSON error bodies with a status-only ApiError", async () => {
    fetchMock.mockResolvedValueOnce(new Response("gateway timeout", { status: 504 }));
    const error = await apiFetch("/tenants").catch((cause: unknown) => cause);
    expect(error).toMatchObject({ status: 504 });
  });
});

describe("apiFetch auth", () => {
  it("sends the bearer token and a correlation id", async () => {
    tokenStore.save("access-1", "refresh-1", 900);
    fetchMock.mockResolvedValueOnce(jsonResponse(200, { ok: true }));
    await apiFetch("/tenants");
    const init = fetchMock.mock.calls[0]?.[1] as RequestInit;
    const headers = init.headers as Record<string, string>;
    expect(headers.Authorization).toBe("Bearer access-1");
    expect(headers["X-Correlation-Id"]).toMatch(/[0-9a-f-]{36}/);
  });

  it("refreshes once on 401, retries with the new token, and does not loop", async () => {
    tokenStore.save("stale-access", "refresh-1", 900);
    fetchMock.mockImplementation(
      async (input: RequestInfo | URL, init?: RequestInit) => {
        const url = String(input);
        if (url.endsWith("/auth/refresh")) {
          return jsonResponse(200, {
            accessToken: "fresh-access",
            refreshToken: "refresh-2",
            tokenType: "Bearer",
            expiresInSeconds: 900,
          });
        }
        const headers = (init?.headers ?? {}) as Record<string, string>;
        if (headers.Authorization === "Bearer stale-access") {
          return jsonResponse(401, {
            title: "Unauthorized",
            status: 401,
            code: "UNAUTHENTICATED",
          });
        }
        return jsonResponse(200, { items: [] });
      },
    );

    const result = await apiFetch<{ items: unknown[] }>("/tenants");
    expect(result).toEqual({ items: [] });
    expect(tokenStore.get()?.accessToken).toBe("fresh-access");
    const calls = fetchMock.mock.calls as Array<[string, RequestInit]>;
    expect(calls.filter(([url]) => url.endsWith("/auth/refresh"))).toHaveLength(1);
    const retries = calls.filter(
      ([url, init]) =>
        url.endsWith("/tenants") &&
        (init.headers as Record<string, string>).Authorization === "Bearer fresh-access",
    );
    expect(retries).toHaveLength(1);
  });

  it("recovers GET /auth/me with an expired access token but a live refresh token", async () => {
    // Issue #39 (the #35 reviewer finding): /auth/me is the session-
    // validation call; a browser reload must transparently refresh
    // instead of logging the operator out.
    tokenStore.save("stale-access", "refresh-1", 900);
    fetchMock.mockImplementation(
      async (input: RequestInfo | URL, init?: RequestInit) => {
        const url = String(input);
        if (url.endsWith("/auth/refresh")) {
          return jsonResponse(200, {
            accessToken: "fresh-access",
            refreshToken: "refresh-2",
            tokenType: "Bearer",
            expiresInSeconds: 900,
          });
        }
        const headers = (init?.headers ?? {}) as Record<string, string>;
        if (headers.Authorization === "Bearer stale-access") {
          return jsonResponse(401, { title: "Unauthorized", status: 401, code: "UNAUTHENTICATED" });
        }
        return jsonResponse(200, { id: "user-1", email: "ops@example.com" });
      },
    );

    const me = await apiFetch<{ id: string }>("/auth/me");
    expect(me.id).toBe("user-1");
    expect(tokenStore.get()?.accessToken).toBe("fresh-access");
  });

  it("clears the session and emits the expiry event when refresh fails", async () => {
    tokenStore.save("stale-access", "dead-refresh", 900);
    const expired = vi.fn();
    window.addEventListener(SESSION_EXPIRED_EVENT, expired);
    fetchMock.mockImplementation(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.endsWith("/auth/refresh")) {
        return jsonResponse(401, {
          title: "Unauthorized",
          status: 401,
          code: "INVALID_REFRESH_TOKEN",
        });
      }
      return jsonResponse(401, { title: "Unauthorized", status: 401 });
    });

    const first = apiFetch("/tenants").catch((cause: unknown) => cause);
    const second = apiFetch("/organizations").catch((cause: unknown) => cause);
    const [firstResult, secondResult] = await Promise.all([first, second]);
    expect(firstResult).toMatchObject({ status: 401 });
    expect(secondResult).toMatchObject({ status: 401 });
    expect(tokenStore.get()).toBeNull();
    expect(expired).toHaveBeenCalledTimes(1);
    // One refresh attempt for a whole wave of concurrent 401s.
    const calls = fetchMock.mock.calls as Array<[string]>;
    expect(calls.filter(([url]) => url.endsWith("/auth/refresh"))).toHaveLength(1);
    window.removeEventListener(SESSION_EXPIRED_EVENT, expired);
  });
});

describe("apiFetchText (non-JSON bodies, S9-05)", () => {
  it("returns the raw text body with the accept header carried", async () => {
    tokenStore.save("access-1", "refresh-1", 900);
    fetchMock.mockResolvedValueOnce(
      new Response("releaseRef: R-101\n", {
        status: 200,
        headers: { "Content-Type": "application/yaml" },
      }),
    );
    const yaml = await apiFetchText("/releases/r-1/manifest?format=yaml", {
      accept: "application/yaml",
    });
    expect(yaml).toBe("releaseRef: R-101\n");
    const init = fetchMock.mock.calls[0]?.[1] as RequestInit;
    const headers = init.headers as Record<string, string>;
    expect(headers.Accept).toBe("application/yaml");
    expect(headers.Authorization).toBe("Bearer access-1");
  });

  it("defaults to the JSON accept header and decodes problem+json errors", async () => {
    fetchMock.mockResolvedValueOnce(
      jsonResponse(404, {
        title: "Not Found",
        status: 404,
        code: "RELEASE_MANIFEST_NOT_FOUND",
        detail: "no manifest is frozen for this release",
      }),
    );
    const error = await apiFetchText("/releases/r-1/manifest").catch(
      (cause: unknown) => cause,
    );
    expect(error).toMatchObject({
      status: 404,
      code: "RELEASE_MANIFEST_NOT_FOUND",
      detail: "no manifest is frozen for this release",
    });
  });

  it("refreshes once on 401 and retries the text request", async () => {
    tokenStore.save("stale-access", "refresh-1", 900);
    fetchMock.mockImplementation(
      async (input: RequestInfo | URL, init?: RequestInit) => {
        const url = String(input);
        if (url.endsWith("/auth/refresh")) {
          return jsonResponse(200, {
            accessToken: "fresh-access",
            refreshToken: "refresh-2",
            tokenType: "Bearer",
            expiresInSeconds: 900,
          });
        }
        const headers = (init?.headers ?? {}) as Record<string, string>;
        if (headers.Authorization === "Bearer stale-access") {
          return jsonResponse(401, { title: "Unauthorized", status: 401, code: "UNAUTHENTICATED" });
        }
        return new Response("recovered: true\n", { status: 200 });
      },
    );

    const yaml = await apiFetchText("/releases/r-1/manifest?format=yaml", {
      accept: "application/yaml",
    });
    expect(yaml).toBe("recovered: true\n");
    expect(tokenStore.get()?.accessToken).toBe("fresh-access");
  });
});

describe("pageFetch cursor pagination", () => {
  it("encodes filters and limit, and omits absent next_cursor", async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(200, { items: [{ id: "a" }] }));
    const page = await pageFetch<{ id: string }>("/tenants", {
      organizationId: "org-1",
      status: undefined,
      limit: 25,
    });
    expect(page).toEqual({ items: [{ id: "a" }], nextCursor: undefined });
    expect(fetchMock.mock.calls[0]?.[0]).toBe("/api/v1/tenants?organizationId=org-1&limit=25");
  });

  it("passes next_cursor through as the next page's cursor", async () => {
    fetchMock.mockResolvedValueOnce(
      jsonResponse(200, { items: [{ id: "a" }], next_cursor: "eyJvIjo1MH0" }),
    );
    const page = await pageFetch("/tenants", { organizationId: "org-1", limit: 50 });
    expect(page.nextCursor).toBe("eyJvIjo1MH0");
  });
});

describe("ensureFreshTokens without a session", () => {
  it("resolves false without touching the network", async () => {
    const result = await ensureFreshTokens();
    expect(result).toBe(false);
    expect(fetchMock).not.toHaveBeenCalled();
  });
});
