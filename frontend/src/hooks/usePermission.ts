/**
 * Role-aware navigation (issue #12, PRD §26). Access tokens deliberately
 * carry no role claims (docs/authentication.md) and the RBAC engine
 * evaluates live memberships per request — so the console never guesses
 * from cached roles: each navigation section probes the exact endpoint
 * it would load, and visibility follows the server's own verdict
 * (permitted 200 shows the section; concealed 404 / opaque 403 hides
 * it). The backend keeps enforcing every call regardless — the probe
 * only decides what to render, never what to allow.
 */
import { useQuery, type UseQueryResult } from "@tanstack/react-query";
import { ApiError } from "../api/problem";

export interface PermissionProbe {
  /** True when the probed endpoint answered 200 and the section may render. */
  allowed: boolean;
  /** False until the probe settles — screens avoid flashing hidden-then-shown nav. */
  settled: boolean;
}

export function usePermission(
  key: string,
  probe: () => Promise<unknown>,
  enabled: boolean,
): PermissionProbe {
  const result: UseQueryResult<unknown, Error> = useQuery({
    queryKey: ["permission", key],
    queryFn: probe,
    enabled,
    retry: false,
    staleTime: 5 * 60_000,
    gcTime: 10 * 60_000,
  });
  if (!enabled) {
    return { allowed: false, settled: true };
  }
  if (result.isPending) {
    return { allowed: false, settled: false };
  }
  const denied =
    result.isError && result.error instanceof ApiError && [403, 404].includes(result.error.status);
  return { allowed: result.isSuccess || !denied, settled: true };
}
