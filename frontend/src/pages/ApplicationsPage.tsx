/**
 * Application registry list screen (issue #16): two views of the same
 * registry. "All applications" is the organization-wide administration
 * listing (GET /applications?organizationId=... — every application in
 * both ownership scopes, including shared applications no tenant is
 * authorized for yet, which every effective-visibility listing hides by
 * construction); "By tenant" resolves a tenant's effective visibility
 * (GET /tenants/{id}/applications: owned plus authorized-shared,
 * S3-03 AC4). The grid follows the Tenants conventions: client-side
 * search over the loaded cursor pages, Load more, and a validated
 * create dialog whose rules mirror the API.
 */
import AddIcon from "@mui/icons-material/Add";
import {
  Alert,
  Autocomplete,
  Button,
  Chip,
  Stack,
  TextField,
  ToggleButton,
  ToggleButtonGroup,
  Typography,
} from "@mui/material";
import { DataGrid, type GridColDef } from "@mui/x-data-grid";
import { useInfiniteQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { useNavigate } from "react-router-dom";
import {
  createApplication,
  listApplications,
  listTenantApplications,
  type Application,
  type ApplicationPlatform,
} from "../api/applications";
import { useAuth } from "../auth/AuthContext";
import { useToast } from "../components/ToastContext";
import { useAllTenants } from "../hooks/useOrganizationDirectory";
import ApplicationDialog, { type ApplicationFormValue } from "./ApplicationDialog";

const PLATFORM_LABELS: Record<ApplicationPlatform, string> = {
  IOS: "iOS",
  ANDROID: "Android",
};

type ListingMode = "organization" | "tenant";

/** Client-side search over the loaded cursor pages (name or either
 * platform identifier, case-insensitive). */
export function visibleApplications(applications: Application[], search: string): Application[] {
  const needle = search.trim().toLowerCase();
  if (needle === "") return applications;
  return applications.filter(
    (application) =>
      application.name.toLowerCase().includes(needle) ||
      (application.bundleId ?? "").toLowerCase().includes(needle) ||
      (application.packageName ?? "").toLowerCase().includes(needle),
  );
}

export default function ApplicationsPage() {
  const { user } = useAuth();
  const orgId = user?.orgId ?? null;
  const toast = useToast();
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const directory = useAllTenants(orgId);
  const [mode, setMode] = useState<ListingMode>("organization");
  const [pickedTenantId, setPickedTenantId] = useState<string | null>(null);
  const [search, setSearch] = useState("");
  const [createOpen, setCreateOpen] = useState(false);

  // In tenant mode the listing needs a tenant; nothing picked yet falls
  // through to the first tenant in the picker (derived, not an effect).
  const tenantId = pickedTenantId ?? directory.tenants[0]?.id ?? null;
  const tenantModeReady = mode === "tenant" && tenantId !== null;

  const query = useInfiniteQuery({
    queryKey: ["applications", mode, tenantModeReady ? tenantId : orgId],
    queryFn: ({ pageParam }) =>
      mode === "organization"
        ? listApplications({ organizationId: orgId as string, limit: 50, cursor: pageParam })
        : listTenantApplications(tenantId as string, { limit: 50, cursor: pageParam }),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (lastPage) => lastPage.nextCursor,
    enabled: mode === "organization" ? orgId !== null : tenantModeReady,
  });
  const applications = query.data?.pages.flatMap((page) => page.items) ?? [];

  function invalidate() {
    void queryClient.invalidateQueries({ queryKey: ["applications"] });
  }

  const create = useMutation({
    mutationFn: (value: ApplicationFormValue) =>
      createApplication({
        ownershipScope: value.ownershipScope,
        ...(value.ownershipScope === "TENANT"
          ? { tenantId: value.ownerTenantId as string }
          : { organizationId: orgId as string }),
        name: value.name,
        ...(value.description !== "" ? { description: value.description } : {}),
        platforms: value.platforms,
        ...(value.bundleId !== "" ? { bundleId: value.bundleId } : {}),
        ...(value.packageName !== "" ? { packageName: value.packageName } : {}),
      }),
    onSuccess: (application) => {
      invalidate();
      toast.showSuccess(`Application ${application.name} registered`);
    },
    onError: toast.showError,
  });

  const columns: GridColDef<Application>[] = [
    { field: "name", headerName: "Name", flex: 1, minWidth: 200 },
    {
      field: "platforms",
      headerName: "Platforms",
      width: 180,
      renderCell: (params) => (
        <Stack direction="row" spacing={0.5} sx={{ alignItems: "center", height: "100%" }}>
          {params.value.map((platform: ApplicationPlatform) => (
            <Chip key={platform} size="small" variant="outlined" label={PLATFORM_LABELS[platform]} />
          ))}
        </Stack>
      ),
    },
    {
      field: "ownershipScope",
      headerName: "Ownership",
      width: 140,
      renderCell: (params) => (
        <Chip
          size="small"
          label={params.value === "TENANT" ? "Tenant" : "Shared"}
          color={params.value === "TENANT" ? "default" : "info"}
          variant={params.value === "TENANT" ? "outlined" : "filled"}
        />
      ),
    },
    { field: "bundleId", headerName: "Bundle ID", flex: 1, minWidth: 200 },
    { field: "packageName", headerName: "Package name", flex: 1, minWidth: 200 },
    {
      field: "status",
      headerName: "Status",
      width: 130,
      renderCell: (params) => (
        <Chip
          size="small"
          label={params.value === "ACTIVE" ? "Active" : "Archived"}
          color={params.value === "ACTIVE" ? "success" : "default"}
          variant={params.value === "ACTIVE" ? "filled" : "outlined"}
        />
      ),
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
          onClick={() => navigate(`/applications/${params.row.id}`)}
          data-testid={`view-${params.row.id}`}
        >
          View
        </Button>
      ),
    },
  ];

  const selectedTenant = directory.tenants.find((tenant) => tenant.id === tenantId) ?? null;

  return (
    <Stack spacing={2}>
      <Stack direction="row" spacing={2} sx={{ alignItems: "center" }}>
        <Typography variant="h5" sx={{ flexGrow: 1 }}>
          Applications
        </Typography>
        <ToggleButtonGroup
          value={mode}
          exclusive
          onChange={(_event, value: ListingMode | null) => {
            if (value !== null) setMode(value);
          }}
          size="small"
          aria-label="Listing scope"
          data-testid="application-mode"
        >
          <ToggleButton value="organization" data-testid="application-mode-all">
            All applications
          </ToggleButton>
          <ToggleButton value="tenant" data-testid="application-mode-tenant">
            By tenant
          </ToggleButton>
        </ToggleButtonGroup>
        <Autocomplete
          options={directory.tenants}
          loading={directory.loading}
          getOptionLabel={(option) => option.name}
          isOptionEqualToValue={(option, value) => option.id === value.id}
          value={selectedTenant}
          onChange={(_event, value) => setPickedTenantId(value?.id ?? null)}
          sx={{ width: 260 }}
          disabled={mode !== "tenant"}
          renderInput={(params) => (
            <TextField
              {...params}
              label="Tenant (effective visibility)"
              helperText="Owned applications plus authorized shared applications"
            />
          )}
          data-testid="application-tenant-picker"
        />
        <TextField
          label="Search"
          value={search}
          onChange={(event) => setSearch(event.target.value)}
          sx={{ width: 220 }}
          slotProps={{ htmlInput: { "data-testid": "application-search" } }}
        />
        <Button
          variant="contained"
          startIcon={<AddIcon />}
          onClick={() => setCreateOpen(true)}
          disabled={orgId === null}
          data-testid="create-application-button"
        >
          New application
        </Button>
      </Stack>

      {query.isPending ? (
        <Typography aria-busy="true">Loading applications...</Typography>
      ) : null}
      {query.isError ? (
        <Alert severity="error" data-testid="applications-error">
          Failed to load applications.
        </Alert>
      ) : null}
      {mode === "tenant" && tenantId === null && !directory.loading ? (
        <Alert severity="info">Pick a tenant to list its visible applications.</Alert>
      ) : null}

      <div style={{ height: 520, width: "100%" }} data-testid="applications-grid">
        <DataGrid
          rows={visibleApplications(applications, search)}
          columns={columns}
          getRowId={(row: Application) => row.id}
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
          data-testid="applications-load-more"
        >
          {query.isFetchingNextPage ? "Loading..." : "Load more"}
        </Button>
      ) : null}

      <ApplicationDialog
        open={createOpen}
        title="Register application"
        submitLabel="Register"
        mode="create"
        defaultOwnerTenantId={tenantId}
        tenants={directory.tenants}
        tenantsLoading={directory.loading}
        onClose={() => setCreateOpen(false)}
        onSubmit={async (value) => {
          await create.mutateAsync(value);
        }}
      />
    </Stack>
  );
}
