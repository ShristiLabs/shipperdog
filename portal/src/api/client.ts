/**
 * Portal API client (issue #35). Every business call goes through
 * apiFetch, which centralizes the platform's cross-cutting API contract
 * (docs/api-conventions.md) — deliberately the same idiom as the
 * console's client, re-created small rather than imported so the portal
 * bundle stays independent of the console app (issue AC5):
 *
 * - base path /api/v1, same-origin (Vite dev proxy or nginx in compose);
 * - errors are RFC 7807 application/problem+json, surfaced as ApiError
 *   with the stable `code`, registry `detail`, `traceId`, and the 400
 *   `errors` field map (decoded in problem.ts);
 * - every request carries a fresh X-Correlation-Id so a screen error can
 *   be joined against server logs and the audit trail;
 * - a 401 triggers one single-flight token refresh and a single retry;
 *   a failed refresh clears the session and emits "sd:portal:session-expired"
 *   (the AuthContext listens and returns the tester to the login screen).
 *
 * The portal needs no cursor pagination yet (S8-02 will add it when the
 * catalog arrives).
 */
import { refreshRequest } from "./auth";
import { parseProblem } from "./problem";
import { tokenStore } from "../auth/tokenStore";

const BASE = "/api/v1";

export { ApiError } from "./problem";

export const SESSION_EXPIRED_EVENT = "sd:portal:session-expired";

interface RequestOptions {
  method?: string;
  body?: unknown;
  /** Internal: set on the post-refresh retry to bound it to one attempt. */
  retrying?: boolean;
}

let refreshInFlight: Promise<boolean> | null = null;

/** Refresh the token pair once per wave of 401s; all callers await the
 * same promise. Resolves false when the session cannot be recovered. */
export function ensureFreshTokens(): Promise<boolean> {
  if (!refreshInFlight) {
    refreshInFlight = (async () => {
      const session = tokenStore.get();
      if (!session) return false;
      try {
        const pair = await refreshRequest(session.refreshToken);
        tokenStore.save(pair.accessToken, pair.refreshToken, pair.expiresInSeconds);
        return true;
      } catch {
        tokenStore.clear();
        window.dispatchEvent(new Event(SESSION_EXPIRED_EVENT));
        return false;
      } finally {
        refreshInFlight = null;
      }
    })();
  }
  return refreshInFlight;
}

export async function apiFetch<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const response = await rawFetch(path, options);
  if (response.status === 401 && !options.retrying && !isRawAuthPath(path)) {
    const refreshed = await ensureFreshTokens();
    if (refreshed) {
      return apiFetch<T>(path, { ...options, retrying: true });
    }
  }
  if (!response.ok) {
    throw await parseProblem(response);
  }
  if (response.status === 204) {
    return undefined as T;
  }
  return (await response.json()) as T;
}

async function rawFetch(path: string, options: RequestOptions): Promise<Response> {
  const headers: Record<string, string> = {
    Accept: "application/json",
    "X-Correlation-Id": crypto.randomUUID(),
  };
  if (options.body !== undefined) {
    headers["Content-Type"] = "application/json";
  }
  const session = tokenStore.get();
  if (session) {
    headers.Authorization = `Bearer ${session.accessToken}`;
  }
  return fetch(`${BASE}${path}`, {
    method: options.method ?? "GET",
    headers,
    body: options.body === undefined ? undefined : JSON.stringify(options.body),
  });
}

/** The raw auth POSTs (login, register, refresh, logout) must never
 * trigger the refresh-retry loop — they carry no session. They are
 * invoked through raw fetch in api/auth.ts and never via apiFetch, so
 * this guard is defense in depth. GET /auth/me deliberately DOES
 * refresh: it is the session-validation call, and a reload with an
 * expired access token but a live refresh token must recover
 * transparently (issue AC3). */
const RAW_AUTH_PATHS = new Set(["/auth/login", "/auth/register", "/auth/refresh", "/auth/logout"]);

function isRawAuthPath(path: string): boolean {
  return RAW_AUTH_PATHS.has(path);
}
