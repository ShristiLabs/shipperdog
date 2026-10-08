/**
 * Release rings editor (S10-04, AC1) — the console surface of the
 * progressive rollout ladder: the organization's effective ring
 * vocabulary in progression order (Production last, enforced by the
 * server), each ring with its PRD 8.10 facts (eligibility rules,
 * approval-policy reference, promotion policy, distribution-channel
 * reference, and its gate set). Organization rings edit through the
 * dialog (rename, reorder, re-rule); system defaults are read-only —
 * the organization customizes a system code by creating its own ring
 * under it (the shadow the create dialog builds when the code matches
 * a system vocabulary entry). Client-side validation refuses the
 * obvious shapes (blank name, negative position); the server
 * re-validates everything (Production last, the production
 * governance pair, the eligibility ids) and refusals surface the
 * problem+json code — the console is never the gate.
 */
import AddIcon from "@mui/icons-material/Add";
import {
  Alert,
  Autocomplete,
  Box,
  Button,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  LinearProgress,
  MenuItem,
  Paper,
  Stack,
  TextField,
  Typography,
} from "@mui/material";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import {
  createRing,
  listRings,
  listTenantTesterGroups,
  updateRing,
  type Ring,
} from "../api/rings";
import { useAuth } from "../auth/AuthContext";
import { useToast } from "../components/ToastContext";
import { useAllTenants } from "../hooks/useOrganizationDirectory";
import type { Tenant } from "../api/tenants";

/** The editor's draft: everything the dialog can write, with null
 * meaning "keep the standing" on edit and "the default" on create. */
interface Draft {
  code: string;
  name: string;
  position: string;
  promotionPolicy: "MANUAL" | "AUTOMATIC";
  approvalPolicy: string;
  distributionChannel: string;
  tenants: Tenant[];
  groups: { id: string; name: string }[];
}

function draftOf(ring: Ring | null): Draft {
  return {
    code: ring?.code ?? "",
    name: ring?.name ?? "",
    position: ring === null ? "" : String(ring.position),
    promotionPolicy: ring?.promotionPolicy ?? "MANUAL",
    approvalPolicy: ring?.approvalPolicy ?? "",
    distributionChannel: ring?.distributionChannel ?? "",
    tenants: [],
    groups: [],
  };
}

