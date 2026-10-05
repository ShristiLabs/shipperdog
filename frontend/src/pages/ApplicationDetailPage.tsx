/**
 * Application detail shell (issue #16): registry metadata with the
 * validated edit dialog (including the audited ownership-scope
 * transition), the shared-application tenant authorization screen
 * (PRD 15 — grant/revoke with an optional reason, S3-03), the
 * reversible archive lifecycle behind a confirmation naming the
 * application, and section shells for versions and artifacts that
 * render honest empty states until E4/E5 deliver them — no dead
 * links. grantedBy is a raw UUID (no user-name port yet, issue #15
 * follow-up): the console resolves it through the organization roster
 * when it can and shows the UUID otherwise.
 */
import ArrowBackIcon from "@mui/icons-material/ArrowBack";
import ArchiveIcon from "@mui/icons-material/Archive";
import EditIcon from "@mui/icons-material/Edit";
import UnarchiveIcon from "@mui/icons-material/Unarchive";
import {
  Alert,
  Autocomplete,
  Button,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Paper,
  Stack,
  TextField,
  Tooltip,
  Typography,
} from "@mui/material";
import { DataGrid, type GridColDef } from "@mui/x-data-grid";
import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useMemo, useState } from "react";
import { Link, useParams } from "react-router-dom";
import {
  authorizeTenant,
  deauthorizeTenant,
  getApplication,
  listAuthorizedTenants,
  updateApplication,
  type ApplicationAuthorization,
  type ApplicationPlatform,
} from "../api/applications";
import type { Tenant } from "../api/tenants";
import { useAuth } from "../auth/AuthContext";
import { useToast } from "../components/ToastContext";
import { useAllOrgMembers, useAllTenants } from "../hooks/useOrganizationDirectory";
import ApplicationDialog, { type ApplicationFormValue } from "./ApplicationDialog";
import { validateReason } from "./applicationRules";

const PLATFORM_LABELS: Record<ApplicationPlatform, string> = {
  IOS: "iOS",
  ANDROID: "Android",
};

