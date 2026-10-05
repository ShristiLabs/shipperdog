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
  if (response.status === 401 && !options.retrying && !isAuthPath(path)) {
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

function isAuthPath(path: string): boolean {
  return path.startsWith("/auth/");
}
