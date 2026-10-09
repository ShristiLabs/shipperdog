/**
 * Tenant device registry (issue #48, S11-03, FR-028, PRD 8.12): the
 * testers' devices of one tenant — registered by hand (the M2 answer:
 * a UDID cannot be captured from a web page; the mobileconfig profile
 * is a documented follow-up), listed with their owners, descriptively
 * editable, reversibly parked, and exported as the store-side
 * provisioning CSV (identifier, platform, model, OS, owner email).
 * Devices are never deleted: deactivation is the reversible park, and
 * a deactivated tester's records stay (the INV-007 history posture).
 */
import ArrowBackIcon from "@mui/icons-material/ArrowBack";
import DevicesIcon from "@mui/icons-material/Devices";
import DownloadIcon from "@mui/icons-material/Download";
import {
  Alert,
  Autocomplete,
  Button,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  MenuItem,
  Stack,
  TextField,
  Tooltip,
  Typography,
} from "@mui/material";
import { DataGrid, type GridColDef } from "@mui/x-data-grid";
import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Link, useParams } from "react-router-dom";
import {
  changeTenantDeviceStatus,
  exportTenantDevicesCsv,
  listTenantDevices,
  registerTenantDevice,
  updateTenantDevice,
  type DeviceItem,
  type DevicePlatform,
  type DeviceStatus,
} from "../api/devices";
import { getTenant, listTenantTesters, type TenantTesterMembership } from "../api/tenants";
import { useToast } from "../components/ToastContext";

const STATUS_COLORS: Record<DeviceStatus, "success" | "default"> = {
  ACTIVE: "success",
  DEACTIVATED: "default",
};

