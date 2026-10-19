/**
 * Tenant administration screen (issue #12): the organization's tenants
 * in an MUI data grid with quick-search over loaded rows and a
 * server-side status filter, cursor-paginated through Load more
 * (docs/api-conventions.md). Create and edit run through validated
 * dialogs; deactivation — reversible per the lifecycle — still demands
 * an explicit confirmation naming the tenant.
 */
import AddIcon from "@mui/icons-material/Add";
import DevicesIcon from "@mui/icons-material/Devices";
import SpaceDashboardIcon from "@mui/icons-material/SpaceDashboard";
import SystemUpdateAltIcon from "@mui/icons-material/SystemUpdateAlt";
import GroupsIcon from "@mui/icons-material/Groups";
import {
  Alert,
  Button,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogContentText,
  DialogTitle,
  MenuItem,
  Stack,
  TextField,
  Tooltip,
  Typography,
} from "@mui/material";
import { DataGrid, type GridColDef } from "@mui/x-data-grid";
import { useInfiniteQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { createTenant, listTenants, updateTenant, type Tenant, type TenantStatus } from "../api/tenants";
import { useAuth } from "../auth/AuthContext";
import { useToast } from "../components/ToastContext";
import { validateTenantExternalId, validateTenantName } from "./tenantRules";

const STATUS_FILTERS: ReadonlyArray<{ value: TenantStatus; label: string }> = [
  { value: "ACTIVE", label: "Active" },
  { value: "DEACTIVATED", label: "Deactivated" },
];

/** Client-side search over the loaded cursor pages (name or external ID,
 * case-insensitive). The server-side status filter stays a query param. */
export function visibleRows(tenants: Tenant[], search: string): Tenant[] {
  const needle = search.trim().toLowerCase();
  if (needle === "") return tenants;
  return tenants.filter(
    (tenant) =>
      tenant.name.toLowerCase().includes(needle) ||
      tenant.externalId.toLowerCase().includes(needle),
  );
}

export default function TenantsPage() {
  const { user } = useAuth();
  const orgId = user?.orgId ?? null;
  const [statusFilter, setStatusFilter] = useState<TenantStatus | "ALL">("ALL");
  const [search, setSearch] = useState("");
  const [createOpen, setCreateOpen] = useState(false);
  const [editTarget, setEditTarget] = useState<Tenant | null>(null);
  const [deactivateTarget, setDeactivateTarget] = useState<Tenant | null>(null);
  const navigate = useNavigate();

  const query = useInfiniteQuery({
    queryKey: ["tenants", orgId, statusFilter],
    enabled: orgId !== null,
    queryFn: ({ pageParam }) =>
      listTenants({
        organizationId: orgId as string,
        status: statusFilter === "ALL" ? undefined : statusFilter,
        limit: 50,
        cursor: pageParam,
      }),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (lastPage) => lastPage.nextCursor,
  });
  const tenants = query.data?.pages.flatMap((page) => page.items) ?? [];

  const columns: GridColDef<Tenant>[] = [
    { field: "name", headerName: "Name", flex: 1, minWidth: 180 },
    { field: "externalId", headerName: "External ID", flex: 1, minWidth: 160 },
    {
      field: "status",
      headerName: "Status",
      width: 140,
      renderCell: (params) => (
        <Chip
          size="small"
          label={params.value === "ACTIVE" ? "Active" : "Deactivated"}
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
      width: 470,
      renderCell: (params) => {
        const tenant = params.row;
        return (
          <Stack direction="row" spacing={1}>
            <Button size="small" onClick={() => setEditTarget(tenant)} data-testid={`edit-${tenant.id}`}>
              Edit
            </Button>
            <Tooltip title="Manage members">
              <Button
                size="small"
                startIcon={<GroupsIcon />}
                onClick={() => navigate(`/tenants/${tenant.id}/members`)}
                data-testid={`members-${tenant.id}`}
              >
                Members
              </Button>
            </Tooltip>
            <Tooltip title="Device registry">
              <Button
                size="small"
                startIcon={<DevicesIcon />}
                onClick={() => navigate(`/tenants/${tenant.id}/devices`)}
                data-testid={`devices-${tenant.id}`}
              >
                Devices
              </Button>
            </Tooltip>
            <Tooltip title="Tenant dashboard">
              <Button
                size="small"
                startIcon={<SpaceDashboardIcon />}
                onClick={() => navigate(`/tenants/${tenant.id}/dashboard`)}
                data-testid={`dashboard-${tenant.id}`}
              >
                Dashboard
              </Button>
            </Tooltip>
            <Tooltip title="OTA dashboard (S16-15)">
              <Button
                size="small"
                startIcon={<SystemUpdateAltIcon />}
                onClick={() => navigate(`/ota/dashboard/${tenant.id}`)}
                data-testid={`ota-dashboard-${tenant.id}`}
              >
                OTA
              </Button>
            </Tooltip>
            {tenant.status === "ACTIVE" ? (
              <Button
                size="small"
                color="warning"
                onClick={() => setDeactivateTarget(tenant)}
                data-testid={`deactivate-${tenant.id}`}
              >
                Deactivate
              </Button>
            ) : (
              <Button
                size="small"
                color="success"
                onClick={() => void reactivate.mutateAsync(tenant)}
                data-testid={`reactivate-${tenant.id}`}
              >
                Reactivate
              </Button>
            )}
          </Stack>
        );
      },
    },
  ];

  const toast = useToast();
  const queryClient = useQueryClient();

  function invalidate() {
    void queryClient.invalidateQueries({ queryKey: ["tenants"] });
  }

  const reactivate = useMutation({
    mutationFn: (tenant: Tenant) => updateTenant(tenant.id, { status: "ACTIVE" }),
    onSuccess: (tenant) => {
      invalidate();
      toast.showSuccess(`Tenant ${tenant.name} reactivated`);
    },
    onError: toast.showError,
  });

  const deactivate = useMutation({
    mutationFn: (tenant: Tenant) => updateTenant(tenant.id, { status: "DEACTIVATED" }),
    onSuccess: (tenant) => {
      invalidate();
      toast.showSuccess(`Tenant ${tenant.name} deactivated`);
    },
    onError: toast.showError,
  });

  return (
    <Stack spacing={2}>
      <Stack direction="row" spacing={2} sx={{ alignItems: "center" }}>
        <Typography variant="h5" sx={{ flexGrow: 1 }}>
          Tenants
        </Typography>
        <TextField
          label="Search"
          value={search}
          onChange={(event) => setSearch(event.target.value)}
          sx={{ width: 260 }}
          slotProps={{ htmlInput: { "data-testid": "tenant-search" } }}
        />
        <TextField
          select
          label="Status"
          value={statusFilter}
          onChange={(event) => setStatusFilter(event.target.value as TenantStatus | "ALL")}
          sx={{ width: 180 }}
          data-testid="tenant-status-filter"
        >
          <MenuItem value="ALL">All</MenuItem>
          {STATUS_FILTERS.map((option) => (
            <MenuItem key={option.value} value={option.value}>
              {option.label}
            </MenuItem>
          ))}
        </TextField>
        <Button
          variant="contained"
          startIcon={<AddIcon />}
          onClick={() => setCreateOpen(true)}
          disabled={orgId === null}
          data-testid="create-tenant-button"
        >
          New tenant
        </Button>
      </Stack>

      {query.isPending ? <Typography aria-busy="true">Loading tenants...</Typography> : null}
      {query.isError ? (
        <Alert severity="error" data-testid="tenants-error">
          Failed to load tenants.
        </Alert>
      ) : null}

      <div style={{ height: 520, width: "100%" }} data-testid="tenants-grid">
        <DataGrid
          rows={visibleRows(tenants, search)}
          columns={columns}
          getRowId={(row: Tenant) => row.id}
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
          data-testid="tenants-load-more"
        >
          {query.isFetchingNextPage ? "Loading..." : "Load more"}
        </Button>
      ) : null}

      <TenantDialog
        open={createOpen}
        title="Create tenant"
        submitLabel="Create"
        onClose={() => setCreateOpen(false)}
        onSubmit={async (name, externalId) => {
          if (!orgId) return;
          await createTenant({ organizationId: orgId, name, externalId });
          invalidate();
          toast.showSuccess(`Tenant ${name} created`);
        }}
      />
      <TenantDialog
        open={editTarget !== null}
        title={`Edit tenant ${editTarget?.name ?? ""}`}
        submitLabel="Save"
        initialName={editTarget?.name ?? ""}
        initialExternalId={editTarget?.externalId ?? ""}
        onClose={() => setEditTarget(null)}
        onSubmit={async (name, externalId) => {
          if (!editTarget) return;
          await updateTenant(editTarget.id, { name, externalId });
          invalidate();
          toast.showSuccess(`Tenant ${name} updated`);
        }}
      />

      <Dialog open={deactivateTarget !== null} onClose={() => setDeactivateTarget(null)}>
        <DialogTitle>Deactivate tenant?</DialogTitle>
        <DialogContent>
          <DialogContentText>
            Tenants are never deleted: deactivation blocks new memberships but keeps
            all data, members, and applications, and can be reversed later. Confirm
            deactivation of <strong>{deactivateTarget?.name}</strong>.
          </DialogContentText>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setDeactivateTarget(null)}>Cancel</Button>
          <Button
            color="warning"
            variant="contained"
            data-testid="confirm-deactivate"
            onClick={() => {
              if (deactivateTarget) void deactivate.mutateAsync(deactivateTarget);
              setDeactivateTarget(null);
            }}
          >
            Deactivate
          </Button>
        </DialogActions>
      </Dialog>
    </Stack>
  );
}