export default function RingsPage() {
  const { user } = useAuth();
  const orgId = user?.orgId ?? null;
  const toast = useToast();
  const queryClient = useQueryClient();
  const [editorOpen, setEditorOpen] = useState(false);
  const [editing, setEditing] = useState<Ring | null>(null);
  const [draft, setDraft] = useState<Draft>(draftOf(null));

  const rings = useQuery({
    queryKey: ["rings", orgId],
    queryFn: () => listRings(orgId as string),
    enabled: orgId !== null,
  });
  const { tenants } = useAllTenants(orgId);

  async function refresh() {
    await queryClient.invalidateQueries({ queryKey: ["rings", orgId] });
  }

  /** The groups picker feeds from every tenant's groups once the
   * dialog opens (app:tester-groups:manage holders see the named
   * options; everyone else degrades to free id entry). */
  const groupsLoader = useQuery({
    queryKey: ["rings-editor-groups", orgId],
    queryFn: async () => {
      const rosters = await Promise.all(
        (tenants ?? []).map((tenant) =>
          listTenantTesterGroups(tenant.id).catch(() => [] as { id: string; name: string }[]),
        ),
      );
      return rosters.flat();
    },
    enabled: editorOpen && orgId !== null && (tenants?.length ?? 0) > 0,
  });

  const save = useMutation({
    mutationFn: async () => {
      const eligibility = {
        ...(draft.tenants.length > 0 ? { tenants: draft.tenants.map((tenant) => tenant.id) } : {}),
        ...(draft.groups.length > 0 ? { groups: draft.groups.map((group) => group.id) } : {}),
      };
      const hasEligibility = Object.keys(eligibility).length > 0;
      if (editing !== null) {
        // An EMPTY eligibility object CLEARS the rules (the documented
        // reset); absent keeps them — so the body always carries the
        // composed object.
        return updateRing(orgId as string, editing.id, {
          name: draft.name.trim(),
          position: Number(draft.position),
          eligibility,
          approvalPolicy: draft.approvalPolicy.trim() === "" ? null : draft.approvalPolicy.trim(),
          promotionPolicy: draft.promotionPolicy,
          distributionChannel:
            draft.distributionChannel.trim() === "" ? null : draft.distributionChannel.trim(),
        });
      }
      return createRing({
        organizationId: orgId as string,
        code: draft.code.trim(),
        name: draft.name.trim(),
        position: Number(draft.position),
        eligibility: hasEligibility ? eligibility : null,
        approvalPolicy: draft.approvalPolicy.trim() === "" ? null : draft.approvalPolicy.trim(),
        promotionPolicy: draft.promotionPolicy,
        distributionChannel:
          draft.distributionChannel.trim() === "" ? null : draft.distributionChannel.trim(),
      });
    },
    onSuccess: async () => {
      toast.showSuccess("Ring saved");
      setEditorOpen(false);
      await refresh();
    },
    onError: (error) => {
      toast.showError(error);
    },
  });

  function openCreate() {
    setEditing(null);
    setDraft(draftOf(null));
    setEditorOpen(true);
  }

  function openEdit(ring: Ring) {
    setEditing(ring);
    setDraft(draftOf(ring));
    // Seed the pickers with the stored rules' ids (named options load
    // separately; the ids render until they resolve).
    setDraft((current) => ({
      ...current,
      tenants: (ring.eligibility?.tenants ?? []).map(
        (id) => tenants?.find((tenant) => tenant.id === id) ?? ({ id, name: id } as Tenant),
      ),
      groups: (ring.eligibility?.groups ?? []).map((id) => ({ id, name: id })),
    }));
    setEditorOpen(true);
  }

  const options: { id: string; name: string }[] = groupsLoader.data ?? [];

  return (
    <Stack spacing={2}>
      <Stack direction="row" spacing={2} sx={{ alignItems: "center" }}>
        <Typography variant="h5" sx={{ flexGrow: 1 }} data-testid="rings-title">
          Release rings
        </Typography>
        <Button variant="contained" startIcon={<AddIcon />} onClick={openCreate} data-testid="ring-create">
          New ring
        </Button>
      </Stack>
      <Typography color="text.secondary">
        The progressive rollout ladder, first ring to Production (always last — the server refuses
        any other order). Each ring carries its eligibility rules (which tenants and tester groups
        it admits), its approval-policy reference (a workflow name the ring leg resolves before the
        environment&apos;s), its promotion policy, and its quality-gate set. System defaults are
        read-only; creating a ring under a system code customizes that code for your organization.
      </Typography>
      {rings.isError ? (
        <Alert severity="error" data-testid="rings-error">
          Failed to load the ring vocabulary.
        </Alert>
      ) : rings.isPending ? (
        <LinearProgress aria-label="loading rings" />
      ) : (
        <Stack spacing={1} data-testid="ring-list">
          {rings.data?.map((ring) => (
            <Paper key={ring.id} variant="outlined" sx={{ padding: 2 }} data-testid={`ring-${ring.code}`}>
              <Stack direction="row" spacing={2} sx={{ alignItems: "center", flexWrap: "wrap" as const }}>
                <Typography variant="subtitle1" sx={{ fontFamily: "monospace", minWidth: 110 }}>
                  {ring.position}. {ring.code}
                </Typography>
                <Typography variant="body1">{ring.name}</Typography>
                <Chip
                  size="small"
                  variant="outlined"
                  label={ring.organizationScoped ? "organization" : "system default"}
                />
                <Chip
                  size="small"
                  variant="outlined"
                  label={
                    ring.eligibility?.tenants || ring.eligibility?.groups
                      ? `eligibility: ${ring.eligibility?.tenants?.length ?? 0} tenants, ${
                          ring.eligibility?.groups?.length ?? 0
                        } groups`
                      : "eligibility: everyone"
                  }
                />
                <Chip
                  size="small"
                  variant="outlined"
                  label={ring.approvalPolicy === null ? "no approval policy" : `policy ${ring.approvalPolicy}`}
                />
                <Chip size="small" variant="outlined" label={`promotion ${ring.promotionPolicy.toLowerCase()}`} />
                <Chip
                  size="small"
                  variant="outlined"
                  label={
                    ring.gateSet.length === 0
                      ? "no gates"
                      : `${ring.gateSet.filter((gate) => gate.required).length} required gates`
                  }
                />
                <Box sx={{ flexGrow: 1 }} />
                {ring.organizationScoped ? (
                  <Button size="small" onClick={() => openEdit(ring)} data-testid={`ring-edit-${ring.code}`}>
                    Edit
                  </Button>
                ) : null}
              </Stack>
            </Paper>
          ))}
        </Stack>
      )}

      <Dialog open={editorOpen} onClose={() => setEditorOpen(false)} fullWidth maxWidth="sm">
        <DialogTitle>{editing === null ? "New ring" : `Edit ring '${editing.code}'`}</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ marginTop: 1 }}>
            {editing === null ? (
              <TextField
                label="Code (lowercase kebab; a system code customizes it for your organization)"
                value={draft.code}
                onChange={(event) => setDraft({ ...draft, code: event.target.value })}
                data-testid="ring-code"
              />
            ) : null}
            <TextField
              label="Name"
              value={draft.name}
              onChange={(event) => setDraft({ ...draft, name: event.target.value })}
              data-testid="ring-name"
            />
            <TextField
              label="Position (Production stays last)"
              type="number"
              value={draft.position}
              onChange={(event) => setDraft({ ...draft, position: event.target.value })}
              data-testid="ring-position"
            />
            <TextField
              select
              label="Promotion policy"
              value={draft.promotionPolicy}
              onChange={(event) =>
                setDraft({ ...draft, promotionPolicy: event.target.value as Draft["promotionPolicy"] })
              }
              data-testid="ring-promotion-policy"
            >
              <MenuItem value="MANUAL">Manual</MenuItem>
              <MenuItem value="AUTOMATIC">Automatic (acts with S10-06)</MenuItem>
            </TextField>
            <TextField
              label="Approval policy (workflow name; required for Production)"
              value={draft.approvalPolicy}
              onChange={(event) => setDraft({ ...draft, approvalPolicy: event.target.value })}
              data-testid="ring-approval-policy"
            />
            <TextField
              label="Distribution channel (reference name; destinations land in E13)"
              value={draft.distributionChannel}
              onChange={(event) => setDraft({ ...draft, distributionChannel: event.target.value })}
              data-testid="ring-distribution-channel"
            />
            <Autocomplete
              multiple
              options={tenants ?? []}
              getOptionLabel={(option) => option.name}
              value={draft.tenants}
              onChange={(_event, value) => setDraft({ ...draft, tenants: value })}
              renderInput={(parameters) => (
                <TextField {...parameters} label="Eligibility tenants (empty = every target tenant)" />
              )}
              data-testid="ring-eligibility-tenants"
            />
            <Autocomplete
              multiple
              freeSolo
              options={options}
              getOptionLabel={(option) => (typeof option === "string" ? option : `${option.name} (${option.id})`)}
              isOptionEqualToValue={(option, value) =>
                typeof option === "string" || typeof value === "string"
                  ? String(option) === String(value)
                  : option.id === value.id
              }
              value={draft.groups as unknown as (string | { id: string; name: string })[]}
              onChange={(_event, value) =>
                setDraft({
                  ...draft,
                  groups: value.map((group) =>
                    typeof group === "string" ? { id: group, name: group } : group,
                  ),
                })
              }
              renderInput={(parameters) => (
                <TextField
                  {...parameters}
                  label="Eligibility tester groups (empty = no group rule; ids paste directly)"
                />
              )}
              data-testid="ring-eligibility-groups"
            />
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setEditorOpen(false)}>Cancel</Button>
          <Button
            variant="contained"
            disabled={save.isPending || draft.name.trim() === "" || draft.position.trim() === ""}
            onClick={() => save.mutate()}
            data-testid="ring-save"
          >
            Save
          </Button>
        </DialogActions>
      </Dialog>
    </Stack>
  );
}
