/**
 * Raw auth endpoints (docs/authentication.md). These bypass the general
 * client deliberately: login and refresh run WITHOUT a bearer token and
 * must never trigger the client's refresh-retry loop; their failures
 * surface the same ApiError envelope as everything else.
 */
import { ApiError, parseProblem } from "./problem";

export interface TokenPair {
  accessToken: string;
  refreshToken: string;
  tokenType: string;
  expiresInSeconds: number;
}

export interface CurrentUser {
  id: string;
  email: string;
  fullName: string;
  status: string;
  orgId: string | null;
  tenantId: string | null;
  authenticatedVia: string;
}

/** Whether an organization offers OIDC SSO (S14-01): 200 when the
 * console should offer the button; ApiError 404 otherwise. */
export async function ssoOptionsRequest(organizationId: string): Promise<void> {
  const response = await fetch(
    "/api/v1/auth/sso/options?organizationId=" + encodeURIComponent(organizationId),
    { headers: { Accept: "application/json" } },
  );
  if (!response.ok) throw await parseProblem(response);
}

/** Exchanges the one-time SSO session code for the same token pair a
 * local login receives; the code is single-use. */
export async function ssoExchangeRequest(code: string): Promise<TokenPair> {
  const response = await fetch("/api/v1/auth/sso/exchange", {
    method: "POST",
    headers: jsonHeaders(),
    body: JSON.stringify({ code }),
  });
  if (!response.ok) throw await parseProblem(response);
  return (await response.json()) as TokenPair;
}

export async function loginRequest(email: string, password: string): Promise<TokenPair> {
  const response = await fetch("/api/v1/auth/login", {
    method: "POST",
    headers: jsonHeaders(),
    body: JSON.stringify({ email, password }),
  });
  if (!response.ok) throw await parseProblem(response);
  return (await response.json()) as TokenPair;
}

export async function refreshRequest(refreshToken: string): Promise<TokenPair> {
  const response = await fetch("/api/v1/auth/refresh", {
    method: "POST",
    headers: jsonHeaders(),
    body: JSON.stringify({ refreshToken }),
  });
  if (!response.ok) throw await parseProblem(response);
  return (await response.json()) as TokenPair;
}

/** Best-effort revocation: the local session ends regardless of outcome
 * (the family also expires on its own TTL if the call never lands). */
export async function logoutRequest(refreshToken: string): Promise<void> {
  await fetch("/api/v1/auth/logout", {
    method: "POST",
    headers: jsonHeaders(),
    body: JSON.stringify({ refreshToken }),
  }).catch(() => undefined);
}

function jsonHeaders(): Record<string, string> {
  return { "Content-Type": "application/json", Accept: "application/json" };
}

export { ApiError };
