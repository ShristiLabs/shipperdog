/**
 * Release list screen (issue #59, S9-05, PRD 32): the console's entry
 * into a release dashboard — pick an application (the listing is
 * application-addressed: applicationId is the permission target),
 * filter by status/version/tenant server-side (the listing's
 * conjunctive params; an unknown status is a 400, never a silently
 * empty page) and by ring client-side over the loaded cursor pages
 * (the listing has no ring param — the same honest pattern as the
 * registry's search box), and open the deep-linkable §32 dashboard
 * per release. The nav section renders under the applications probe:
 * every role holding app:applications:read also holds
 * app:releases:read (authorization matrix), so the picker can always
 * back the screen; the per-application listing stays server-enforced
 * (DEVELOPER holds app:releases:read without app:applications:read —
 * their console entry point is future work, the CLI is the S9-06
 * surface).
 */
import {
  Alert,
  Autocomplete,
  Button,
  Chip,
  MenuItem,
  Stack,
  TextField,
  Typography,
} from "@mui/material";
import { DataGrid, type GridColDef } from "@mui/x-data-grid";
import { useInfiniteQuery } from "@tanstack/react-query";
import { useMemo, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { listReleases, type Release } from "../api/releases";
import { useAuth } from "../auth/AuthContext";
import {
  useAllApplicationVersions,
  useAllApplications,
  useAllTenants,
} from "../hooks/useOrganizationDirectory";
import {
  RELEASE_STATUSES,
  RELEASE_STATUS_COLORS,
  RELEASE_STATUS_LABELS,
} from "./releaseRules";

/** Client-side ring filter + search over the loaded cursor pages (the
 * listing's own filters are the server-side status/version/tenant). */
export function visibleReleases(
  releases: Release[],
  ring: string | null,
  search: string,
): Release[] {
  let visible = releases;
  if (ring !== null) {
    visible = visible.filter((release) => release.ring === ring);
  }
  const needle = search.trim().toLowerCase();
  if (needle !== "") {
    visible = visible.filter((release) => release.releaseRef.toLowerCase().includes(needle));
  }
  return visible;
}

export default function ReleasesPage() {
  const { applicationId: routeApplicationId } = useParams<{ applicationId: string }>();
  const { user } = useAuth();
  const orgId = user?.orgId ?? null;
  const navigate = useNavigate();
  const directory = useAllApplications(orgId);
  const tenants = useAllTenants(orgId);

  const [pickedApplicationId, setPickedApplicationId] = useState<string | null>(null);
  const [status, setStatus] = useState<Release["status"] | "">("");
  const [versionId, setVersionId] = useState<string | null>(null);
  const [tenantId, setTenantId] = useState<string | null>(null);
  const [ring, setRing] = useState<string | null>(null);
  const [search, setSearch] = useState("");

  // The route's application wins until the operator picks another (the
  // deep link from an application's screens preselects its releases);
  // nothing picked falls through to the first application in the
  // directory (derived, not an effect — the ApplicationsPage tenant
  // picker precedent).
  const applicationId =
    pickedApplicationId ?? routeApplicationId ?? directory.applications[0]?.id ?? null;
  const selectedApplication =
    directory.applications.find((application) => application.id === applicationId) ?? null;

  const versions = useAllApplicationVersions(applicationId);

  const query = useInfiniteQuery({
    queryKey: ["releases", applicationId, status || undefined, versionId ?? undefined, tenantId ?? undefined],
    queryFn: ({ pageParam }) =>
      listReleases({
        applicationId: applicationId as string,
        ...(status !== "" ? { status } : {}),
        ...(versionId !== null ? { versionId } : {}),
        ...(tenantId !== null ? { tenantId } : {}),
        limit: 50,
        cursor: pageParam,
      }),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (lastPage) => lastPage.nextCursor,
    enabled: applicationId !== null,
  });
  const releases = useMemo(
    () => query.data?.pages.flatMap((page) => page.items) ?? [],
    [query.data],
  );

  const versionNameById = useMemo(
    () => new Map(versions.versions.map((version) => [version.id, version.versionName])),
    [versions.versions],
  );
  const ringOptions = useMemo(
    () => Array.from(new Set(releases.map((release) => release.ring))).sort(),
    [releases],
  );

  const columns: GridColDef<Release>[] = [
    { field: "releaseRef", headerName: "Release", width: 120 },
    {
      field: "status",
      headerName: "Status",
      width: 170,
      renderCell: (params) => {
        const status = params.row.status;
        return (
          <Chip
            size="small"
            label={RELEASE_STATUS_LABELS[status]}
            color={RELEASE_STATUS_COLORS[status]}
            variant={status === "DRAFT" ? "outlined" : "filled"}
          />
        );
      },
    },
    {
      field: "versionId",
      headerName: "Version",
      width: 130,
      valueGetter: (value: string) => versionNameById.get(value) ?? value,
    },
    { field: "ring", headerName: "Ring", width: 140 },
    {
      field: "environment",
      headerName: "Environment",
      width: 140,
      valueGetter: (value: string | null) => value ?? "—",
    },
    {
      field: "targetCount",
      headerName: "Targets",
      width: 100,
      valueGetter: (value: number) => `${value} tenant${value === 1 ? "" : "s"}`,
    },
    {
      field: "createdAt",
      headerName: "Created",
      type: "dateTime",
      width: 190,
      valueGetter: (value: string) => new Date(value),
    },
    {
      field: "actions",
      headerName: "Actions",
      sortable: false,
      filterable: false,
      width: 110,
      renderCell: (params) => (
        <Button
          size="small"
          onClick={() => navigate(`/releases/${params.row.applicationId}/${params.row.id}`)}
          data-testid={`view-${params.row.id}`}
        >
          View
        </Button>
      ),
    },
  ];

  if (orgId !== null && !directory.loading && directory.applications.length === 0) {
    return (
      <Stack spacing={2}>
        <Typography variant="h5">Releases</Typography>
        <Alert severity="info" data-testid="releases-no-applications">
          No applications are registered for this organization yet. A release distributes one
          version of one application — register an application first.
        </Alert>
      </Stack>
    );
  }

  return (
    <Stack spacing={2}>
      <Stack direction="row" spacing={2} sx={{ alignItems: "center" }}>
        <Typography variant="h5" sx={{ flexGrow: 1 }}>
          Releases
        </Typography>
        <Autocomplete
          options={directory.applications}
          loading={directory.loading}
          getOptionLabel={(option) => option.name}
          isOptionEqualToValue={(option, value) => option.id === value.id}
          value={selectedApplication}
          onChange={(_event, value) => {
            setPickedApplicationId(value?.id ?? null);
            setVersionId(null);
            setTenantId(null);
            setRing(null);
          }}
          sx={{ width: 280 }}
          renderInput={(params) => (
            <TextField {...params} label="Application" required helperText="Releases are per application" />
          )}
          data-testid="release-application-picker"
        />
      </Stack>

      {applicationId !== null ? (
        <Stack direction="row" spacing={2} sx={{ alignItems: "center", flexWrap: "wrap" }}>
          <TextField
            select
            label="Status"
            value={status}
            onChange={(event) => setStatus(event.target.value as Release["status"] | "")}
            sx={{ width: 190 }}
            data-testid="release-status-filter"
          >
            <MenuItem value="">All statuses</MenuItem>
            {RELEASE_STATUSES.map((value) => (
              <MenuItem key={value} value={value}>
                {RELEASE_STATUS_LABELS[value]}
              </MenuItem>
            ))}
          </TextField>
          <Autocomplete
            options={versions.versions}
            loading={versions.loading}
            getOptionLabel={(option) => option.versionName}
            isOptionEqualToValue={(option, value) => option.id === value.id}
            value={versions.versions.find((version) => version.id === versionId) ?? null}
            onChange={(_event, value) => setVersionId(value?.id ?? null)}
            sx={{ width: 180 }}
            renderInput={(params) => <TextField {...params} label="Version" />}
            data-testid="release-version-filter"
          />
          <Autocomplete
            options={tenants.tenants}
            loading={tenants.loading}
            getOptionLabel={(option) => option.name}
            isOptionEqualToValue={(option, value) => option.id === value.id}
            value={tenants.tenants.find((tenant) => tenant.id === tenantId) ?? null}
            onChange={(_event, value) => setTenantId(value?.id ?? null)}
            sx={{ width: 200 }}
            renderInput={(params) => <TextField {...params} label="Target tenant" />}
            data-testid="release-tenant-filter"
          />
          <TextField
            select
            label="Ring"
            value={ring ?? ""}
            onChange={(event) => setRing(event.target.value === "" ? null : event.target.value)}
            sx={{ width: 170 }}
            data-testid="release-ring-filter"
            helperText="Loaded pages"
          >
            <MenuItem value="">All rings</MenuItem>
            {ringOptions.map((code) => (
              <MenuItem key={code} value={code}>
                {code}
              </MenuItem>
            ))}
          </TextField>
          <TextField
            label="Search"
            value={search}
            onChange={(event) => setSearch(event.target.value)}
            sx={{ width: 180 }}
            slotProps={{ htmlInput: { "data-testid": "release-search" } }}
          />
        </Stack>
      ) : null}

      {query.isPending && applicationId !== null ? (
        <Typography aria-busy="true">Loading releases...</Typography>
      ) : null}
      {query.isError ? (
        <Alert severity="error" data-testid="releases-error">
          Failed to load releases. You may not have permission to read this
          application&apos;s releases, or the application does not exist.
        </Alert>
      ) : null}

      {applicationId !== null ? (
        <>
          <div style={{ height: 520, width: "100%" }} data-testid="releases-grid">
            <DataGrid
              rows={visibleReleases(releases, ring, search)}
              columns={columns}
              getRowId={(row: Release) => row.id}
              loading={query.isPending}
              density="compact"
              disableRowSelectionOnClick
              hideFooter
            />
          </div>
          {query.hasNextPage ? (
            <Button
              onClick={() => void query.fetchNextPage()}
              disabled={query.isFetchingNextPage}
              data-testid="releases-load-more"
            >
              {query.isFetchingNextPage ? "Loading..." : "Load more"}
            </Button>
          ) : null}
          {!query.isPending && releases.length === 0 && !query.isError ? (
            <Alert severity="info" data-testid="releases-empty">
              No releases yet for this application. A release is created from one version&apos;s
              artifacts targeting tenants, tester groups, and a ring (the S9-01 API; the console
              create dialog is a later story).
            </Alert>
          ) : null}
        </>
      ) : null}
    </Stack>
  );
}
