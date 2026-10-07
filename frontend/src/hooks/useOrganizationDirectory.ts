/**
 * Complete-roster loaders for console pickers (issue #16). Grid
 * listings paginate with Load more (api-conventions.md), but a picker
 * needs the whole roster to choose from: these walk the cursor until
 * exhausted — capped at PICKER_MAX_PAGES so a pathological roster
 * cannot loop forever — and cache by organization so the tenant
 * picker, the grant dialog, and the grantedBy resolver share one
 * directory fetch instead of re-walking per screen. S9-05 adds the
 * application registry and per-application version walks (the release
 * dashboard's filters).
 */
import { useQuery } from "@tanstack/react-query";
import { listApplications, type Application } from "../api/applications";
import { listApplicationVersions, type Version } from "../api/versions";
import { listOrganizationMembers, type OrgMember } from "../api/organizations";
import { listTenants, type Tenant } from "../api/tenants";
import type { Page } from "../api/client";

const PICKER_PAGE_LIMIT = 50;
const PICKER_MAX_PAGES = 20;

async function walkAll<T>(fetchPage: (cursor?: string) => Promise<Page<T>>): Promise<T[]> {
  const items: T[] = [];
  let cursor: string | undefined;
  let pages = 0;
  do {
    const page = await fetchPage(cursor);
    items.push(...page.items);
    cursor = page.nextCursor;
    pages += 1;
  } while (cursor !== undefined && pages < PICKER_MAX_PAGES);
  return items;
}

export function useAllTenants(orgId: string | null): { tenants: Tenant[]; loading: boolean } {
  const query = useQuery({
    queryKey: ["org-directory", "tenants", orgId],
    queryFn: () =>
      walkAll((cursor) =>
        listTenants({ organizationId: orgId as string, limit: PICKER_PAGE_LIMIT, cursor }),
      ),
    enabled: orgId !== null,
    staleTime: 60_000,
  });
  return { tenants: query.data ?? [], loading: query.isPending };
}

export function useAllOrgMembers(orgId: string | null): { members: OrgMember[]; loading: boolean } {
  const query = useQuery({
    queryKey: ["org-directory", "members", orgId],
    queryFn: () =>
      walkAll((cursor) =>
        listOrganizationMembers(orgId as string, { limit: PICKER_PAGE_LIMIT, cursor }),
      ),
    enabled: orgId !== null,
    staleTime: 60_000,
  });
  return { members: query.data ?? [], loading: query.isPending };
}

/** The organization-wide application registry as one picker-ready list
 * (S9-05): the release dashboard's application filter — the release
 * listing is application-addressed, so the picker needs every
 * application of the organization, both ownership scopes. */
export function useAllApplications(orgId: string | null): {
  applications: Application[];
  loading: boolean;
} {
  const query = useQuery({
    queryKey: ["org-directory", "applications", orgId],
    queryFn: () =>
      walkAll((cursor) =>
        listApplications({ organizationId: orgId as string, limit: PICKER_PAGE_LIMIT, cursor }),
      ),
    enabled: orgId !== null,
    staleTime: 60_000,
  });
  return { applications: query.data ?? [], loading: query.isPending };
}

/** Every version of one application as one picker-ready list (S9-05):
 * the release list's version filter and the version-name resolver for
 * release headers. */
export function useAllApplicationVersions(applicationId: string | null): {
  versions: Version[];
  loading: boolean;
} {
  const query = useQuery({
    queryKey: ["app-versions", "all", applicationId],
    queryFn: () =>
      walkAll((cursor) =>
        listApplicationVersions(applicationId as string, { limit: PICKER_PAGE_LIMIT, cursor }),
      ),
    enabled: applicationId !== null,
    staleTime: 60_000,
  });
  return { versions: query.data ?? [], loading: query.isPending };
}
