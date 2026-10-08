/**
 * Approval workflow editor (issue #41, S10-02, AC4) — the console
 * surface that composes governance topologies as data: the
 * organization's definitions listed with their chain summaries, and
 * the create/edit dialog that builds the ordered chain as GROUPS (the
 * issue's simplified tree editor) — each group a mode toggle (ALL =
 * the PARALLEL mode, every step must approve; ANY = the OR mode, the
 * first approval satisfies and the remaining siblings skip) holding
 * its ordered steps, each step a required role from the five-role
 * vocabulary, optionally narrowed to a tenant the release must target
 * and optionally carrying a trigger condition (the stub vocabulary:
 * environment equals, feature-flag contains) evaluated at round
 * instantiation. Client-side validation refuses before any write the
 * shapes that would make a step unreachable — an empty definition, an
 * empty group, a role demanded twice, the ten-step ceiling, a
 * malformed condition (approvalRules.ts) — and the server re-validates
 * everything (the console is never the gate; refusals surface the
 * problem+json code, the ApplicationDetailPage precedent). The name is
 * the binding vocabulary an environment's approval_policy references:
 * set at creation, never edited. Edits replace the chain and re-attach
 * the ring (an emptied ring DETACHES); rounds already run keep their
 * frozen per-step copies — later rounds, rework restarts included,
 * instantiate the definition's current content.
 */
import AddIcon from "@mui/icons-material/Add";
import DeleteIcon from "@mui/icons-material/Delete";
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
  IconButton,
  LinearProgress,
  MenuItem,
  Paper,
  Stack,
  TextField,
  Tooltip,
  Typography,
} from "@mui/material";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import {
  createApprovalWorkflow,
  listApprovalWorkflows,
  updateApprovalWorkflow,
  type ApprovalRole,
  type ApprovalWorkflow,
  type GroupMode,
} from "../api/workflows";
import { useAuth } from "../auth/AuthContext";
import { useToast } from "../components/ToastContext";
import { useAllTenants } from "../hooks/useOrganizationDirectory";
import {
  APPROVAL_ROLES,
  CONDITION_TYPES,
  chainSummary,
  conditionLabel,
  draftToGroups,
  groupsOf,
  roleLabel,
  validateDefinition,
} from "./approvalRules";
import type { DraftGroup, DraftStep } from "./approvalRules";

const EMPTY_STEP: DraftStep = { role: "QA_ENGINEER", tenantId: null, condition: null };

export default function ApprovalWorkflowsPage() {
  const { user } = useAuth();
  const orgId = user?.orgId ?? null;
  const [editorOpen, setEditorOpen] = useState(false);
  const [editing, setEditing] = useState<ApprovalWorkflow | null>(null);

  const workflows = useQuery({
    queryKey: ["approval-workflows", orgId],
    queryFn: () => listApprovalWorkflows({ organizationId: orgId as string, limit: 50 }),
    enabled: orgId !== null,
  });

  function openCreate() {
    setEditing(null);
    setEditorOpen(true);
  }

  function openEdit(workflow: ApprovalWorkflow) {
    setEditing(workflow);
    setEditorOpen(true);
  }

  return (
    <Stack spacing={2}>
      <Stack direction="row" spacing={2} sx={{ alignItems: "center" }}>
        <Typography variant="h5" sx={{ flexGrow: 1 }} data-testid="workflows-title">
          Approval workflows
        </Typography>
        <Button variant="contained" startIcon={<AddIcon />} onClick={openCreate} data-testid="workflow-create">
          New definition
        </Button>
      </Stack>
      <Typography color="text.secondary">
        Chains compose as ordered groups — sequential is the one-step-group degenerate case. Within a
        group: ALL (the PARALLEL mode) demands every step&apos;s signature; ANY (the OR mode) is
        satisfied by the first approval and skips the remaining siblings, recording which signature
        satisfied the group. Conditions are evaluated at round start; an excluded step instantiates
        SKIPPED with its rationale. An environment binds a definition by name through its approval
        policy; the ring attachment binds releases starting at that ring.
      </Typography>
      {workflows.isError ? (
        <Alert severity="error" data-testid="workflows-error">
          Failed to load the approval workflow definitions.
        </Alert>
      ) : workflows.isPending ? (
        <Typography aria-busy="true">Loading definitions...</Typography>
      ) : (workflows.data?.items.length ?? 0) === 0 ? (
        <Alert severity="info" data-testid="workflows-empty">
          No approval workflow definitions yet — create one to require sign-offs before a release
          may cross into APPROVED.
        </Alert>
      ) : (
        <Stack spacing={1} data-testid="workflow-list">
          {workflows.data?.items.map((workflow) => (
            <Paper key={workflow.id} variant="outlined" sx={{ padding: 2 }} data-testid={`workflow-${workflow.name}`}>
              <Stack direction="row" spacing={2} sx={{ alignItems: "center", flexWrap: "wrap" as const }}>
                <Typography variant="subtitle1" sx={{ fontFamily: "monospace" }}>
                  {workflow.name}
                </Typography>
                <Chip
                  size="small"
                  variant="outlined"
                  label={workflow.ring === null ? "no ring attachment" : `ring ${workflow.ring}`}
                />
                <Typography variant="body2" sx={{ flexGrow: 1 }} color="text.secondary">
                  {chainSummary(workflow.steps)}
                </Typography>
                <Button size="small" onClick={() => openEdit(workflow)} data-testid={`workflow-edit-${workflow.name}`}>
                  Edit
                </Button>
              </Stack>
              <Stack spacing={0.5} sx={{ marginTop: 1 }}>
                {workflow.steps
                  .filter((step) => step.condition !== null)
                  .map((step) => (
                    <Typography key={step.position} variant="body2" color="text.secondary">
                      {roleLabel(step.requiredRole)} {conditionLabel(step.condition!)}
                    </Typography>
                  ))}
              </Stack>
            </Paper>
          ))}
        </Stack>
      )}
      {editorOpen ? (
        <WorkflowEditorDialog
          orgId={orgId as string}
          workflow={editing}
          onClose={() => setEditorOpen(false)}
        />
      ) : null}
    </Stack>
  );
}

