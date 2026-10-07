/**
 * Component-test harness (issue #35, mirroring the console's): providers
 * (query client, theme, router, auth, toasts) plus a declarative fetch
 * router so screen tests exercise the real AuthContext/QueryClient
 * paths instead of mocks of them. Routes are matched in order;
 * unmatched requests hit a catch-all that records them and answer 404 so
 * missing stubs fail loudly in assertions rather than hanging.
 */
import { ThemeProvider } from "@mui/material";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, type RenderOptions } from "@testing-library/react";
import type { ReactElement, ReactNode } from "react";
import { MemoryRouter } from "react-router-dom";
import { AuthProvider } from "../auth/AuthContext";
import { tokenStore } from "../auth/tokenStore";
import { ToastProvider } from "../components/ToastContext";
import { portalTheme } from "../theme";

export interface SignedInTester {
  id?: string;
  email?: string;
  fullName?: string;
}

export function seedSession(user: SignedInTester = {}): void {
  tokenStore.save("access-token", "refresh-token", 900);
  routeTable.unshift({
    method: "GET",
    url: "/api/v1/auth/me",
    status: 200,
    body: {
      id: user.id ?? "20000000-0000-4000-8000-000000000020",
      email: user.email ?? "ana.tester@example.com",
      fullName: user.fullName ?? "Ana Tester",
      status: "ACTIVE",
      orgId: null,
      tenantId: null,
      authenticatedVia: "PASSWORD",
    },
  });
}

/**
 * Stubs the catalog listing (S8-02) with the given items — the honest
 * empty page by default. Call AFTER installFetchRouter (it unshifts
 * onto the live table, like seedSession).
 */
export function seedCatalog(items: unknown[] = [], nextCursor?: string): void {
  const body: Record<string, unknown> = { effectiveAccess: true, items };
  if (nextCursor) body.next_cursor = nextCursor;
  routeTable.unshift({
    method: "GET",
    url: "/api/v1/authorization/decisions/applications",
    status: 200,
    body,
  });
}

export interface SequencedResponse {
  status: number;
  body?: unknown;
}

export interface Route {
  method: string;
  url: string;
  /** Response for a plain route; optional when `sequence` carries the
   * ordered responses (a plain route without status answers 500 so a
   * mis-authored stub fails loudly). */
  status?: number;
  body?: unknown;
  /** Ordered responses when the route matches repeatedly: the Nth match
   * answers sequence[N-1]; the last entry repeats once the sequence is
   * exhausted. */
  sequence?: SequencedResponse[];
}

let routeTable: Route[] = [];
export const unmatchedRequests: string[] = [];

/** Every request the router saw, in order: method, path, and the
 * Authorization header when present (absent for raw auth calls). */
export interface RecordedRequest {
  method: string;
  path: string;
  authorization?: string;
}
export const recordedRequests: RecordedRequest[] = [];

export function installFetchRouter(routes: Route[]): void {
  routeTable = [...routes];
  unmatchedRequests.length = 0;
  recordedRequests.length = 0;
  const hits = new Map<Route, number>();
  vi.stubGlobal(
    "fetch",
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      const path = url.split("?")[0] ?? url;
      const method = (init?.method ?? "GET").toUpperCase();
      const headers = new Headers(init?.headers);
      recordedRequests.push({
        method,
        path,
        authorization: headers.get("Authorization") ?? undefined,
      });
      for (const route of routeTable) {
        if (route.method === method && path === route.url) {
          const seen = hits.get(route) ?? 0;
          hits.set(route, seen + 1);
          const response = route.sequence
            ? route.sequence[Math.min(seen, route.sequence.length - 1)]!
            : { status: route.status ?? 500, body: route.body };
          return new Response(response.body === undefined ? null : JSON.stringify(response.body), {
            status: response.status,
            headers: { "Content-Type": "application/problem+json" },
          });
        }
      }
      unmatchedRequests.push(`${method} ${url}`);
      return new Response(
        JSON.stringify({ title: "Not Found", status: 404, code: "UNMATCHED" }),
        { status: 404, headers: { "Content-Type": "application/problem+json" } },
      );
    }),
  );
}

interface PortalRenderOptions extends RenderOptions {
  route?: string;
}

export function renderPortal(ui: ReactElement, options: PortalRenderOptions = {}): void {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  function Wrapper({ children }: { children: ReactNode }) {
    return (
      <QueryClientProvider client={queryClient}>
        <ThemeProvider theme={portalTheme}>
          <MemoryRouter initialEntries={[options.route ?? "/"]}>
            <AuthProvider>
              <ToastProvider>{children}</ToastProvider>
            </AuthProvider>
          </MemoryRouter>
        </ThemeProvider>
      </QueryClientProvider>
    );
  }
  render(ui, { ...options, wrapper: Wrapper });
}
