/**
 * Tenant member management (issue #12): roster with role and membership
 * status (the S2-03 MemberView.status follow-up), add via the
 * organization's member roster (or a raw user id for tenant testers who
 * are not org members), role changes, the reversible suspend switch,
 * and removal — each with confirmation where destructive.
 */
import ArrowBackIcon from "@mui/icons-material/ArrowBack";
import PersonAddIcon from "@mui/icons-material/PersonAdd";
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
import { listOrganizationMembers, type OrgMember } from "../api/organizations";
import {
  addTenantMember,
  changeTenantMemberRole,
  changeTenantMemberStatus,
  getTenant,
  listTenantMembers,
  removeTenantMember,
  TENANT_ROLES,
  type MemberStatus,
  type TenantMember,
  type TenantRole,
} from "../api/tenants";
import { useAuth } from "../auth/AuthContext";
import { useToast } from "../components/ToastContext";

export default function TenantMembersPage() {
  const { tenantId } = useParams<{ tenantId: string }>();
  const tenantIdSafe = tenantId ?? "";
  const { user } = useAuth();
  const orgId = user?.orgId ?? null;
  const toast = useToast();
  const queryClient = useQueryClient();
  const [addOpen, setAddOpen] = useState(false);
  const [removeTarget, setRemoveTarget] = useState<TenantMember | null>(null);

  const tenant = useQuery({
    queryKey: ["tenant", tenantIdSafe],
    queryFn: () => getTenant(tenantIdSafe),
    enabled: tenantIdSafe !== "",
  });

  const members = useInfiniteQuery({
    queryKey: ["tenant-members", tenantIdSafe],
    queryFn: ({ pageParam }) => listTenantMembers(tenantIdSafe, { limit: 50, cursor: pageParam }),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (lastPage) => lastPage.nextCursor,
    enabled: tenantIdSafe !== "",
  });
  const roster = members.data?.pages.flatMap((page) => page.items) ?? [];

  function invalidate() {
    void queryClient.invalidateQueries({ queryKey: ["tenant-members", tenantIdSafe] });
  }

  const roleChange = useMutation({
    mutationFn: (input: { userId: string; role: TenantRole }) =>
      changeTenantMemberRole(tenantIdSafe, input.userId, input.role),
    onSuccess: (member) => {
      invalidate();
      toast.showSuccess(`${member.email} is now ${member.role}`);
    },
    onError: toast.showError,
  });

  const statusChange = useMutation({
    mutationFn: (input: { userId: string; status: MemberStatus }) =>
      changeTenantMemberStatus(tenantIdSafe, input.userId, input.status),
    onSuccess: (member) => {
      invalidate();
      toast.showSuccess(
        member.status === "SUSPENDED"
          ? `${member.email} suspended (sessions revoked)`
          : `${member.email} reactivated`,
      );
    },
    onError: toast.showError,
  });

  const remove = useMutation({
    mutationFn: (userId: string) => removeTenantMember(tenantIdSafe, userId),
    onSuccess: () => {
      invalidate();
      toast.showSuccess("Member removed");
    },
    onError: toast.showError,
  });

  const columns: GridColDef<TenantMember>[] = [
    { field: "email", headerName: "Email", flex: 1, minWidth: 220 },
    { field: "fullName", headerName: "Name", flex: 1, minWidth: 160 },
    {
      field: "role",
      headerName: "Role",
      width: 200,
      renderCell: (params) => (
        <TextField
          select
          size="small"
          value={params.value}
          label="Role"
          sx={{ minWidth: 170 }}
          onChange={(event) =>
            void roleChange.mutateAsync({
              userId: params.row.userId,
              role: event.target.value as TenantRole,
            })
          }
          data-testid={`role-${params.row.userId}`}
        >
          {TENANT_ROLES.map((role) => (
            <MenuItem key={role} value={role}>
              {role === "TENANT_ADMIN" ? "Tenant Admin" : "Tenant Tester"}
            </MenuItem>
          ))}
        </TextField>
      ),
    },
    {
      field: "status",
      headerName: "Status",
      width: 130,
      renderCell: (params) => (
        <Chip
          size="small"
          label={params.value === "ACTIVE" ? "Active" : "Suspended"}
          color={params.value === "ACTIVE" ? "success" : "warning"}
          variant={params.value === "ACTIVE" ? "filled" : "outlined"}
          data-testid={`status-${params.row.userId}`}
        />
      ),
    },
    {
      field: "createdAt",
      headerName: "Member since",
      type: "dateTime",
      width: 190,
      valueGetter: (value: string) => new Date(value),
    },
    {
      field: "actions",
      headerName: "Actions",
      sortable: false,
      filterable: false,
      width: 220,
      renderCell: (params) => (
        <Stack direction="row" spacing={1}>
          {params.row.status === "ACTIVE" ? (
            <Tooltip title="Suspend membership (revokes sessions)">
              <Button
                size="small"
                color="warning"
                onClick={() =>
                  void statusChange.mutateAsync({ userId: params.row.userId, status: "SUSPENDED" })
                }
                data-testid={`suspend-${params.row.userId}`}
              >
                Suspend
              </Button>
            </Tooltip>
          ) : (
            <Button
              size="small"
              color="success"
              onClick={() =>
                void statusChange.mutateAsync({ userId: params.row.userId, status: "ACTIVE" })
              }
              data-testid={`reactivate-${params.row.userId}`}
            >
              Reactivate
            </Button>
          )}
          <Button
            size="small"
            color="error"
            onClick={() => setRemoveTarget(params.row)}
            data-testid={`remove-${params.row.userId}`}
          >
            Remove
          </Button>
        </Stack>
      ),
    },
  ];

  return (
    <Stack spacing={2}>
      <Stack direction="row" spacing={2} sx={{ alignItems: "center" }}>
        <Button component={Link} to="/tenants" startIcon={<ArrowBackIcon />}>
          Tenants
        </Button>
        <Typography variant="h5" sx={{ flexGrow: 1 }} data-testid="members-title">
          Members {tenant.data ? `— ${tenant.data.name}` : ""}
        </Typography>
        <Button
          variant="contained"
          startIcon={<PersonAddIcon />}
          onClick={() => setAddOpen(true)}
          data-testid="add-member-button"
        >
          Add member
        </Button>
      </Stack>

      {tenant.data?.status === "DEACTIVATED" ? (
        <Alert severity="info">
          This tenant is deactivated: existing memberships stay administrable, new
          additions are refused by the API.
        </Alert>
      ) : null}

      {members.isError ? (
        <Alert severity="error" data-testid="members-error">
          Failed to load members.
        </Alert>
      ) : null}

      <div style={{ height: 560, width: "100%" }} data-testid="members-grid">
        <DataGrid
          rows={roster}
          columns={columns}
          getRowId={(row: TenantMember) => row.userId}
          loading={members.isPending}
          density="compact"
          disableRowSelectionOnClick
          hideFooter
        />
      </div>

      {members.hasNextPage ? (
        <Button
          onClick={() => void members.fetchNextPage()}
          disabled={members.isFetchingNextPage}
          data-testid="members-load-more"
        >
          {members.isFetchingNextPage ? "Loading..." : "Load more"}
        </Button>
      ) : null}

      <AddMemberDialog
        open={addOpen}
        orgId={orgId}
        onClose={() => setAddOpen(false)}
        onSubmit={async (userId, role) => {
          await addTenantMember(tenantIdSafe, { userId, role });
          invalidate();
          toast.showSuccess("Member added");
        }}
      />

      <Dialog open={removeTarget !== null} onClose={() => setRemoveTarget(null)}>
        <DialogTitle>Remove member?</DialogTitle>
        <DialogContent>
          <Typography>
            Removing <strong>{removeTarget?.email}</strong> deletes the membership and
            revokes their sessions. They can be re-added later.
          </Typography>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setRemoveTarget(null)}>Cancel</Button>
          <Button
            color="error"
            variant="contained"
            data-testid="confirm-remove"
            onClick={() => {
              if (removeTarget) void remove.mutateAsync(removeTarget.userId);
              setRemoveTarget(null);
            }}
          >
            Remove
          </Button>
        </DialogActions>
      </Dialog>
    </Stack>
  );
}