interface EditorDialogProps {
  orgId: string;
  workflow: ApprovalWorkflow | null;
  onClose: () => void;
}

/** The create/edit dialog: the binding name (create only — it is the
 * vocabulary environments reference), the optional ring attachment
 * code (emptied detaches), and the chain as groups. Submit is refused
 * client-side while any validation error stands (AC4). */
function WorkflowEditorDialog({ orgId, workflow, onClose }: EditorDialogProps) {
  const toast = useToast();
  const queryClient = useQueryClient();
  const [name, setName] = useState("");
  const [ring, setRing] = useState(workflow?.ring ?? "");
  const [groups, setGroups] = useState<DraftGroup[]>(() =>
    workflow === null
      ? [{ mode: "ALL", steps: [{ ...EMPTY_STEP }] }]
      : groupsOf(workflow.steps).map((group) => ({
          mode: group.mode,
          steps: group.steps.map((step) => ({
            role: step.requiredRole,
            tenantId: step.tenantId,
            condition:
              step.condition === null
                ? null
                : {
                    type: step.condition.type,
                    key: step.condition.key ?? "",
                    value: step.condition.value ?? "",
                  },
          })),
        })),
  );

  const tenants = useAllTenants(orgId);

  const errors = validateDefinition(groups, workflow === null ? name : null);
  const creating = workflow === null;

  const save = useMutation({
    mutationFn: async () => {
      const body = { ring: ring.trim() === "" ? null : ring.trim().toLowerCase(), groups: draftToGroups(groups) };
      if (creating) {
        return createApprovalWorkflow({ organizationId: orgId, name: name.trim(), ...body });
      }
      return updateApprovalWorkflow(orgId, workflow.id, body);
    },
    onSuccess: (saved) => {
      void queryClient.invalidateQueries({ queryKey: ["approval-workflows", orgId] });
      toast.showSuccess(`Approval workflow ${saved.name} ${creating ? "created" : "updated"}`);
      onClose();
    },
    onError: toast.showError,
  });

  function patchGroup(index: number, patch: Partial<DraftGroup>) {
    setGroups((current) => current.map((group, at) => (at === index ? { ...group, ...patch } : group)));
  }

  function patchStep(groupIndex: number, stepIndex: number, patch: Partial<DraftStep>) {
    setGroups((current) =>
      current.map((group, at) =>
        at === groupIndex
          ? {
              ...group,
              steps: group.steps.map((step, position) =>
                position === stepIndex ? { ...step, ...patch } : step,
              ),
            }
          : group,
      ),
    );
  }

  const errorValues = [...errors.values()];

  return (
    <Dialog open fullWidth maxWidth="md" data-testid="workflow-editor">
      <DialogTitle>
        {creating ? "New approval workflow" : `Edit ${workflow?.name}`}
      </DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ marginTop: 1 }}>
          <Stack direction="row" spacing={2}>
            <TextField
              label="Binding name (kebab-case)"
              value={name}
              onChange={(event) => setName(event.target.value)}
              disabled={!creating}
              error={creating && errors.has("name")}
              helperText={
                creating
                  ? (errors.get("name") ??
                    "The vocabulary an environment's approval policy references; not editable later.")
                  : "The name never moves — environments reference it."
              }
              sx={{ flexGrow: 1 }}
              slotProps={{ htmlInput: { "data-testid": "workflow-name" } }}
            />
            <TextField
              label="Starting ring (optional)"
              value={ring}
              onChange={(event) => setRing(event.target.value)}
              helperText="Releases starting at this ring resolve the chain; empty detaches."
              sx={{ flexGrow: 1 }}
              slotProps={{ htmlInput: { "data-testid": "workflow-ring" } }}
            />
          </Stack>
          {groups.map((group, groupIndex) => (
            <Paper key={groupIndex} variant="outlined" sx={{ padding: 2 }} data-testid={`workflow-group-${groupIndex}`}>
              <Stack direction="row" spacing={2} sx={{ alignItems: "center" }}>
                <Typography variant="subtitle2">Group {groupIndex + 1}</Typography>
                <TextField
                  select
                  label="Mode"
                  value={group.mode}
                  onChange={(event) => patchGroup(groupIndex, { mode: event.target.value as GroupMode })}
                  size="small"
                  sx={{ minWidth: 260 }}
                  data-testid={`workflow-mode-${groupIndex}`}
                  helperText={
                    group.mode === "ALL"
                      ? "ALL (parallel): every step must approve"
                      : "ANY (or): the first approval satisfies; siblings skip"
                  }
                >
                  <MenuItem value="ALL">ALL — every step must approve</MenuItem>
                  <MenuItem value="ANY">ANY — first approval satisfies</MenuItem>
                </TextField>
                <Box sx={{ flexGrow: 1 }} />
                <Tooltip title="Remove this group">
                  <IconButton
                    size="small"
                    onClick={() => setGroups((current) => current.filter((_, at) => at !== groupIndex))}
                    aria-label={`Remove group ${groupIndex + 1}`}
                    data-testid={`workflow-remove-group-${groupIndex}`}
                  >
                    <DeleteIcon fontSize="small" />
                  </IconButton>
                </Tooltip>
              </Stack>
              <Stack spacing={1} sx={{ marginTop: 1 }}>
                {group.steps.map((step, stepIndex) => (
                  <StepEditor
                    key={stepIndex}
                    step={step}
                    tenants={tenants.tenants.map((tenant) => ({ id: tenant.id, name: tenant.name }))}
                    error={errors.get(`condition-${groups.slice(0, groupIndex).reduce((count, earlier) => count + earlier.steps.length, 0) + stepIndex}`)}
                    onChange={(patch) => patchStep(groupIndex, stepIndex, patch)}
                    onRemove={() =>
                      setGroups((current) =>
                        current.map((groupAt, at) =>
                          at === groupIndex
                            ? { ...groupAt, steps: groupAt.steps.filter((_, position) => position !== stepIndex) }
                            : groupAt,
                        ),
                      )
                    }
                  />
                ))}
                <Button
                  size="small"
                  onClick={() =>
                    setGroups((current) =>
                      current.map((groupAt, at) =>
                        at === groupIndex ? { ...groupAt, steps: [...groupAt.steps, { ...EMPTY_STEP }] } : groupAt,
                      ),
                    )
                  }
                  data-testid={`workflow-add-step-${groupIndex}`}
                >
                  Add step to group {groupIndex + 1}
                </Button>
              </Stack>
            </Paper>
          ))}
          <Button
            size="small"
            onClick={() => setGroups((current) => [...current, { mode: "ALL", steps: [{ ...EMPTY_STEP }] }])}
            data-testid="workflow-add-group"
          >
            Add group
          </Button>
          {errorValues.length > 0 ? (
            <Alert severity="error" data-testid="workflow-validation">
              {errorValues.map((error) => (
                <div key={error}>{error}</div>
              ))}
            </Alert>
          ) : null}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button
          variant="contained"
          disabled={errorValues.length > 0 || save.isPending}
          onClick={() => save.mutate()}
          data-testid="workflow-save"
        >
          {save.isPending ? "Saving..." : creating ? "Create" : "Save"}
        </Button>
        {save.isPending ? <LinearProgress sx={{ width: 80 }} /> : null}
      </DialogActions>
    </Dialog>
  );
}

