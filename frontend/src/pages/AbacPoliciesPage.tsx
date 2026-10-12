/**
 * ABAC policies editor (S14-04, AC4/AC6) — the console surface of
 * the attribute-based access control engine: the organization's
 * versioned DENY / REQUIRE rules in their defined evaluation order,
 * each with its governing action, its selectors, its typed condition,
 * and the human summary the server renders. The editor is a JSON
 * condition editor with the PRD 25 example as a one-click template:
 * the client validates the JSON parses (a syntax gate, never the
 * grammar — the server's AbacCondition parse is the single
 * authority, and its field errors surface inline); references (the
 * application or environment a rule scopes to, the ring it guards)
 * resolve server-side at write. Both effects only ever NARROW access
 * (deny-override over the RBAC base layer); every save appends an
 * immutable version row, and the version history dialog reads them
 * back.
 */
import AddIcon from "@mui/icons-material/Add";
import DeleteIcon from "@mui/icons-material/Delete";
import HistoryIcon from "@mui/icons-material/History";
import {
  Alert,
  Box,
  Button,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  FormControlLabel,
  LinearProgress,
  MenuItem,
  Paper,
  Stack,
  Switch,
  TextField,
  Typography,
} from "@mui/material";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import {
  createAbacPolicy,
  deleteAbacPolicy,
  listAbacPolicies,
  listAbacPolicyVersions,
  updateAbacPolicy,
  type AbacConditionNode,
  type AbacPolicy,
} from "../api/abac";
import { ApiError } from "../api/problem";
import { listEnvironments } from "../api/environments";
import { listApplications } from "../api/applications";
import { listRings } from "../api/rings";
import { useAuth } from "../auth/AuthContext";
import { useToast } from "../components/ToastContext";

/** The PRD 25 example, one click to a well-formed draft (the
 * editor's humane entry into the typed condition format). */
const TEMPLATES: {
  label: string;
  name: string;
  effect: AbacPolicy["effect"];
  action: AbacPolicy["action"];
  condition: string;
}[] = [
  {
    label: "PRD 25: Banking promote needs ReleaseManager + approved security",
    name: "banking-production-promote",
    effect: "REQUIRE",
    action: "PROMOTE",
    condition: JSON.stringify(
      { allOf: [{ subjectOrgRole: "RELEASE_MANAGER" }, { securityStatus: "APPROVED" }] },
      null,
      2,
    ),
  },
  {
    label: "Deny downloads by viewers",
    name: "no-viewer-downloads",
    effect: "DENY",
    action: "DOWNLOAD",
    condition: JSON.stringify({ subjectOrgRole: "VIEWER" }, null, 2),
  },
  {
    label: "Promotions need security approval",
    name: "promotions-need-security",
    effect: "REQUIRE",
    action: "PROMOTE",
    condition: JSON.stringify({ securityStatus: "APPROVED" }, null, 2),
  },
];

/** The editor's draft: everything the dialog writes. */
interface Draft {
  name: string;
  effect: AbacPolicy["effect"];
  action: AbacPolicy["action"];
  applicationId: string;
  environmentId: string;
  ring: string;
  condition: string;
  position: string;
  enabled: boolean;
}

function draftOf(policy: AbacPolicy | null): Draft {
  return {
    name: policy?.name ?? "",
    effect: policy?.effect ?? "REQUIRE",
    action: policy?.action ?? "PROMOTE",
    applicationId: policy?.applicationId ?? "",
    environmentId: policy?.environmentId ?? "",
    ring: policy?.ring ?? "",
    condition: policy === null ? "" : JSON.stringify(policy.condition, null, 2),
    position: policy === null ? "10" : String(policy.position),
    enabled: policy?.enabled ?? true,
  };
}