export default function ApplicationDetailPage() {
  const { applicationId } = useParams<{ applicationId: string }>();
  const applicationIdSafe = applicationId ?? "";
  const { user } = useAuth();
  const orgId = user?.orgId ?? null;
  const toast = useToast();
  const queryClient = useQueryClient();
  const [editOpen, setEditOpen] = useState(false);
  const [archiveOpen, setArchiveOpen] = useState(false);
  const [grantOpen, setGrantOpen] = useState(false);
  const [revokeTarget, setRevokeTarget] = useState<ApplicationAuthorization | null>(null);

  const application = useQuery({
    queryKey: ["application", applicationIdSafe],
    queryFn: () => getApplication(applicationIdSafe),
    enabled: applicationIdSafe !== "",
  });
  const app = application.data ?? null;
  const shared = app?.ownershipScope === "ORGANIZATION";

  const directory = useAllTenants(orgId);
  const roster = useAllOrgMembers(orgId);

  const tenantNameById = useMemo(
    () => new Map(directory.tenants.map((tenant) => [tenant.id, tenant.name])),
    [directory.tenants],
  );
  const memberNameById = useMemo(
    () => new Map(roster.members.map((member) => [member.userId, member.fullName])),
    [roster.members],
  );

  const authorizations = useInfiniteQuery({
    queryKey: ["app-authorizations", applicationIdSafe],
    queryFn: ({ pageParam }) =>
      listAuthorizedTenants(applicationIdSafe, { limit: 50, cursor: pageParam }),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (lastPage) => lastPage.nextCursor,
    enabled: applicationIdSafe !== "" && shared,
  });
  const grants = useMemo(
    () => authorizations.data?.pages.flatMap((page) => page.items) ?? [],
    [authorizations.data],
  );
  const authorizedTenantIds = useMemo(() => new Set(grants.map((grant) => grant.tenantId)), [grants]);
  const grantableTenants = directory.tenants.filter((tenant) => !authorizedTenantIds.has(tenant.id));

  function invalidateApplication() {
    void queryClient.invalidateQueries({ queryKey: ["application", applicationIdSafe] });
    void queryClient.invalidateQueries({ queryKey: ["applications"] });
  }

  function invalidateAuthorizations() {
    void queryClient.invalidateQueries({ queryKey: ["app-authorizations", applicationIdSafe] });
    // Grant/revoke changes the tenants' effective-visibility listings.
    void queryClient.invalidateQueries({ queryKey: ["applications"] });
  }

  const update = useMutation({
    mutationFn: (input: Parameters<typeof updateApplication>[1]) =>
      updateApplication(applicationIdSafe, input),
    onSuccess: (updated) => {
      invalidateApplication();
      toast.showSuccess(`Application ${updated.name} updated`);
    },
    onError: toast.showError,
  });

  const archive = useMutation({
    mutationFn: (status: "ACTIVE" | "ARCHIVED") =>
      updateApplication(applicationIdSafe, { status }),
    onSuccess: (updated) => {
      invalidateApplication();
      toast.showSuccess(
        updated.status === "ARCHIVED"
          ? `Application ${updated.name} archived (reversible)`
          : `Application ${updated.name} restored`,
      );
    },
    onError: toast.showError,
  });

  const grant = useMutation({
    mutationFn: (input: { tenantId: string; reason?: string }) =>
      authorizeTenant(applicationIdSafe, input.tenantId, input.reason),
    onSuccess: () => {
      invalidateAuthorizations();
      toast.showSuccess("Tenant authorized");
    },
    onError: toast.showError,
  });

  const revoke = useMutation({
    mutationFn: (input: { tenantId: string; reason?: string }) =>
      deauthorizeTenant(applicationIdSafe, input.tenantId, input.reason),
    onSuccess: () => {
      invalidateAuthorizations();
      toast.showSuccess("Tenant de-authorized");
    },
    onError: toast.showError,
  });

  /** PATCH payload: metadata always (unchanged values are no-ops
   * server-side); the scope fields ride along only when the audited
   * transition is actually requested (tenantId exactly when
   * converting a shared application to tenant ownership). */
  async function submitEdit(value: ApplicationFormValue) {
    if (!app) return;
    const scopeChanged = value.ownershipScope !== app.ownershipScope;
    await update.mutateAsync({
      name: value.name,
      description: value.description,
      platforms: value.platforms,
      bundleId: value.bundleId === "" ? null : value.bundleId,
      packageName: value.packageName === "" ? null : value.packageName,
      ...(scopeChanged
        ? {
            ownershipScope: value.ownershipScope,
            ...(value.ownershipScope === "TENANT" ? { tenantId: value.ownerTenantId as string } : {}),
          }
        : {}),
    });
  }

  const authorizationColumns: GridColDef<ApplicationAuthorization>[] = [
    {
      field: "tenantId",
      headerName: "Tenant",
      flex: 1,
      minWidth: 200,
      valueGetter: (value: string) => tenantNameById.get(value) ?? value,
    },
    {
      field: "grantedBy",
      headerName: "Granted by",
      flex: 1,
      minWidth: 200,
      valueGetter: (value: string) => memberNameById.get(value) ?? value,
    },
    {
      field: "grantedAt",
      headerName: "Granted",
      type: "dateTime",
      width: 190,
      valueGetter: (value: string) => new Date(value),
    },
    {
      field: "actions",
      headerName: "Actions",
      sortable: false,
      filterable: false,
      width: 130,
      renderCell: (params) => (
        <Button
          size="small"
          color="error"
          onClick={() => setRevokeTarget(params.row)}
          data-testid={`revoke-${params.row.tenantId}`}
        >
          Revoke
        </Button>
      ),
    },
  ];

  if (application.isError) {
    return (
      <Stack spacing={2}>
        <Button component={Link} to="/applications" startIcon={<ArrowBackIcon />}>
          Applications
        </Button>
        <Alert severity="error" data-testid="application-error">
          Failed to load the application. It may not exist, or you may not have access to it.
        </Alert>
      </Stack>
    );
  }

  if (!app) {
    return (
      <Stack spacing={2}>
        <Typography aria-busy="true">Loading application...</Typography>
      </Stack>
    );
  }

  return (
    <Stack spacing={2}>
      <Stack direction="row" spacing={2} sx={{ alignItems: "center" }}>
        <Button component={Link} to="/applications" startIcon={<ArrowBackIcon />}>
          Applications
        </Button>
        <Typography variant="h5" sx={{ flexGrow: 1 }} data-testid="application-title">
          {app.name}
        </Typography>
        {app.status === "ACTIVE" ? (
          <Tooltip title="Archive (reversible soft retirement)">
            <Button
              color="warning"
              variant="outlined"
              startIcon={<ArchiveIcon />}
              onClick={() => setArchiveOpen(true)}
              data-testid="archive-application"
            >
              Archive
            </Button>
          </Tooltip>
        ) : (
          <Button
            color="success"
            variant="outlined"
            startIcon={<UnarchiveIcon />}
            onClick={() => void archive.mutateAsync("ACTIVE")}
            data-testid="restore-application"
          >
            Restore
          </Button>
        )}
        <Button
          variant="contained"
          startIcon={<EditIcon />}
          onClick={() => setEditOpen(true)}
          data-testid="edit-application"
        >
          Edit
        </Button>
      </Stack>

      <Stack direction="row" spacing={1} sx={{ alignItems: "center" }}>
        <Chip
          size="small"
          label={app.status === "ACTIVE" ? "Active" : "Archived"}
          color={app.status === "ACTIVE" ? "success" : "default"}
          variant={app.status === "ACTIVE" ? "filled" : "outlined"}
        />
        <Chip
          size="small"
          label={app.ownershipScope === "TENANT" ? `Tenant-owned — ${tenantNameById.get(app.tenantId ?? "") ?? app.tenantId ?? ""}` : "Organization-shared"}
          color={app.ownershipScope === "TENANT" ? "default" : "info"}
          variant={app.ownershipScope === "TENANT" ? "outlined" : "filled"}
        />
        {app.platforms.map((platform) => (
          <Chip key={platform} size="small" variant="outlined" label={PLATFORM_LABELS[platform]} />
        ))}
      </Stack>

      {app.description ? <Typography color="text.secondary">{app.description}</Typography> : null}

      <Paper variant="outlined" sx={{ padding: 2 }} data-testid="application-details">
        <Typography variant="h6">Details</Typography>
        <Stack spacing={1} sx={{ marginTop: 1 }}>
          <DetailRow label="Bundle ID" value={app.bundleId} />
          <DetailRow label="Package name" value={app.packageName} />
          <DetailRow
            label="Owner"
            value={
              app.ownershipScope === "TENANT"
                ? tenantNameById.get(app.tenantId ?? "") ?? app.tenantId
                : `Organization ${app.organizationId}`
            }
          />
          <DetailRow label="Created" value={new Date(app.createdAt).toLocaleString()} />
          <DetailRow label="Updated" value={new Date(app.updatedAt).toLocaleString()} />
        </Stack>
      </Paper>

      {shared ? (
        <Paper variant="outlined" sx={{ padding: 2 }} data-testid="authorization-section">
          <Stack direction="row" spacing={2} sx={{ alignItems: "center", marginBottom: 1 }}>
            <Typography variant="h6" sx={{ flexGrow: 1 }}>
              Authorized tenants
            </Typography>
            <Button
              variant="contained"
              size="small"
              onClick={() => setGrantOpen(true)}
              disabled={grantableTenants.length === 0}
              data-testid="grant-button"
            >
              Authorize tenant
            </Button>
          </Stack>
          <Typography color="text.secondary" sx={{ marginBottom: 1 }}>
            Distribution decides which tenants receive this shared application (PRD 15).
            Converting the application to tenant ownership requires revoking every active
            authorization first.
          </Typography>
          {authorizations.isError ? (
            <Alert severity="error">Failed to load authorized tenants.</Alert>
          ) : (
            <div style={{ height: 300, width: "100%" }} data-testid="authorization-grid">
              <DataGrid
                rows={grants}
                columns={authorizationColumns}
                getRowId={(row: ApplicationAuthorization) => row.tenantId}
                loading={authorizations.isPending}
                density="compact"
                disableRowSelectionOnClick
                hideFooter
              />
            </div>
          )}
          {authorizations.hasNextPage ? (
            <Button
              onClick={() => void authorizations.fetchNextPage()}
              disabled={authorizations.isFetchingNextPage}
              data-testid="authorizations-load-more"
            >
              {authorizations.isFetchingNextPage ? "Loading..." : "Load more"}
            </Button>
          ) : null}
        </Paper>
      ) : null}

      <Paper variant="outlined" sx={{ padding: 2 }} data-testid="versions-section">
        <Typography variant="h6">Versions</Typography>
        <Typography color="text.secondary" sx={{ marginTop: 1 }} data-testid="versions-empty">
          No versions yet. Version management (E4) will list this application&apos;s versions here.
        </Typography>
      </Paper>

      <Paper variant="outlined" sx={{ padding: 2 }} data-testid="artifacts-section">
        <Typography variant="h6">Artifacts</Typography>
        <Typography color="text.secondary" sx={{ marginTop: 1 }} data-testid="artifacts-empty">
          No artifacts yet. Each version&apos;s immutable binaries (E5) will appear here.
        </Typography>
      </Paper>

      <ApplicationDialog
        open={editOpen}
        title={`Edit application ${app.name}`}
        submitLabel="Save"
        mode="edit"
        application={app}
        tenants={directory.tenants}
        tenantsLoading={directory.loading}
        onClose={() => setEditOpen(false)}
        onSubmit={submitEdit}
      />

      <Dialog open={archiveOpen} onClose={() => setArchiveOpen(false)}>
        <DialogTitle>Archive application?</DialogTitle>
        <DialogContent>
          <Typography>
            Archiving <strong>{app.name}</strong> is reversible: the application stays readable
            with its audit history but accepts no new versions or artifacts. Confirm archiving of{" "}
            <strong>{app.name}</strong>.
          </Typography>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setArchiveOpen(false)}>Cancel</Button>
          <Button
            color="warning"
            variant="contained"
            data-testid="confirm-archive"
            onClick={() => {
              void archive.mutateAsync("ARCHIVED");
              setArchiveOpen(false);
            }}
          >
            Archive
          </Button>
        </DialogActions>
      </Dialog>

      <GrantDialog
        open={grantOpen}
        tenants={grantableTenants}
        loading={directory.loading}
        onClose={() => setGrantOpen(false)}
        onSubmit={async (tenantId, reason) => {
          await grant.mutateAsync({ tenantId, reason });
        }}
      />

      <ReasonDialog
        open={revokeTarget !== null}
        title="Revoke tenant authorization?"
        description={
          revokeTarget
            ? `Revoking ${tenantNameById.get(revokeTarget.tenantId) ?? revokeTarget.tenantId} removes the shared application from the tenant's effective visibility. The tenant can be re-authorized later; the revocation is recorded in the audit trail.`
            : ""
        }
        confirmLabel="Revoke"
        confirmTestId="confirm-revoke"
        reasonTestId="revoke-reason"
        onClose={() => setRevokeTarget(null)}
        onSubmit={async (reason) => {
          if (!revokeTarget) return;
          await revoke.mutateAsync({ tenantId: revokeTarget.tenantId, reason });
        }}
      />
    </Stack>
  );
}