export default function TenantDevicesPage() {
  const { tenantId } = useParams<{ tenantId: string }>();
  const tenantIdSafe = tenantId ?? "";
  const toast = useToast();
  const queryClient = useQueryClient();
  const [registerOpen, setRegisterOpen] = useState(false);
  const [editTarget, setEditTarget] = useState<DeviceItem | null>(null);
  const [platformFilter, setPlatformFilter] = useState("");
  const [statusFilter, setStatusFilter] = useState("");

  const tenant = useQuery({
    queryKey: ["tenant", tenantIdSafe],
    queryFn: () => getTenant(tenantIdSafe),
    enabled: tenantIdSafe !== "",
  });

  const devices = useInfiniteQuery({
    queryKey: ["tenant-devices", tenantIdSafe, platformFilter, statusFilter],
    queryFn: ({ pageParam }) =>
      listTenantDevices(tenantIdSafe, {
        limit: 50,
        cursor: pageParam,
        ...(platformFilter === "" ? {} : { platform: platformFilter as DevicePlatform }),
        ...(statusFilter === "" ? {} : { status: statusFilter as DeviceStatus }),
      }),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (lastPage) => lastPage.nextCursor,
    enabled: tenantIdSafe !== "",
  });
  const rows = devices.data?.pages.flatMap((page) => page.items) ?? [];

  function invalidate() {
    void queryClient.invalidateQueries({ queryKey: ["tenant-devices", tenantIdSafe] });
  }

  const register = useMutation({
    mutationFn: (input: {
      testerId: string;
      platform: DevicePlatform;
      model?: string;
      osVersion?: string;
      identifier: string;
    }) => registerTenantDevice(tenantIdSafe, input),
    onSuccess: (device) => {
      invalidate();
      setRegisterOpen(false);
      toast.showSuccess(`Device ${device.identifier} registered`);
    },
    onError: (error) => toast.showError(error instanceof Error ? error.message : "Registration failed"),
  });

  const edit = useMutation({
    mutationFn: (input: { deviceId: string; model: string; osVersion: string }) =>
      updateTenantDevice(tenantIdSafe, input.deviceId, { model: input.model, osVersion: input.osVersion }),
    onSuccess: (device) => {
      invalidate();
      setEditTarget(null);
      toast.showSuccess(`Device ${device.identifier} updated`);
    },
    onError: (error) => toast.showError(error instanceof Error ? error.message : "Update failed"),
  });

  const statusChange = useMutation({
    mutationFn: (input: { deviceId: string; status: DeviceStatus }) =>
      changeTenantDeviceStatus(tenantIdSafe, input.deviceId, input.status),
    onSuccess: (device) => {
      invalidate();
      toast.showSuccess(`Device ${device.identifier} is ${device.status.toLowerCase()}`);
    },
    onError: (error) => toast.showError(error instanceof Error ? error.message : "Status change failed"),
  });

  const exportCsv = useMutation({
    mutationFn: () => exportTenantDevicesCsv(tenantIdSafe),
    onSuccess: (csv) => {
      const blob = new Blob([csv], { type: "text/csv" });
      const url = URL.createObjectURL(blob);
      const anchor = document.createElement("a");
      anchor.href = url;
      anchor.download = `devices-${tenantIdSafe}.csv`;
      anchor.click();
      URL.revokeObjectURL(url);
      toast.showSuccess("Provisioning CSV downloaded");
    },
    onError: (error) => toast.showError(error instanceof Error ? error.message : "Export failed"),
  });

  const busy = register.isPending || edit.isPending || statusChange.isPending;

  const columns: GridColDef<DeviceItem>[] = [
    { field: "identifier", headerName: "Identifier", width: 260 },
    { field: "platform", headerName: "Platform", width: 110 },
    { field: "model", headerName: "Model", width: 180 },
    { field: "osVersion", headerName: "OS", width: 110 },
    { field: "ownerEmail", headerName: "Owner", width: 240 },
    {
      field: "status",
      headerName: "Status",
      width: 140,
      renderCell: (params) => (
        <Chip
          size="small"
          color={STATUS_COLORS[params.row.status] ?? "default"}
          label={params.row.status.toLowerCase()}
          data-testid={`device-status-${params.row.id}`}
        />
      ),
    },
    {
      field: "actions",
      headerName: "Actions",
      sortable: false,
      filterable: false,
      width: 260,
      renderCell: (params) => {
        const device = params.row;
        return (
          <Stack direction="row" spacing={1}>
            <Button size="small" disabled={busy} onClick={() => setEditTarget(device)} data-testid={`edit-${device.id}`}>
              Edit
            </Button>
            {device.status === "ACTIVE" ? (
              <Button
                size="small"
                color="warning"
                disabled={busy}
                onClick={() => statusChange.mutate({ deviceId: device.id, status: "DEACTIVATED" })}
                data-testid={`deactivate-${device.id}`}
              >
                Deactivate
              </Button>
            ) : (
              <Button
                size="small"
                color="success"
                disabled={busy}
                onClick={() => statusChange.mutate({ deviceId: device.id, status: "ACTIVE" })}
                data-testid={`reactivate-${device.id}`}
              >
                Reactivate
              </Button>
            )}
          </Stack>
        );
      },
    },
  ];

  return (
    <Stack spacing={2}>
      <Stack direction="row" spacing={1} sx={{ alignItems: "center" }}>
        <Tooltip title="Back to tenants">
          <Button component={Link} to="/tenants" startIcon={<ArrowBackIcon />} aria-label="Back to tenants" />
        </Tooltip>
        <Typography variant="h5" sx={{ flexGrow: 1 }} data-testid="devices-title">
          Devices {tenant.data ? `· ${tenant.data.name}` : ""}
        </Typography>
      </Stack>
      <Typography variant="body2" color="text.secondary">
        Tester devices of this tenant: register by hand (a UDID cannot be captured from a web page), export the
        provisioning CSV for store-side lists. Devices are never deleted — deactivation parks a device reversibly and
        keeps every record.
      </Typography>
      <Stack direction="row" spacing={2} sx={{ alignItems: "center", flexWrap: "wrap" }}>
        <Button
          variant="contained"
          startIcon={<DevicesIcon />}
          onClick={() => setRegisterOpen(true)}
          data-testid="register-device"
        >
          Register device
        </Button>
        <Button
          variant="outlined"
          startIcon={<DownloadIcon />}
          disabled={exportCsv.isPending}
          onClick={() => exportCsv.mutate()}
          data-testid="export-devices"
        >
          Export CSV
        </Button>
        <TextField
          select
          size="small"
          label="Platform"
          sx={{ minWidth: 130 }}
          value={platformFilter}
          onChange={(event) => setPlatformFilter(event.target.value)}
        >
          <MenuItem value="">All</MenuItem>
          <MenuItem value="IOS">iOS</MenuItem>
          <MenuItem value="ANDROID">Android</MenuItem>
        </TextField>
        <TextField
          select
          size="small"
          label="Status"
          sx={{ minWidth: 140 }}
          value={statusFilter}
          onChange={(event) => setStatusFilter(event.target.value)}
        >
          <MenuItem value="">All</MenuItem>
          <MenuItem value="ACTIVE">Active</MenuItem>
          <MenuItem value="DEACTIVATED">Deactivated</MenuItem>
        </TextField>
      </Stack>
      {devices.isError ? (
        <Alert severity="error">Failed to load the device registry.</Alert>
      ) : (
        <div style={{ height: 520, width: "100%" }} data-testid="device-grid">
          <DataGrid
            rows={rows}
            columns={columns}
            getRowId={(row) => row.id}
            loading={devices.isPending}
            density="compact"
            disableRowSelectionOnClick
            hideFooter
          />
        </div>
      )}
      {devices.hasNextPage ? (
        <Button
          onClick={() => void devices.fetchNextPage()}
          disabled={devices.isFetchingNextPage}
          data-testid="devices-load-more"
        >
          {devices.isFetchingNextPage ? "Loading..." : "Load more"}
        </Button>
      ) : null}
      {registerOpen ? (
        <RegisterDialog
          tenantId={tenantIdSafe}
          busy={register.isPending}
          onCancel={() => setRegisterOpen(false)}
          onSubmit={(input) => register.mutate(input)}
        />
      ) : null}
      {editTarget ? (
        <EditDialog
          device={editTarget}
          busy={edit.isPending}
          onCancel={() => setEditTarget(null)}
          onSubmit={(model, osVersion) => edit.mutate({ deviceId: editTarget.id, model, osVersion })}
        />
      ) : null}
    </Stack>
  );
}