interface TenantDialogProps {
  open: boolean;
  title: string;
  submitLabel: string;
  initialName?: string;
  initialExternalId?: string;
  onClose: () => void;
  onSubmit: (name: string, externalId: string) => Promise<void>;
}

/** Shared create/edit form; field rules mirror the API (tenantRules). */
function TenantDialog({
  open,
  title,
  submitLabel,
  initialName = "",
  initialExternalId = "",
  onClose,
  onSubmit,
}: TenantDialogProps) {
  const toast = useToast();
  const [name, setName] = useState(initialName);
  const [externalId, setExternalId] = useState(initialExternalId);
  const [touched, setTouched] = useState(false);
  const [submitting, setSubmitting] = useState(false);

  const nameError = touched ? validateTenantName(name) : undefined;
  const externalIdError = touched ? validateTenantExternalId(externalId) : undefined;
  const valid = validateTenantName(name) === undefined && validateTenantExternalId(externalId) === undefined;

  // Re-seed the form whenever a different tenant opens the dialog.
  const [seed, setSeed] = useState(`${initialName}/${initialExternalId}`);
  const currentSeed = `${initialName}/${initialExternalId}`;
  if (open && seed !== currentSeed) {
    setSeed(currentSeed);
    setName(initialName);
    setExternalId(initialExternalId);
    setTouched(false);
  }

  async function handleSubmit() {
    setTouched(true);
    if (!valid) return;
    setSubmitting(true);
    try {
      await onSubmit(name.trim(), externalId.trim());
      onClose();
      setTouched(false);
    } catch (error) {
      toast.showError(error);
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>{title}</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ marginTop: 1 }}>
          <TextField
            label="Name"
            value={name}
            onChange={(event) => setName(event.target.value)}
            error={nameError !== undefined}
            helperText={nameError ?? "Within-organization identifier (1-200 characters)"}
            required
            slotProps={{ htmlInput: { "data-testid": "tenant-name" } }}
          />
          <TextField
            label="External ID"
            value={externalId}
            onChange={(event) => setExternalId(event.target.value)}
            error={externalIdError !== undefined}
            helperText={externalIdError ?? "Globally unique customer-system mapping (1-100 characters)"}
            required
            slotProps={{ htmlInput: { "data-testid": "tenant-external-id" } }}
          />
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button
          variant="contained"
          onClick={() => void handleSubmit()}
          disabled={submitting || (touched && !valid)}
          data-testid="tenant-submit"
        >
          {submitting ? "Saving..." : submitLabel}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
