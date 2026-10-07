/**
 * The tester's application catalog (issue #36, S8-02): the decision
 * API's self-scoped listing the portal home renders. The server
 * computes the reachability set (never a client-side filter of a
 * bigger list, PRD 26); this module only types the card contract and
 * pages through it with the platform's cursor envelope
 * ({@code next_cursor} present exactly when another page follows).
 */
import { apiFetch } from "./client";

/** The application facts of a catalog card. */
export interface CatalogApplication {
  applicationId: string;
  name: string;
  status: string;
}

/** The version facts of a catalog card. */
export interface CatalogVersion {
  versionId: string;
  versionName: string;
  status: string;
}

/**
 * One platform's install resolution: the newest authorized version
 * carrying a distributable artifact for the platform, with the tenant
 * dimension the S8-04 download decision is addressed through.
 */
export interface CatalogPlatform {
  platform: "IOS" | "ANDROID";
  tenantId: string;
  versionId: string;
  versionName: string;
}

/** The card model the S8-03 history views and S8-04 download flow reuse. */
export interface CatalogCard {
  tenantId: string;
  application: CatalogApplication;
  latestAuthorizedVersion: CatalogVersion;
  platforms: CatalogPlatform[];
}

/** One cursor page of the catalog. */
export interface CatalogPage {
  effectiveAccess: boolean;
  items: CatalogCard[];
  next_cursor?: string;
}

/** The platform's default page size; "Load more" follows next_cursor. */
export const CATALOG_PAGE_SIZE = 20;

/** Fetches one cursor page of the caller's authorized applications. */
export async function fetchCatalogPage(options: { limit?: number; cursor?: string } = {}): Promise<CatalogPage> {
  const params = new URLSearchParams();
  params.set("limit", String(options.limit ?? CATALOG_PAGE_SIZE));
  if (options.cursor) params.set("cursor", options.cursor);
  return apiFetch<CatalogPage>(`/authorization/decisions/applications?${params.toString()}`);
}