export default function AbacPoliciesPage() {
  const { user } = useAuth();
  const orgId = user?.orgId ?? null;
  const toast = useToast();
  const queryClient = useQueryClient();
  const [editorOpen, setEditorOpen] = useState(false);
  const [editing, setEditing] = useState<AbacPolicy | null>(null);
  const [draft, setDraft] = useState<Draft>(draftOf(null));
  const [conditionError, setConditionError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [historyOf, setHistoryOf] = useState<AbacPolicy | null>(null);

  const policies = useQuery({
    queryKey: ["abac-policies", orgId],
    queryFn: () => listAbacPolicies(orgId as string),
    enabled: orgId !== null,
  });
  const applications = useQuery({
    queryKey: ["abac-applications", orgId],
    queryFn: () => listApplications({ organizationId: orgId as string, limit: 100 }),
    enabled: orgId !== null,
  });
  const environments = useQuery({
    queryKey: ["abac-environments", orgId],
    queryFn: () => listEnvironments(orgId as string),
    enabled: orgId !== null,
  });
  const rings = useQuery({
    queryKey: ["abac-rings", orgId],
    queryFn: () => listRings(orgId as string),
    enabled: orgId !== null,
  });
  const history = useQuery({
    queryKey: ["abac-policy-versions", orgId, historyOf?.id],
    queryFn: () => listAbacPolicyVersions(orgId as string, (historyOf as AbacPolicy).id),
    enabled: orgId !== null && historyOf !== null,
  });

  async function refresh() {
    await queryClient.invalidateQueries({ queryKey: ["abac-policies", orgId] });
    if (historyOf !== null) {
      await queryClient.invalidateQueries({ queryKey: ["abac-policy-versions", orgId, historyOf.id] });
    }
  }

  const save = useMutation({
    mutationFn: async () => {
      let condition: AbacConditionNode;
      try {
        condition = JSON.parse(draft.condition);
      } catch {
        throw new Error("The condition does not parse as JSON");
      }
      const body = {
        organizationId: orgId as string,
        name: draft.name.trim(),
        effect: draft.effect,
        action: draft.action,
        applicationId: draft.applicationId.trim() !== "" ? draft.applicationId.trim() : null,
        environmentId: draft.environmentId.trim() !== "" ? draft.environmentId.trim() : null,
        ring: draft.ring.trim() !== "" ? draft.ring.trim() : null,
        condition,
        position: Number(draft.position),
        enabled: draft.enabled,
      };
      return editing === null
        ? createAbacPolicy(body)
        : updateAbacPolicy(orgId as string, editing.id, body);
    },
    onSuccess: async () => {
      toast.showSuccess("ABAC policy saved");
      setEditorOpen(false);
      await refresh();
    },
    onError: (error) => {
      if (error instanceof ApiError) {
        setFieldErrors(error.fieldErrors ?? {});
        toast.showError(error);
        return;
      }
      setConditionError(error instanceof Error ? error.message : "The policy could not be saved");
    },
  });

  const remove = useMutation({
    mutationFn: (policy: AbacPolicy) => deleteAbacPolicy(orgId as string, policy.id),
    onSuccess: async () => {
      toast.showSuccess("ABAC policy deleted");
      await refresh();
    },
    onError: (error) => {
      toast.showError(error);
    },
  });

  function openCreate() {
    setEditing(null);
    setDraft(draftOf(null));
    setConditionError(null);
    setFieldErrors({});
    setEditorOpen(true);
  }

  function openEdit(policy: AbacPolicy) {
    setEditing(policy);
    setDraft(draftOf(policy));
    setConditionError(null);
    setFieldErrors({});
    setEditorOpen(true);
  }

  function applyTemplate(template: (typeof TEMPLATES)[number]) {
    setDraft((current) => ({
      ...current,
      name: current.name.trim() === "" ? template.name : current.name,
      effect: template.effect,
      action: template.action,
      condition: template.condition,
    }));
  }

  const effectColor: Record<AbacPolicy["effect"], "error" | "warning"> = {
    DENY: "error",
    REQUIRE: "warning",
  };

  return (
    <Stack spacing={2}>
      <Stack direction="row" spacing={2} sx={{ alignItems: "center" }}>
        <Typography variant="h5" sx={{ flexGrow: 1 }} data-testid="abac-title">
          Access policies (ABAC)
        </Typography>
        <Button variant="contained" startIcon={<AddIcon />} onClick={openCreate} data-testid="abac-create">
          New policy
        </Button>
      </Stack>
      <Typography color="text.secondary">
        Attribute-based rules that NARROW access beyond roles — deny-override over the RBAC base
        layer, never a widening. A DENY rule refuses when its condition holds; a REQUIRE rule demands
        its condition for every request its action and selectors match (the PRD 25 example&apos;s
        shape). Conditions are the typed JSON format the conditional approvals and release policies
        share; an attribute the decision cannot resolve fails closed. Every save is versioned and
        audited; the version history icon reads the immutable rows back.
      </Typography>
      {policies.isError ? (
        <Alert severity="error" data-testid="abac-error">
          Failed to load the ABAC policies.
        </Alert>
      ) : policies.isPending ? (
        <LinearProgress aria-label="loading ABAC policies" />
      ) : (
        <Stack spacing={1} data-testid="abac-list">
          {(policies.data ?? []).length === 0 ? (
            <Paper variant="outlined" sx={{ padding: 2 }}>
              <Typography color="text.secondary">
                No policies authored: every access decision answers RBAC and the layered engine
                alone.
              </Typography>
            </Paper>
          ) : null}
          {(policies.data ?? []).map((policy) => (
            <Paper key={policy.id} variant="outlined" sx={{ padding: 2 }} data-testid={`abac-${policy.name}`}>
              <Stack direction="row" spacing={2} sx={{ alignItems: "center", flexWrap: "wrap" as const }}>
                <Typography variant="subtitle1" sx={{ fontFamily: "monospace", minWidth: 90 }}>
                  {policy.position}. {policy.name}
                </Typography>
                <Chip size="small" color={effectColor[policy.effect]} label={policy.effect} />
                <Chip size="small" variant="outlined" label={`action: ${policy.action}`} />
                <Chip size="small" variant="outlined" label={`v${policy.version}`} />
                {policy.applicationId !== null ? (
                  <Chip
                    size="small"
                    variant="outlined"
                    label={`application ${
                      (applications.data?.items ?? []).find(
                        (application: { id: string; name: string }) => application.id === policy.applicationId,
                      )?.name ?? policy.applicationId
                    }`}
                  />
                ) : null}
                {policy.environmentId !== null ? (
                  <Chip
                    size="small"
                    variant="outlined"
                    label={`environment ${
                      (environments.data?.items ?? []).find(
                        (environment: { id: string; name: string }) => environment.id === policy.environmentId,
                      )?.name ?? policy.environmentId
                    }`}
                  />
                ) : null}
                {policy.ring !== null ? <Chip size="small" variant="outlined" label={`ring ${policy.ring}`} /> : null}
                {!policy.enabled ? <Chip size="small" variant="outlined" label="disabled" /> : null}
                <Typography variant="body2" sx={{ fontFamily: "monospace", flexGrow: 1 }}>
                  {policy.conditionSummary}
                </Typography>
                <Button size="small" startIcon={<HistoryIcon />} onClick={() => setHistoryOf(policy)}>
                  History
                </Button>
                <Button size="small" onClick={() => openEdit(policy)} data-testid={`abac-edit-${policy.name}`}>
                  Edit
                </Button>
                <Button
                  size="small"
                  color="error"
                  startIcon={<DeleteIcon />}
                  onClick={() => remove.mutate(policy)}
                  data-testid={`abac-delete-${policy.name}`}
                >
                  Delete
                </Button>
              </Stack>
            </Paper>
          ))}
        </Stack>
      )}

      <Dialog open={editorOpen} onClose={() => setEditorOpen(false)} fullWidth maxWidth="md">
        <DialogTitle>{editing === null ? "New ABAC policy" : `Edit ${editing.name}`}</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ paddingTop: 1 }}>
            <Stack direction="row" spacing={2} sx={{ flexWrap: "wrap" as const }}>
              <TextField
                label="Name"
                sx={{ minWidth: 240 }}
                value={draft.name}
                onChange={(event) => setDraft({ ...draft, name: event.target.value })}
                error={fieldErrors.name !== undefined}
                helperText={fieldErrors.name}
                data-testid="abac-field-name"
              />
              <TextField
                select
                label="Effect"
                sx={{ minWidth: 150 }}
                value={draft.effect}
                onChange={(event) => setDraft({ ...draft, effect: event.target.value as AbacPolicy["effect"] })}
                data-testid="abac-field-effect"
              >
                <MenuItem value="DENY">DENY (refuse when it holds)</MenuItem>
                <MenuItem value="REQUIRE">REQUIRE (must hold)</MenuItem>
              </TextField>
              <TextField
                select
                label="Action"
                sx={{ minWidth: 200 }}
                value={draft.action}
                onChange={(event) => setDraft({ ...draft, action: event.target.value as AbacPolicy["action"] })}
                error={fieldErrors.action !== undefined}
                helperText={fieldErrors.action}
                data-testid="abac-field-action"
              >
                <MenuItem value="PROMOTE">PROMOTE (ring hops)</MenuItem>
                <MenuItem value="DOWNLOAD">DOWNLOAD</MenuItem>
                <MenuItem value="READ_ARTIFACT">READ_ARTIFACT</MenuItem>
                <MenuItem value="LIST_APPLICATIONS">LIST_APPLICATIONS</MenuItem>
              </TextField>
              <TextField
                select
                label="Application (any if empty)"
                sx={{ minWidth: 220 }}
                value={draft.applicationId}
                onChange={(event) => setDraft({ ...draft, applicationId: event.target.value })}
                error={fieldErrors.applicationId !== undefined}
                helperText={fieldErrors.applicationId}
                data-testid="abac-field-application"
              >
                <MenuItem value="">Any application</MenuItem>
                {(applications.data?.items ?? []).map((application: { id: string; name: string }) => (
                  <MenuItem key={application.id} value={application.id}>
                    {application.name}
                  </MenuItem>
                ))}
              </TextField>
              <TextField
                select
                label="Environment (any if empty)"
                sx={{ minWidth: 200 }}
                value={draft.environmentId}
                onChange={(event) => setDraft({ ...draft, environmentId: event.target.value })}
                error={fieldErrors.environmentId !== undefined}
                helperText={fieldErrors.environmentId}
                data-testid="abac-field-environment"
              >
                <MenuItem value="">Any environment</MenuItem>
                {(environments.data?.items ?? []).map((environment: { id: string; name: string }) => (
                  <MenuItem key={environment.id} value={environment.id}>
                    {environment.name}
                  </MenuItem>
                ))}
              </TextField>
              <TextField
                select
                label="Ring (any if empty)"
                sx={{ minWidth: 180 }}
                value={draft.ring}
                onChange={(event) => setDraft({ ...draft, ring: event.target.value })}
                error={fieldErrors.ring !== undefined}
                helperText={fieldErrors.ring}
                data-testid="abac-field-ring"
              >
                <MenuItem value="">Any ring</MenuItem>
                {(rings.data ?? []).map((ring: { id: string; code: string }) => (
                  <MenuItem key={ring.id} value={ring.code}>
                    {ring.code}
                  </MenuItem>
                ))}
              </TextField>
              <TextField
                label="Position (order)"
                sx={{ maxWidth: 160 }}
                value={draft.position}
                onChange={(event) => setDraft({ ...draft, position: event.target.value })}
                error={fieldErrors.position !== undefined}
                helperText={fieldErrors.position}
                data-testid="abac-field-position"
              />
              <FormControlLabel
                control={
                  <Switch
                    checked={draft.enabled}
                    onChange={(event) => setDraft({ ...draft, enabled: event.target.checked })}
                    data-testid="abac-field-enabled"
                  />
                }
                label="Enabled"
              />
            </Stack>
            <Box>
              <Typography variant="body2" color="text.secondary" sx={{ marginBottom: 1 }}>
                Templates:
                {TEMPLATES.map((template) => (
                  <Chip
                    key={template.label}
                    size="small"
                    variant="outlined"
                    label={template.label}
                    sx={{ margin: 0.5 }}
                    onClick={() => applyTemplate(template)}
                    data-testid={`abac-template-${template.name}`}
                  />
                ))}
              </Typography>
            </Box>
            <TextField
              label="Condition (JSON)"
              multiline
              minRows={5}
              fullWidth
              sx={{ fontFamily: "monospace" }}
              value={draft.condition}
              onChange={(event) => {
                setConditionError(null);
                setDraft({ ...draft, condition: event.target.value });
              }}
              error={conditionError !== null || fieldErrors.condition !== undefined}
              helperText={
                conditionError ??
                fieldErrors.condition ??
                "Atoms: subjectOrgRole, subjectPlatformAdmin, subjectLinkedTester, applicationName, "
                  + "applicationPlatform, environmentName, ringCode, securityStatus; combinators: allOf, anyOf"
              }
              data-testid="abac-field-condition"
            />
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setEditorOpen(false)} data-testid="abac-cancel">
            Cancel
          </Button>
          <Button variant="contained" onClick={() => save.mutate()} disabled={save.isPending} data-testid="abac-save">
            Save policy
          </Button>
        </DialogActions>
      </Dialog>

      <Dialog open={historyOf !== null} onClose={() => setHistoryOf(null)} fullWidth maxWidth="md">
        <DialogTitle>Version history — {historyOf?.name}</DialogTitle>
        <DialogContent>
          {history.isError ? (
            <Alert severity="error">Failed to load the version history.</Alert>
          ) : history.isPending ? (
            <LinearProgress aria-label="loading version history" />
          ) : (
            <Stack spacing={1} data-testid="abac-history-list">
              {(history.data ?? []).map((version) => (
                <Paper key={version.id} variant="outlined" sx={{ padding: 1.5 }}>
                  <Stack direction="row" spacing={2} sx={{ alignItems: "center", flexWrap: "wrap" as const }}>
                    <Chip size="small" label={`v${version.version}`} />
                    <Chip size="small" variant="outlined" label={version.effect} />
                    <Chip size="small" variant="outlined" label={version.action} />
                    {!version.enabled ? <Chip size="small" variant="outlined" label="disabled" /> : null}
                    <Typography variant="body2" sx={{ fontFamily: "monospace", flexGrow: 1 }}>
                      {version.conditionSummary}
                    </Typography>
                    <Typography variant="caption" color="text.secondary">
                      {new Date(version.createdAt).toLocaleString()} — author {version.authoredBy}
                    </Typography>
                  </Stack>
                </Paper>
              ))}
            </Stack>
          )}
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setHistoryOf(null)} data-testid="abac-history-close">
            Close
          </Button>
        </DialogActions>
      </Dialog>
    </Stack>
  );
}
