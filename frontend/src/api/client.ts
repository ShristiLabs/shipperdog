/**
 * Console API client (issue #12). Every business call goes through
 * apiFetch, which centralizes the platform's cross-cutting API contract
 * (docs/api-conventions.md):
 *
 * - base path /api/v1, same-origin (Vite dev proxy or nginx in compose);
 * - errors are RFC 7807 application/problem+json, surfaced as ApiError
 *   with the stable `code`, registry `detail`, `traceId`, and the 400
 *   `errors` field map (decoded in problem.ts);
 * - every request carries a fresh X-Correlation-Id so a screen error can
 *   be joined against server logs and the audit trail;
 * - a 401 triggers one single-flight token refresh and a single retry;
 *   a failed refresh clears the session and emits "sd:session-expired"
 *   (the AuthContext listens and returns the user to the login screen).
 *
 * Cursor pagination (`{items, next_cursor}`) is decoded by pageFetch.
 */
import { refreshRequest } from "./auth";
import { parseProblem } from "./problem";
import { tokenStore } from "../auth/tokenStore";

const BASE = "/api/v1";

export { ApiError } from "./problem";

export const SESSION_EXPIRED_EVENT = "sd:session-expired";

/** Cursor-paginated page (docs/api-conventions.md). nextCursor is absent
 * exactly when no further page follows. */
export interface Page<T> {
  items: T[];
  nextCursor?: string;
}

type QueryValue = string | number | undefined;

interface RequestOptions {
  method?: string;
  body?: unknown;
  /** Internal: set on the post-refresh retry to bound it to one attempt. */
  retrying?: boolean;
  /** Internal: carried by apiFetchText to override the Accept header. */
  acceptOverride?: string;
  /** Extra request headers (S16-15): designated POSTs carry an
   * Idempotency-Key so an intra-wizard retry never double-creates —
   * the CLI's per-invocation key doctrine (issue #8's contract). */
  headers?: Record<string, string>;
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

/** The same contract as apiFetch for endpoints that answer a non-JSON
 * body (e.g. a manifest's `format=yaml` render): correlation id, bearer
 * token, the single-flight 401 refresh and retry, and problem+json
 * error decoding — only the success path returns text instead of
 * parsed JSON. Screens still never call fetch directly. */
export async function apiFetchText(
  path: string,
  options: RequestOptions & { accept?: string } = {},
): Promise<string> {
  const { accept, ...requestOptions } = options;
  const response = await rawFetch(path, {
    ...requestOptions,
    ...(accept !== undefined ? { acceptOverride: accept } : {}),
  });
  if (response.status === 401 && !requestOptions.retrying && !isRawAuthPath(path)) {
    const refreshed = await ensureFreshTokens();
    if (refreshed) {
      return apiFetchText(path, { ...options, retrying: true });
    }
  }
  if (!response.ok) {
    throw await parseProblem(response);
  }
  return response.text();
}

/** Append limit/cursor plus arbitrary filters to a list endpoint and
 * decode the `{items, next_cursor}` envelope. */
export async function pageFetch<T>(
  path: string,
  params: Record<string, QueryValue> = {},
): Promise<Page<T>> {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined) search.set(key, String(value));
  }
  const suffix = search.size > 0 ? `?${search.toString()}` : "";
  const body = await apiFetch<{ items: T[]; next_cursor?: string }>(`${path}${suffix}`);
  return {
    items: body.items,
    nextCursor: body.next_cursor,
  };
}

async function rawFetch(path: string, options: RequestOptions): Promise<Response> {
  const headers: Record<string, string> = {
    Accept: options.acceptOverride ?? "application/json",
    "X-Correlation-Id": crypto.randomUUID(),
    ...options.headers,
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
 * refresh (issue #39, the #35 reviewer finding): it is the session-
 * validation call, and a reload with an expired access token but a
 * live refresh token must recover transparently instead of logging
 * the operator out — the prefix match this replaced suppressed that
 * recovery. Same shape as the portal's client. */
const RAW_AUTH_PATHS = new Set(["/auth/login", "/auth/register", "/auth/refresh", "/auth/logout"]);

function isRawAuthPath(path: string): boolean {
  return RAW_AUTH_PATHS.has(path);
}
