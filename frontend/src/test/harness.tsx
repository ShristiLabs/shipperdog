/**
 * Component-test harness (issue #12): providers (query client, theme,
 * router, auth, toasts) plus a declarative fetch router so screen tests
 * exercise the real AuthContext/QueryClient paths instead of mocks of
 * them. Routes are matched in order; unmatched requests hit a catch-all
 * that records them and answer 404 so missing stubs fail loudly in
 * assertions rather than hanging.
 */
import { ThemeProvider } from "@mui/material";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, type RenderOptions } from "@testing-library/react";
import type { ReactElement, ReactNode } from "react";
import { MemoryRouter } from "react-router-dom";
import { AuthProvider } from "../auth/AuthContext";
import { tokenStore } from "../auth/tokenStore";
import { ToastProvider } from "../components/ToastContext";
import { consoleTheme } from "../theme";

export interface SignedInUser {
  id?: string;
  email?: string;
  orgId?: string | null;
  tenantId?: string | null;
}

export function seedSession(user: SignedInUser = {}): void {
  tokenStore.save("access-token", "refresh-token", 900);
  const me = {
    id: user.id ?? "10000000-0000-4000-8000-000000000010",
    email: user.email ?? "dev@shipperdog.local",
    fullName: "Dev Admin",
    status: "ACTIVE",
    orgId: user.orgId === undefined ? "10000000-0000-4000-8000-000000000001" : user.orgId,
    tenantId: user.tenantId ?? null,
    authenticatedVia: "PASSWORD",
  };
  routeTable.unshift({
    method: "GET",
    url: "/api/v1/auth/me",
    status: 200,
    body: me,
  });
}

export interface SequencedResponse {
  status: number;
  body?: unknown;
}

export interface Route {
  method: string;
  url: string;
  status: number;
  body?: unknown;
  /** Ordered responses when the route matches repeatedly (cursor
   * paging): the Nth match answers sequence[N-1]; the last entry
   * repeats once the sequence is exhausted. */
  sequence?: SequencedResponse[];
}

let routeTable: Route[] = [];
export const unmatchedRequests: string[] = [];

export function installFetchRouter(routes: Route[]): void {
  routeTable = [...routes];
  unmatchedRequests.length = 0;
  const hits = new Map<Route, number>();
  vi.stubGlobal(
    "fetch",
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      const path = url.split("?")[0] ?? url;
      const method = (init?.method ?? "GET").toUpperCase();
      for (const route of routeTable) {
        if (route.method === method && path === route.url) {
          const seen = hits.get(route) ?? 0;
          hits.set(route, seen + 1);
          const response = route.sequence
            ? route.sequence[Math.min(seen, route.sequence.length - 1)]!
            : route;
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

interface ConsoleRenderOptions extends RenderOptions {
  route?: string;
}

export function renderConsole(ui: ReactElement, options: ConsoleRenderOptions = {}): void {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  function Wrapper({ children }: { children: ReactNode }) {
    return (
      <QueryClientProvider client={queryClient}>
        <ThemeProvider theme={consoleTheme}>
          <MemoryRouter initialEntries={[options.route ?? "/tenants"]}>
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