function DetailRow({ label, value }: { label: string; value: string | null }) {
  return (
    <Stack direction="row" spacing={2}>
      <Typography sx={{ minWidth: 140, color: "text.secondary" }}>{label}</Typography>
      <Typography>{value ?? "—"}</Typography>
    </Stack>
  );
}

interface GrantDialogProps {
  open: boolean;
  tenants: Tenant[];
  loading: boolean;
  onClose: () => void;
  onSubmit: (tenantId: string, reason?: string) => Promise<void>;
}

function GrantDialog({ open, tenants, loading, onClose, onSubmit }: GrantDialogProps) {
  const toast = useToast();
  const [selected, setSelected] = useState<Tenant | null>(null);
  const [reason, setReason] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const reasonError = reason.trim() !== "" ? validateReason(reason) : undefined;

  async function handleSubmit() {
    if (!selected) return;
    setSubmitting(true);
    try {
      await onSubmit(selected.id, reason.trim() === "" ? undefined : reason.trim());
      onClose();
      setSelected(null);
      setReason("");
    } catch (error) {
      toast.showError(error);
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>Authorize tenant</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ marginTop: 1 }}>
          <Autocomplete
            options={tenants}
            loading={loading}
            getOptionLabel={(option) => option.name}
            isOptionEqualToValue={(option, value) => option.id === value.id}
            value={selected}
            onChange={(_event, value: Tenant | null) => setSelected(value)}
            renderInput={(params) => (
              <TextField {...params} label="Tenant" required helperText="Already-authorized tenants are not offered" />
            )}
            data-testid="grant-tenant-picker"
          />
          <TextField
            label="Reason (optional)"
            value={reason}
            onChange={(event) => setReason(event.target.value)}
            error={reasonError !== undefined}
            helperText={reasonError ?? "Captured on the audit event (max 500 characters)"}
            multiline
            minRows={2}
            slotProps={{ htmlInput: { "data-testid": "grant-reason" } }}
          />
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button
          variant="contained"
          onClick={() => void handleSubmit()}
          disabled={submitting || selected === null || reasonError !== undefined}
          data-testid="grant-submit"
        >
          {submitting ? "Authorizing..." : "Authorize"}
        </Button>
      </DialogActions>
    </Dialog>
  );
}

