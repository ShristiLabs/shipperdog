/** Environments client (S9-04 backend, docs/environments.md): the
 * organization-grain deployment contexts a release flows through. The
 * console had no environments surface of its own until the
 * distribution story needed the pickers (S13-01: an environment-level
 * credential override and a channel binding both address one), so
 * this client deliberately exposes only the listing read the pickers
 * need. */
import { pageFetch } from "./client";

/** One environment as the listing serves it. */
export interface Environment {
  id: string;
  organizationId: string;
  code: string;
  name: string;
  position: number;
  status: "ACTIVE" | "DISABLED";
  distributionChannel: string | null;
  approvalPolicy: string | null;
  configVersion: number;
  createdAt: string;
  updatedAt: string;
}

/** The organization's environments in progression order. */
export function listEnvironments(
  organizationId: string,
  params: { limit?: number; cursor?: string } = {},
): Promise<{ items: Environment[]; nextCursor?: string }> {
  return pageFetch<Environment>("/environments", { organizationId, ...params });
}
