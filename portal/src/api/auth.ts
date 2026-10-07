/**
 * Raw auth endpoints (docs/authentication.md, docs/testers.md). These
 * bypass the general client deliberately: login, register (invitation
 * accept), and refresh run WITHOUT a bearer token and must never
 * trigger the client's refresh-retry loop; their failures surface the
 * same ApiError envelope as everything else.
 */
import { ApiError, parseProblem } from "./problem";

export interface TokenPair {
  accessToken: string;
  refreshToken: string;
  tokenType: string;
  expiresInSeconds: number;
}

/** GET /auth/me — the tester context that drives the portal home. */
export interface CurrentUser {
  id: string;
  email: string;
  fullName: string;
  status: string;
  orgId: string | null;
  tenantId: string | null;
  authenticatedVia: string;
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

/** Accept a tester invitation (S6-01): set the account password, then
 * the caller logs in through the standard flow. Registration binds the
 * INVITED tester record to a fresh user account. */
export async function registerRequest(request: {
  invitationToken: string;
  email: string;
  password: string;
  fullName: string;
}): Promise<void> {
  const response = await fetch("/api/v1/auth/register", {
    method: "POST",
    headers: jsonHeaders(),
    body: JSON.stringify(request),
  });
  if (!response.ok) throw await parseProblem(response);
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