interface ReasonDialogProps {
  open: boolean;
  title: string;
  description: string;
  confirmLabel: string;
  confirmTestId: string;
  reasonTestId: string;
  onClose: () => void;
  onSubmit: (reason?: string) => Promise<void>;
}

/** Confirmation with an optional audit reason (used by revoke). */
function ReasonDialog({
  open,
  title,
  description,
  confirmLabel,
  confirmTestId,
  reasonTestId,
  onClose,
  onSubmit,
}: ReasonDialogProps) {
  const toast = useToast();
  const [reason, setReason] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const reasonError = reason.trim() !== "" ? validateReason(reason) : undefined;

  async function handleSubmit() {
    setSubmitting(true);
    try {
      await onSubmit(reason.trim() === "" ? undefined : reason.trim());
      onClose();
      setReason("");
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
          <Typography>{description}</Typography>
          <TextField
            label="Reason (optional)"
            value={reason}
            onChange={(event) => setReason(event.target.value)}
            error={reasonError !== undefined}
            helperText={reasonError ?? "Captured on the audit event (max 500 characters)"}
            multiline
            minRows={2}
            slotProps={{ htmlInput: { "data-testid": reasonTestId } }}
          />
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button
          color="error"
          variant="contained"
          onClick={() => void handleSubmit()}
          disabled={submitting || reasonError !== undefined}
          data-testid={confirmTestId}
        >
          {submitting ? "Working..." : confirmLabel}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