interface AddMemberDialogProps {
  open: boolean;
  orgId: string | null;
  onClose: () => void;
  onSubmit: (userId: string, role: TenantRole) => Promise<void>;
}

/** Role picker is fixed to the M1 tenant roles (TENANT_ADMIN, TENANT_TESTER);
 * the person is chosen from the org roster or by raw user id — tenant
 * testers need not belong to the owning organization (docs/tenants.md). */
function AddMemberDialog({ open, orgId, onClose, onSubmit }: AddMemberDialogProps) {
  const toast = useToast();
  const [orgMembers, setOrgMembers] = useState<OrgMember[] | null>(null);
  const [selected, setSelected] = useState<OrgMember | null>(null);
  const [manualUserId, setManualUserId] = useState("");
  const [role, setRole] = useState<TenantRole>("TENANT_ADMIN");
  const [submitting, setSubmitting] = useState(false);

  const orgRoster = useQuery({
    queryKey: ["org-members", orgId],
    queryFn: async () => {
      if (!orgId) throw new Error("no organization");
      const first = await listOrganizationMembers(orgId, { limit: 50 });
      let items = first.items;
      if (first.nextCursor) {
        const second = await listOrganizationMembers(orgId, { limit: 50, cursor: first.nextCursor });
        items = [...items, ...second.items];
      }
      return items;
    },
    enabled: open && orgMembers === null && orgId !== null,
  });

  if (orgRoster.data && orgMembers === null) {
    setOrgMembers(orgRoster.data);
  }

  const userId = selected?.userId ?? (manualUserId.trim() !== "" ? manualUserId.trim() : undefined);
  const manualIdInvalid = selected === null && manualUserId.trim() !== "" && !isUuid(manualUserId.trim());

  async function handleSubmit() {
    if (!userId) return;
    setSubmitting(true);
    try {
      await onSubmit(userId, role);
      onClose();
      setSelected(null);
      setManualUserId("");
      setRole("TENANT_ADMIN");
    } catch (error) {
      toast.showError(error);
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>Add member</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ marginTop: 1 }}>
          <Autocomplete
            options={orgMembers ?? []}
            loading={orgRoster.isPending}
            getOptionLabel={(option: OrgMember) => `${option.fullName} (${option.email})`}
            isOptionEqualToValue={(option: OrgMember, value: OrgMember) =>
              option.userId === value.userId
            }
            value={selected}
            onChange={(_event, value: OrgMember | null) => setSelected(value)}
            renderInput={(params) => (
              <TextField
                {...params}
                label="Organization member"
                helperText="Tenant testers may be outside the organization — enter their user id below instead."
              />
            )}
            data-testid="member-picker"
          />
          <TextField
            label="User ID (when not an organization member)"
            value={manualUserId}
            onChange={(event) => {
              setSelected(null);
              setManualUserId(event.target.value);
            }}
            error={manualIdInvalid}
            helperText={manualIdInvalid ? "Must be a UUID" : "Overrides the picker above"}
            disabled={selected !== null}
            slotProps={{ htmlInput: { "data-testid": "member-user-id" } }}
          />
          <TextField
            select
            label="Role"
            value={role}
            onChange={(event) => setRole(event.target.value as TenantRole)}
            required
            data-testid="member-role"
          >
            {TENANT_ROLES.map((option) => (
              <MenuItem key={option} value={option}>
                {option === "TENANT_ADMIN" ? "Tenant Admin" : "Tenant Tester"}
              </MenuItem>
            ))}
          </TextField>
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button
          variant="contained"
          onClick={() => void handleSubmit()}
          disabled={submitting || userId === undefined || manualIdInvalid}
          data-testid="member-submit"
        >
          {submitting ? "Adding..." : "Add member"}
        </Button>
      </DialogActions>
    </Dialog>
  );
}

function isUuid(value: string): boolean {
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value);
}