interface StepEditorProps {
  step: DraftStep;
  tenants: Array<{ id: string; name: string }>;
  error: string | undefined;
  onChange: (patch: Partial<DraftStep>) => void;
  onRemove: () => void;
}

/** One step of a group: the role select, the optional tenant scope,
 * the optional trigger condition, and the remove affordance. */
function StepEditor({ step, tenants, error, onChange, onRemove }: StepEditorProps) {
  const [conditionOpen, setConditionOpen] = useState(step.condition !== null);
  return (
    <Paper variant="outlined" sx={{ padding: 1.5 }}>
      <Stack direction="row" spacing={1.5} sx={{ alignItems: "center", flexWrap: "wrap" as const }}>
        <TextField
          select
          label="Required role"
          value={step.role}
          onChange={(event) => onChange({ role: event.target.value as ApprovalRole })}
          size="small"
          sx={{ minWidth: 190 }}
          data-testid="workflow-step-role"
        >
          {APPROVAL_ROLES.map((role) => (
            <MenuItem key={role} value={role}>
              {role}
            </MenuItem>
          ))}
        </TextField>
        <Autocomplete
          size="small"
          options={tenants}
          getOptionLabel={(option) => option.name}
          isOptionEqualToValue={(option, value) => option.id === value.id}
          value={tenants.find((tenant) => tenant.id === step.tenantId) ?? null}
          onChange={(_event, value) => onChange({ tenantId: value?.id ?? null })}
          sx={{ minWidth: 180 }}
          renderInput={(params) => (
            <TextField {...params} label="Tenant scope (optional)" helperText="Only releases targeting this tenant" />
          )}
          data-testid="workflow-step-tenant"
        />
        <Button size="small" onClick={() => setConditionOpen((open) => !open)} data-testid="workflow-step-condition-toggle">
          {conditionOpen ? "Remove condition" : "Add condition"}
        </Button>
        <Box sx={{ flexGrow: 1 }} />
        <Tooltip title="Remove this step">
          <IconButton size="small" onClick={onRemove} aria-label="Remove step" data-testid="workflow-step-remove">
            <DeleteIcon fontSize="small" />
          </IconButton>
        </Tooltip>
      </Stack>
      {conditionOpen ? (
        <Stack direction="row" spacing={1.5} sx={{ marginTop: 1.5, flexWrap: "wrap" as const }}>
          <TextField
            select
            label="Condition"
            value={step.condition?.type ?? "ENVIRONMENT_EQUALS"}
            onChange={(event) =>
              onChange({
                condition: {
                  type: event.target.value as (typeof CONDITION_TYPES)[number],
                  key: step.condition?.key ?? "",
                  value: step.condition?.value ?? "",
                },
              })
            }
            size="small"
            sx={{ minWidth: 220 }}
            data-testid="workflow-condition-type"
          >
            {CONDITION_TYPES.map((type) => (
              <MenuItem key={type} value={type}>
                {type}
              </MenuItem>
            ))}
          </TextField>
          {(step.condition?.type ?? "ENVIRONMENT_EQUALS") === "ENVIRONMENT_EQUALS" ? (
            <TextField
              label="Environment code"
              value={step.condition?.value ?? ""}
              onChange={(event) =>
                onChange({
                  condition: {
                    type: "ENVIRONMENT_EQUALS",
                    key: "",
                    value: event.target.value,
                  },
                })
              }
              size="small"
              error={error !== undefined}
              helperText={error ?? "The step demands its signature only on this environment"}
              slotProps={{ htmlInput: { "data-testid": "workflow-condition-env" } }}
            />
          ) : (
            <>
              <TextField
                label="Flag key"
                value={step.condition?.key ?? ""}
                onChange={(event) =>
                  onChange({
                    condition: {
                      type: "FEATURE_FLAG_CONTAINS",
                      key: event.target.value,
                      value: step.condition?.value ?? "",
                    },
                  })
                }
                size="small"
                error={error !== undefined}
                helperText={error ?? "The environment's resolved configuration must carry this flag"}
                slotProps={{ htmlInput: { "data-testid": "workflow-condition-key" } }}
              />
              <TextField
                label="Flag value (optional)"
                value={step.condition?.value ?? ""}
                onChange={(event) =>
                  onChange({
                    condition: {
                      type: "FEATURE_FLAG_CONTAINS",
                      key: step.condition?.key ?? "",
                      value: event.target.value,
                    },
                  })
                }
                size="small"
                helperText="Empty = any value"
                slotProps={{ htmlInput: { "data-testid": "workflow-condition-value" } }}
              />
            </>
          )}
        </Stack>
      ) : null}
    </Paper>
  );
}
