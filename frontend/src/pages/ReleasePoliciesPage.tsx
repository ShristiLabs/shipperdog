/**
 * Release policies editor (S10-06, AC6) — the console surface of the
 * declarative policy engine: the organization's standing BLOCK /
 * HOLD_FOR_REVIEW / ALLOW rules in their defined evaluation order,
 * each with its scope, its typed condition, and the human summary
 * the server renders. The editor is a JSON condition editor with the
 * PRD 33 examples as one-click templates: the client validates the
 * JSON parses (a syntax gate, never the grammar — the server's
 * PolicyCondition parse is the single authority, and its field
 * errors surface inline); references (the ring a rule scopes to, the
 * gate types it names) resolve server-side at write. The
 * skip-relaxation knob edits beside the rules: the named rings a
 * promotion may carry beyond the next (the default posture — one
 * ring at a time — stands for every organization that never edits
 * it).
 */
import AddIcon from "@mui/icons-material/Add";
import DeleteIcon from "@mui/icons-material/Delete";
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
import { ApiError } from "../api/problem";
import {
  createPolicyRule,
  deletePolicyRule,
  getSkipAllowances,
  listPolicyRules,
  replaceSkipAllowances,
  updatePolicyRule,
  type PolicyConditionNode,
  type PolicyRule,
} from "../api/policies";
import { listRings } from "../api/rings";
import { useAuth } from "../auth/AuthContext";
import { useToast } from "../components/ToastContext";

/** The PRD 33 rule examples, one click to a well-formed draft (the
 * editor's humane entry into the typed condition format). */
const TEMPLATES: { label: string; name: string; action: PolicyRule["action"]; condition: string }[] = [
  {
    label: "vulnerabilities > 0 -> BLOCK",
    name: "no-critical-vulns",
    action: "BLOCK",
    condition: JSON.stringify({ metric: "CRITICAL_VULNERABILITIES", operator: ">", value: 0 }, null, 2),
  },
  {
    label: "crash-free < threshold -> BLOCK",
    name: "crash-free-floor",
    action: "BLOCK",
    condition: JSON.stringify({ metric: "CRASH_FREE_RATE", operator: "<", value: 0.99 }, null, 2),
  },
  {
    label: "install success OR engagement low -> HOLD",
    name: "engagement-floor",
    action: "HOLD_FOR_REVIEW",
    condition: JSON.stringify(
      {
        anyOf: [
          { metric: "INSTALL_SUCCESS_RATE", operator: "<", value: 0.9 },
          { metric: "TESTER_ENGAGEMENT", operator: "<", value: 0.5 },
        ],
      },
      null,
      2,
    ),
  },
  {
    label: "approval high AND bugs zero -> ALLOW",
    name: "healthy-and-approved",
    action: "ALLOW",
    condition: JSON.stringify(
      {
        allOf: [
          { metric: "TESTER_APPROVAL_RATE", operator: ">=", value: 0.8 },
          { metric: "OPEN_CRITICAL_BUGS", operator: "=", value: 0 },
        ],
      },
      null,
      2,
    ),
  },
];

/** The editor's draft: everything the dialog writes. */
interface Draft {
  name: string;
  scope: PolicyRule["scope"];
  applicationId: string;
  ring: string;
  environmentId: string;
  action: PolicyRule["action"];
  condition: string;
  position: string;
  enabled: boolean;
}

function draftOf(rule: PolicyRule | null): Draft {
  return {
    name: rule?.name ?? "",
    scope: rule?.scope ?? "ORGANIZATION",
    applicationId: rule?.applicationId ?? "",
    ring: rule?.ring ?? "",
    environmentId: rule?.environmentId ?? "",
    action: rule?.action ?? "BLOCK",
    condition: rule === null ? "" : JSON.stringify(rule.condition, null, 2),
    position: rule === null ? "10" : String(rule.position),
    enabled: rule?.enabled ?? true,
  };
}