/** The registration form (AC1): the owner (a linked tester of the
 * tenant), the platform, the optional descriptive fields, and the
 * identifier — unique within the tenant; a duplicate is the server's
 * 409, surfaced as the toast. */
function RegisterDialog({
  tenantId,
  busy,
  onCancel,
  onSubmit,
}: {
  tenantId: string;
  busy: boolean;
  onCancel: () => void;
  onSubmit: (input: {
    testerId: string;
    platform: DevicePlatform;
    model?: string;
    osVersion?: string;
    identifier: string;
  }) => void;
}) {
  const testers = useInfiniteQuery({
    queryKey: ["tenant-testers", tenantId],
    queryFn: ({ pageParam }) => listTenantTesters(tenantId, { status: "ALL", limit: 50, cursor: pageParam }),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (lastPage) => lastPage.nextCursor,
  });
  const [owner, setOwner] = useState<TenantTesterMembership | null>(null);
  const [platform, setPlatform] = useState<DevicePlatform>("IOS");
  const [model, setModel] = useState("");
  const [osVersion, setOsVersion] = useState("");
  const [identifier, setIdentifier] = useState("");

  return (
    <Dialog open onClose={onCancel} fullWidth data-testid="register-dialog">
      <DialogTitle>Register device</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ marginTop: 1 }}>
          <Autocomplete
            options={testers.data?.pages.flatMap((page) => page.items) ?? []}
            getOptionLabel={(tester) => `${tester.fullName} (${tester.email})`}
            isOptionEqualToValue={(option, value) => option.testerId === value.testerId}
            value={owner}
            onChange={(_event, tester) => setOwner(tester)}
            loading={testers.isPending}
            renderInput={(params) => <TextField {...params} label="Owner (linked tester)" required />}
          />
          <TextField
            select
            label="Platform"
            required
            value={platform}
            onChange={(event) => setPlatform(event.target.value as DevicePlatform)}
          >
            <MenuItem value="IOS">iOS</MenuItem>
            <MenuItem value="ANDROID">Android</MenuItem>
          </TextField>
          <TextField label="Model" value={model} onChange={(event) => setModel(event.target.value)} />
          <TextField
            label="OS version"
            value={osVersion}
            onChange={(event) => setOsVersion(event.target.value)}
          />
          <TextField
            label="Identifier (UDID / serial)"
            required
            value={identifier}
            onChange={(event) => setIdentifier(event.target.value)}
            helperText="Unique within the tenant"
          />
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onCancel}>Cancel</Button>
        <Button
          variant="contained"
          disabled={busy || owner === null || identifier.trim() === ""}
          onClick={() => {
            if (owner === null) return;
            onSubmit({
              testerId: owner.testerId,
              platform,
              model: model.trim() === "" ? undefined : model.trim(),
              osVersion: osVersion.trim() === "" ? undefined : osVersion.trim(),
              identifier: identifier.trim(),
            });
          }}
          data-testid="register-submit"
        >
          Register
        </Button>
      </DialogActions>
    </Dialog>
  );
}

/** The descriptive edit (AC2): the model and OS version alone — the
 * identifier and platform are the device's identity and never move. */
function EditDialog({
  device,
  busy,
  onCancel,
  onSubmit,
}: {
  device: DeviceItem;
  busy: boolean;
  onCancel: () => void;
  onSubmit: (model: string, osVersion: string) => void;
}) {
  const [model, setModel] = useState(device.model ?? "");
  const [osVersion, setOsVersion] = useState(device.osVersion ?? "");
  return (
    <Dialog open onClose={onCancel} fullWidth data-testid="edit-dialog">
      <DialogTitle>Edit {device.identifier}</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ marginTop: 1 }}>
          <Typography variant="body2" color="text.secondary">
            {device.platform} · owner {device.ownerEmail ?? device.testerId} — the identifier and platform never
            change.
          </Typography>
          <TextField label="Model" value={model} onChange={(event) => setModel(event.target.value)} />
          <TextField label="OS version" value={osVersion} onChange={(event) => setOsVersion(event.target.value)} />
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onCancel}>Cancel</Button>
        <Button
          variant="contained"
          disabled={busy}
          onClick={() => onSubmit(model.trim() === "" ? "" : model.trim(), osVersion.trim() === "" ? "" : osVersion.trim())}
          data-testid="edit-submit"
        >
          Save
        </Button>
      </DialogActions>
    </Dialog>
  );
}