export default function ReleasePoliciesPage() {
  const { user } = useAuth();
  const orgId = user?.orgId ?? null;
  const toast = useToast();
  const queryClient = useQueryClient();
  const [editorOpen, setEditorOpen] = useState(false);
  const [editing, setEditing] = useState<PolicyRule | null>(null);
  const [draft, setDraft] = useState<Draft>(draftOf(null));
  const [conditionError, setConditionError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  // The skip editor's edit state: null means untouched, and the
  // effective value falls back to the loaded standing — the draft is
  // the single source of truth the moment it is touched.
  const [skipEdit, setSkipEdit] = useState<string | null>(null);

  const rules = useQuery({
    queryKey: ["policy-rules", orgId],
    queryFn: () => listPolicyRules(orgId as string),
    enabled: orgId !== null,
  });
  const rings = useQuery({
    queryKey: ["rings", orgId],
    queryFn: () => listRings(orgId as string),
    enabled: orgId !== null,
  });
  const skipAllowances = useQuery({
    queryKey: ["policy-skip-allowances", orgId],
    queryFn: () => getSkipAllowances(orgId as string),
    enabled: orgId !== null,
  });

  async function refresh() {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ["policy-rules", orgId] }),
      queryClient.invalidateQueries({ queryKey: ["policy-skip-allowances", orgId] }),
    ]);
  }

  const save = useMutation({
    mutationFn: async () => {
      let condition: PolicyConditionNode;
      try {
        condition = JSON.parse(draft.condition);
      } catch {
        throw new Error("The condition does not parse as JSON");
      }
      const body = {
        organizationId: orgId as string,
        name: draft.name.trim(),
        scope: draft.scope,
        applicationId: draft.scope === "APPLICATION" && draft.applicationId.trim() !== "" ? draft.applicationId.trim() : null,
        ring: draft.scope === "RING" && draft.ring.trim() !== "" ? draft.ring.trim() : null,
        environmentId:
          draft.scope === "ENVIRONMENT" && draft.environmentId.trim() !== "" ? draft.environmentId.trim() : null,
        action: draft.action,
        condition,
        position: Number(draft.position),
        enabled: draft.enabled,
      };
      return editing === null
        ? createPolicyRule(body)
        : updatePolicyRule(orgId as string, editing.id, body);
    },
    onSuccess: async () => {
      toast.showSuccess("Policy rule saved");
      setEditorOpen(false);
      await refresh();
    },
    onError: (error) => {
      if (error instanceof ApiError) {
        setFieldErrors(error.fieldErrors ?? {});
        toast.showError(error);
        return;
      }
      setConditionError(error instanceof Error ? error.message : "The rule could not be saved");
    },
  });

  const remove = useMutation({
    mutationFn: (rule: PolicyRule) => deletePolicyRule(orgId as string, rule.id),
    onSuccess: async () => {
      toast.showSuccess("Policy rule deleted");
      await refresh();
    },
    onError: (error) => {
      toast.showError(error);
    },
  });

  const saveSkip = useMutation({
    mutationFn: () => {
      // The effective value IS the standing until the operator edits:
      // the whole-set PUT always carries what the screen shows — an
      // untouched Save re-puts the loaded set, never a silent wipe.
      const named = (skipEdit ?? (skipAllowances.data?.rings ?? []).join(", "))
        .split(",")
        .map((code) => code.trim())
        .filter((code) => code !== "");
      return replaceSkipAllowances(orgId as string, named);
    },
    onSuccess: async () => {
      toast.showSuccess("Skip allowances saved");
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

  function openEdit(rule: PolicyRule) {
    setEditing(rule);
    setDraft(draftOf(rule));
    setConditionError(null);
    setFieldErrors({});
    setEditorOpen(true);
  }

  function applyTemplate(template: (typeof TEMPLATES)[number]) {
    setDraft((current) => ({
      ...current,
      name: current.name.trim() === "" ? template.name : current.name,
      action: template.action,
      condition: template.condition,
    }));
  }

  const actionColor: Record<PolicyRule["action"], "error" | "warning" | "success"> = {
    BLOCK: "error",
    HOLD_FOR_REVIEW: "warning",
    ALLOW: "success",
  };

  return (
    <Stack spacing={2}>
      <Stack direction="row" spacing={2} sx={{ alignItems: "center" }}>
        <Typography variant="h5" sx={{ flexGrow: 1 }} data-testid="policies-title">
          Release policies
        </Typography>
        <Button variant="contained" startIcon={<AddIcon />} onClick={openCreate} data-testid="policy-create">
          New rule
        </Button>
      </Stack>
      <Typography color="text.secondary">
        Standing rules the promotion precheck evaluates mechanically, in the order defined below:
        the first BLOCK rule to fire wins and answers the 409 with its reference; a HOLD rule holds
        the hop until a privileged acknowledge records the decision; an ALLOW rule evaluates and
        logs but decides nothing (the seam V3&apos;s automated promotion will act on). Conditions
        are the typed JSON format the conditional approvals use; a metric analytics cannot answer
        fails closed with an explicit no-data outcome.
      </Typography>
      {rules.isError ? (
        <Alert severity="error" data-testid="policies-error">
          Failed to load the policy rules.
        </Alert>
      ) : rules.isPending ? (
        <LinearProgress aria-label="loading policy rules" />
      ) : (
        <Stack spacing={1} data-testid="policy-list">
          {(rules.data ?? []).length === 0 ? (
            <Paper variant="outlined" sx={{ padding: 2 }}>
              <Typography color="text.secondary">
                No rules authored: every promotion answers the engine&apos;s other legs alone.
              </Typography>
            </Paper>
          ) : null}
          {(rules.data ?? []).map((rule) => (
            <Paper key={rule.id} variant="outlined" sx={{ padding: 2 }} data-testid={`policy-${rule.name}`}>
              <Stack direction="row" spacing={2} sx={{ alignItems: "center", flexWrap: "wrap" as const }}>
                <Typography variant="subtitle1" sx={{ fontFamily: "monospace", minWidth: 90 }}>
                  {rule.position}. {rule.name}
                </Typography>
                <Chip size="small" color={actionColor[rule.action]} label={rule.action} />
                <Chip
                  size="small"
                  variant="outlined"
                  label={
                    rule.scope === "ORGANIZATION"
                      ? "scope: organization"
                      : rule.scope === "APPLICATION"
                        ? `scope: application ${rule.applicationId}`
                        : rule.scope === "RING"
                          ? `scope: ring ${rule.ring}`
                          : `scope: environment ${rule.environmentId}`
                  }
                />
                {!rule.enabled ? <Chip size="small" variant="outlined" label="disabled" /> : null}
                <Typography variant="body2" sx={{ fontFamily: "monospace", flexGrow: 1 }}>
                  {rule.conditionSummary}
                </Typography>
                <Button size="small" onClick={() => openEdit(rule)} data-testid={`policy-edit-${rule.name}`}>
                  Edit
                </Button>
                <Button
                  size="small"
                  color="error"
                  startIcon={<DeleteIcon />}
                  onClick={() => remove.mutate(rule)}
                  data-testid={`policy-delete-${rule.name}`}
                >
                  Delete
                </Button>
              </Stack>
            </Paper>
          ))}
        </Stack>
      )}
      <Paper variant="outlined" sx={{ padding: 2 }} data-testid="policy-skip-allowances">
        <Stack spacing={1}>
          <Typography variant="h6">Skip allowances</Typography>
          <Typography color="text.secondary">
            The rings a promotion may name BEYOND the next (the default posture moves one ring at a
            time; an empty list keeps it). Comma-separated ring codes; the server validates every
            code against the organization&apos;s vocabulary.
          </Typography>
          <Stack direction="row" spacing={2} sx={{ alignItems: "center" }}>
            <TextField
              size="small"
              sx={{ maxWidth: 360 }}
              value={skipEdit ?? (skipAllowances.data?.rings ?? []).join(", ")}
              onChange={(event) => setSkipEdit(event.target.value)}
              placeholder="uat"
              data-testid="policy-skip-input"
            />
            <Button
              variant="outlined"
              onClick={() => saveSkip.mutate()}
              disabled={saveSkip.isPending}
              data-testid="policy-skip-save"
            >
              Save
            </Button>
            <Typography variant="body2" color="text.secondary">
              current: {(skipAllowances.data?.rings ?? []).length === 0 ? "none (default posture)" : skipAllowances.data?.rings.join(", ")}
            </Typography>
          </Stack>
        </Stack>
      </Paper>

      <Dialog open={editorOpen} onClose={() => setEditorOpen(false)} fullWidth maxWidth="md">
        <DialogTitle>{editing === null ? "New policy rule" : `Edit ${editing.name}`}</DialogTitle>
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
                data-testid="policy-field-name"
              />
              <TextField
                select
                label="Action"
                sx={{ minWidth: 180 }}
                value={draft.action}
                onChange={(event) => setDraft({ ...draft, action: event.target.value as PolicyRule["action"] })}
                data-testid="policy-field-action"
              >
                <MenuItem value="BLOCK">BLOCK</MenuItem>
                <MenuItem value="HOLD_FOR_REVIEW">HOLD_FOR_REVIEW</MenuItem>
                <MenuItem value="ALLOW">ALLOW</MenuItem>
              </TextField>
              <TextField
                select
                label="Scope"
                sx={{ minWidth: 180 }}
                value={draft.scope}
                onChange={(event) => setDraft({ ...draft, scope: event.target.value as PolicyRule["scope"] })}
                data-testid="policy-field-scope"
              >
                <MenuItem value="ORGANIZATION">Organization</MenuItem>
                <MenuItem value="APPLICATION">Application</MenuItem>
                <MenuItem value="RING">Ring (entry)</MenuItem>
                <MenuItem value="ENVIRONMENT">Environment</MenuItem>
              </TextField>
              {draft.scope === "APPLICATION" ? (
                <TextField
                  label="Application id"
                  sx={{ minWidth: 280 }}
                  value={draft.applicationId}
                  onChange={(event) => setDraft({ ...draft, applicationId: event.target.value })}
                  error={fieldErrors.applicationId !== undefined}
                  helperText={fieldErrors.applicationId}
                  data-testid="policy-field-application"
                />
              ) : null}
              {draft.scope === "RING" ? (
                <TextField
                  select
                  label="Ring"
                  sx={{ minWidth: 200 }}
                  value={draft.ring}
                  onChange={(event) => setDraft({ ...draft, ring: event.target.value })}
                  error={fieldErrors.ring !== undefined}
                  helperText={fieldErrors.ring}
                  data-testid="policy-field-ring"
                >
                  {(rings.data ?? []).map((ring: { id: string; code: string }) => (
                    <MenuItem key={ring.id} value={ring.code}>
                      {ring.code}
                    </MenuItem>
                  ))}
                </TextField>
              ) : null}
              {draft.scope === "ENVIRONMENT" ? (
                <TextField
                  label="Environment id"
                  sx={{ minWidth: 280 }}
                  value={draft.environmentId}
                  onChange={(event) => setDraft({ ...draft, environmentId: event.target.value })}
                  error={fieldErrors.environmentId !== undefined}
                  helperText={fieldErrors.environmentId}
                  data-testid="policy-field-environment"
                />
              ) : null}
              <TextField
                label="Position (order)"
                sx={{ maxWidth: 160 }}
                value={draft.position}
                onChange={(event) => setDraft({ ...draft, position: event.target.value })}
                error={fieldErrors.position !== undefined}
                helperText={fieldErrors.position}
                data-testid="policy-field-position"
              />
              <FormControlLabel
                control={
                  <Switch
                    checked={draft.enabled}
                    onChange={(event) => setDraft({ ...draft, enabled: event.target.checked })}
                    data-testid="policy-field-enabled"
                  />
                }
                label="Enabled"
              />
            </Stack>
            <Box>
              <Typography variant="body2" color="text.secondary" sx={{ marginBottom: 1 }}>
                Templates (PRD 33&apos;s examples):
                {TEMPLATES.map((template) => (
                  <Chip
                    key={template.label}
                    size="small"
                    variant="outlined"
                    label={template.label}
                    sx={{ margin: 0.5 }}
                    onClick={() => applyTemplate(template)}
                    data-testid={`policy-template-${template.name}`}
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
              helperText={conditionError ?? fieldErrors.condition ?? "Atoms: metric (+operator/value), gate (+status), approvalBlocked, artifactPlatform; combinators: allOf, anyOf"}
              data-testid="policy-field-condition"
            />
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setEditorOpen(false)} data-testid="policy-cancel">
            Cancel
          </Button>
          <Button
            variant="contained"
            onClick={() => save.mutate()}
            disabled={save.isPending}
            data-testid="policy-save"
          >
            Save rule
          </Button>
        </DialogActions>
      </Dialog>
    </Stack>
  );
}
