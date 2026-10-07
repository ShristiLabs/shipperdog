/**
 * Release dashboard (issue #59, S9-05) — PRD 32's "heart of the
 * product": one screen per release showing build, quality, approvals,
 * and distribution state so a Release Manager judges release health at
 * a glance. Blocks: the artifact slots with their frozen pins (hashes
 * at APPROVED, the S9-03 manifest), quality gates and approvals (E10
 * data — honest empty states until S10-01/S10-03 land, per the story's
 * build-against-stable-APIs note), distribution (the starting ring and
 * target set; ring promotion tracking is E10), the append-only state
 * timeline (S9-02 history with actors/timestamps/reasons), the frozen
 * manifest viewer with the YAML compliance render and the diff against
 * another APPROVED release of the same application. Lifecycle actions
 * offer PRD 17's legal edges; no read-shaped surface is keyed to
 * app:releases:manage, so actions render for every legal edge and the
 * server's verdict is the gate (denials surface the problem+json code,
 * the ApplicationDetailPage precedent). Detail and timeline
 * auto-refresh (30s) — the dashboard is a live view.
 */
import ArrowBackIcon from "@mui/icons-material/ArrowBack";
import CompareArrowsIcon from "@mui/icons-material/CompareArrows";
import DownloadIcon from "@mui/icons-material/Download";
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
  Divider,
  LinearProgress,
  Paper,
  Stack,
  Step,
  StepLabel,
  Stepper,
  TextField,
  Tooltip,
  Typography,
} from "@mui/material";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useMemo, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { ApiError } from "../api/problem";
import { getApplicationVersion } from "../api/versions";
import {
  getManifestDiff,
  getRelease,
  getReleaseHistory,
  getReleaseManifest,
  getReleaseManifestYaml,
  listReleases,
  transitionRelease,
  type ManifestArtifactPin,
  type ManifestDiff,
  type ReleaseDetail,
  type ReleaseManifest,
  type ReleaseStatus,
} from "../api/releases";
import { useAuth } from "../auth/AuthContext";
import { useToast } from "../components/ToastContext";
import { useAllApplications, useAllOrgMembers, useAllTenants } from "../hooks/useOrganizationDirectory";
import {
  RELEASE_STATUS_COLORS,
  RELEASE_STATUS_LABELS,
  legalTransitions,
  transitionRequiresReason,
} from "./releaseRules";

const REFRESH_MS = 30_000;

function formatBytes(size: number): string {
  if (size < 1024) return `${size} B`;
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`;
  return `${(size / (1024 * 1024)).toFixed(1)} MB`;
}

function shortSha(sha: string): string {
  return sha.length > 12 ? `${sha.slice(0, 12)}...` : sha;
}

export default function ReleaseDetailPage() {
  const { applicationId, releaseId } = useParams<{ applicationId: string; releaseId: string }>();
  const applicationIdSafe = applicationId ?? "";
  const releaseIdSafe = releaseId ?? "";
  const { user } = useAuth();
  const orgId = user?.orgId ?? null;
  const toast = useToast();
  const queryClient = useQueryClient();

  const [reasonTarget, setReasonTarget] = useState<ReleaseStatus | null>(null);
  const [compareOpen, setCompareOpen] = useState(false);

  const detail = useQuery({
    queryKey: ["release", releaseIdSafe],
    queryFn: () => getRelease(applicationIdSafe, releaseIdSafe),
    enabled: applicationIdSafe !== "" && releaseIdSafe !== "",
    refetchInterval: REFRESH_MS,
  });
  const release = detail.data ?? null;

  const history = useQuery({
    queryKey: ["release-history", releaseIdSafe],
    queryFn: () => getReleaseHistory(applicationIdSafe, releaseIdSafe),
    enabled: applicationIdSafe !== "" && releaseIdSafe !== "",
    refetchInterval: REFRESH_MS,
  });

  // The manifest exists exactly when the release has crossed into
  // APPROVED; before that the surface's honest 404 is the empty state,
  // not an error. Every other failure is a real error.
  const manifest = useQuery({
    queryKey: ["release-manifest", releaseIdSafe],
    queryFn: () => getReleaseManifest(applicationIdSafe, releaseIdSafe),
    enabled: applicationIdSafe !== "" && releaseIdSafe !== "" && release !== null,
    retry: false,
    refetchInterval: REFRESH_MS,
  });
  const manifestMissing =
    manifest.isError &&
    manifest.error instanceof ApiError &&
    manifest.error.status === 404;

  const yaml = useQuery({
    queryKey: ["release-manifest-yaml", releaseIdSafe],
    queryFn: () => getReleaseManifestYaml(applicationIdSafe, releaseIdSafe),
    enabled: manifest.data !== undefined,
    staleTime: Infinity,
  });

  const version = useQuery({
    queryKey: ["release-version", applicationIdSafe, release?.versionId ?? ""],
    queryFn: () => getApplicationVersion(applicationIdSafe, release?.versionId as string),
    enabled: release !== null,
    retry: false,
  });

  const directory = useAllApplications(orgId);
  const tenants = useAllTenants(orgId);
  const roster = useAllOrgMembers(orgId);
  const tenantNameById = useMemo(
    () => new Map(tenants.tenants.map((tenant) => [tenant.id, tenant.name])),
    [tenants.tenants],
  );
  const memberNameById = useMemo(
    () => new Map(roster.members.map((member) => [member.userId, member.fullName])),
    [roster.members],
  );
  const applicationName =
    directory.applications.find((application) => application.id === applicationIdSafe)?.name ??
    applicationIdSafe;

  function invalidateRelease() {
    void queryClient.invalidateQueries({ queryKey: ["release", releaseIdSafe] });
    void queryClient.invalidateQueries({ queryKey: ["release-history", releaseIdSafe] });
    void queryClient.invalidateQueries({ queryKey: ["release-manifest", releaseIdSafe] });
    void queryClient.invalidateQueries({ queryKey: ["release-manifest-yaml", releaseIdSafe] });
    void queryClient.invalidateQueries({ queryKey: ["releases"] });
  }

  const transition = useMutation({
    mutationFn: (input: { to: ReleaseStatus; reason?: string }) =>
      transitionRelease(applicationIdSafe, releaseIdSafe, input.to, input.reason),
    onSuccess: (updated) => {
      invalidateRelease();
      toast.showSuccess(`${updated.releaseRef} is now ${RELEASE_STATUS_LABELS[updated.status]}`);
    },
    onError: toast.showError,
  });

  if (detail.isError) {
    return (
      <Stack spacing={2}>
        <Button component={Link} to={`/releases/${applicationIdSafe}`} startIcon={<ArrowBackIcon />}>
          Releases
        </Button>
        <Alert severity="error" data-testid="release-error">
          Failed to load the release. It may not exist, or you may not have access to it.
        </Alert>
      </Stack>
    );
  }

  if (release === null) {
    return (
      <Stack spacing={2}>
        <Typography aria-busy="true">Loading release...</Typography>
      </Stack>
    );
  }

  const actions = legalTransitions(release.status);
  const versionLabel = version.data?.versionName ?? release.versionId;

  return (
    <Stack spacing={2}>
      <Stack direction="row" spacing={2} sx={{ alignItems: "center" }}>
        <Button component={Link} to={`/releases/${applicationIdSafe}`} startIcon={<ArrowBackIcon />}>
          Releases
        </Button>
        <Typography variant="h5" sx={{ flexGrow: 1 }} data-testid="release-title">
          {release.releaseRef} — {applicationName} {versionLabel}
        </Typography>
        <Chip
          label={RELEASE_STATUS_LABELS[release.status]}
          color={RELEASE_STATUS_COLORS[release.status]}
          variant={release.status === "DRAFT" ? "outlined" : "filled"}
          data-testid="release-status-chip"
        />
        <Tooltip title="Compare this frozen manifest with another APPROVED release of the same application">
          <Button
            variant="outlined"
            startIcon={<CompareArrowsIcon />}
            onClick={() => setCompareOpen(true)}
            data-testid="compare-button"
          >
            Compare
          </Button>
        </Tooltip>
      </Stack>

      <Paper variant="outlined" sx={{ padding: 2 }} data-testid="lifecycle-section">
        <Stack direction="row" spacing={2} sx={{ alignItems: "center", flexWrap: "wrap" as const }}>
          <Typography variant="h6" sx={{ minWidth: 100 }}>
            Lifecycle
          </Typography>
          {actions.length > 0 ? (
            actions.map((to) => (
              <Button
                key={to}
                size="small"
                variant="outlined"
                color={to === "FAILED" || to === "ROLLED_BACK" ? "error" : "primary"}
                disabled={transition.isPending}
                onClick={() => {
                  if (transitionRequiresReason(to)) {
                    setReasonTarget(to);
                  } else {
                    transition.mutate({ to });
                  }
                }}
                data-testid={`transition-${to}`}
              >
                {RELEASE_STATUS_LABELS[to]}
              </Button>
            ))
          ) : (
            <Typography color="text.secondary">
              {release.status} is terminal — no further transitions (PRD 17).
            </Typography>
          )}
          {transition.isPending ? <LinearProgress sx={{ width: 80 }} /> : null}
        </Stack>
        <Typography color="text.secondary" sx={{ marginTop: 1 }}>
          Actions offer the state machine&apos;s legal edges only; the server rejects every other
          crossing. Failed and rolled-back releases must explain themselves.
        </Typography>
      </Paper>

      <Stack direction="row" spacing={2} sx={{ alignItems: "stretch" }}>
        <Paper variant="outlined" sx={{ padding: 2, flexGrow: 1 }} data-testid="artifacts-section">
          <Typography variant="h6">Artifacts</Typography>
          <Stack spacing={1} sx={{ marginTop: 1 }}>
            {release.artifacts.map((slot) => {
              const pin = manifest.data?.manifest.artifacts.find(
                (candidate) => candidate.platform === slot.platform,
              );
              return (
                <Stack key={slot.platform} spacing={0.5}>
                  <Stack direction="row" spacing={1} sx={{ alignItems: "center" }}>
                    <Chip size="small" variant="outlined" label={slot.platform} />
                    {pin ? (
                      <Chip size="small" color="success" variant="outlined" label="Frozen" />
                    ) : null}
                  </Stack>
                  <Typography variant="body2" sx={{ fontFamily: "monospace" }}>
                    {slot.artifactId}
                  </Typography>
                  {pin ? (
                    <Tooltip title={pin.sha256}>
                      <Typography variant="body2" color="text.secondary">
                        build {pin.buildNumber ?? "—"} · sha256 {shortSha(pin.sha256)} ·{" "}
                        {formatBytes(pin.sizeBytes)} · {pin.fileName}
                      </Typography>
                    </Tooltip>
                  ) : (
                    <Typography variant="body2" color="text.secondary">
                      Content pins (sha256, build, size) freeze at APPROVED.
                    </Typography>
                  )}
                </Stack>
              );
            })}
          </Stack>
        </Paper>

        <Paper variant="outlined" sx={{ padding: 2, flexGrow: 1 }} data-testid="gates-section">
          <Typography variant="h6">Quality gates</Typography>
          <Typography color="text.secondary" sx={{ marginTop: 1 }} data-testid="gates-empty">
            Gate results arrive with the quality-gate subsystem (E10, S10-03). This block renders
            them per gate — passed, failed, or blocking — when the data lands.
          </Typography>
        </Paper>

        <Paper variant="outlined" sx={{ padding: 2, flexGrow: 1 }} data-testid="approvals-section">
          <Typography variant="h6">Approvals</Typography>
          <Typography color="text.secondary" sx={{ marginTop: 1 }} data-testid="approvals-empty">
            Approval policies and per-role approval state arrive with E10 (S10-01).{" "}
            {manifest.data
              ? manifest.data.manifest.approval.required.length > 0
                ? `Frozen document requires: ${manifest.data.manifest.approval.required.join(", ")}.`
                : "The frozen document records no required approvers."
              : "The frozen manifest will record the required-approver set once frozen."}
          </Typography>
        </Paper>
      </Stack>

      <Paper variant="outlined" sx={{ padding: 2 }} data-testid="distribution-section">
        <Typography variant="h6">Distribution</Typography>
        <Stack direction="row" spacing={1} sx={{ alignItems: "center", marginTop: 1, flexWrap: "wrap" }}>
          <Typography color="text.secondary">Starting ring:</Typography>
          <Chip size="small" color="primary" variant="filled" label={release.ringName} />
          {release.environment ? (
            <>
              <Typography color="text.secondary">Environment:</Typography>
              <Chip size="small" variant="outlined" label={release.environmentName ?? release.environment} />
            </>
          ) : null}
        </Stack>
        <Typography color="text.secondary" sx={{ marginTop: 1 }}>
          Ring promotion tracking (PRD 32&apos;s per-ring states) lands with E10&apos;s ring
          behavior; M2 pins the starting ring and the target set.
        </Typography>
        <Stack spacing={0.5} sx={{ marginTop: 1 }}>
          {release.targets.map((target) => (
            <Typography variant="body2" key={target.tenantId}>
              {tenantNameById.get(target.tenantId) ?? target.tenantId} · ring {target.ring} ·{" "}
              {target.testerGroupIds.length === 0
                ? "tenant at large"
                : `${target.testerGroupIds.length} tester group${target.testerGroupIds.length === 1 ? "" : "s"}`}
              {target.testerGroupIds.length > 0 ? (
                <Tooltip title={target.testerGroupIds.join("\n")}>
                  <Typography component="span" variant="body2" color="text.secondary" sx={{ marginLeft: 1 }}>
                    (ids)
                  </Typography>
                </Tooltip>
              ) : null}
            </Typography>
          ))}
        </Stack>
      </Paper>

      <Paper variant="outlined" sx={{ padding: 2 }} data-testid="timeline-section">
        <Typography variant="h6">State timeline</Typography>
        {history.isError ? (
          <Alert severity="error">Failed to load the transition history.</Alert>
        ) : history.isPending ? (
          <Typography aria-busy="true">Loading history...</Typography>
        ) : (
          <Stepper orientation="vertical" sx={{ marginTop: 1 }} data-testid="release-timeline">
            {history.data?.transitions.map((entry, index) => (
              <Step key={index} expanded active>
                <StepLabel>
                  <Stack spacing={0.5}>
                    <Typography variant="body2">
                      {entry.from === null ? (
                        <>Created as {RELEASE_STATUS_LABELS[entry.to]}</>
                      ) : (
                        <>
                          {RELEASE_STATUS_LABELS[entry.from]} → {RELEASE_STATUS_LABELS[entry.to]}
                        </>
                      )}
                    </Typography>
                    <Typography variant="body2" color="text.secondary">
                      {memberNameById.get(entry.actorId) ?? entry.actorId} ·{" "}
                      {new Date(entry.occurredAt).toLocaleString()}
                    </Typography>
                    {entry.reason ? (
                      <Typography variant="body2" color="text.secondary" sx={{ fontStyle: "italic" }}>
                        &ldquo;{entry.reason}&rdquo;
                      </Typography>
                    ) : null}
                  </Stack>
                </StepLabel>
              </Step>
            ))}
          </Stepper>
        )}
      </Paper>

      <Paper variant="outlined" sx={{ padding: 2 }} data-testid="manifest-section">
        <Stack direction="row" spacing={2} sx={{ alignItems: "center" }}>
          <Typography variant="h6" sx={{ flexGrow: 1 }}>
            Frozen manifest
          </Typography>
          {manifest.data ? (
            <Button
              size="small"
              startIcon={<DownloadIcon />}
              disabled={yaml.data === undefined}
              onClick={() => {
                if (yaml.data === undefined) return;
                const blob = new Blob([yaml.data], { type: "application/yaml" });
                const url = URL.createObjectURL(blob);
                const anchor = document.createElement("a");
                anchor.href = url;
                anchor.download = `${release.releaseRef}-manifest.yaml`;
                anchor.click();
                URL.revokeObjectURL(url);
              }}
              data-testid="manifest-download"
            >
              Download YAML
            </Button>
          ) : null}
        </Stack>
        {manifest.isError && !manifestMissing ? (
          <Alert severity="error">Failed to load the manifest.</Alert>
        ) : manifestMissing ? (
          <Typography color="text.secondary" sx={{ marginTop: 1 }} data-testid="manifest-not-frozen">
            Not frozen yet — the manifest is created when the release crosses into APPROVED
            (S9-03), and never changes afterwards.
          </Typography>
        ) : manifest.data ? (
          <Stack spacing={1} sx={{ marginTop: 1 }}>
            <Stack direction="row" spacing={2} sx={{ flexWrap: "wrap" }}>
              <Tooltip title={manifest.data.sha256}>
                <Typography variant="body2" sx={{ fontFamily: "monospace" }} data-testid="manifest-sha">
                  sha256 {shortSha(manifest.data.sha256)}
                </Typography>
              </Tooltip>
              <Typography variant="body2" color="text.secondary">
                frozen {new Date(manifest.data.frozenAt).toLocaleString()} by{" "}
                {memberNameById.get(manifest.data.frozenBy) ?? manifest.data.frozenBy}
              </Typography>
            </Stack>
            <Divider />
            <ManifestDocumentView manifest={manifest.data.manifest} tenantNameById={tenantNameById} />
            <Divider />
            <Typography variant="subtitle2">YAML (compliance render)</Typography>
            <Box
              component="pre"
              sx={{
                margin: 0,
                padding: 1,
                backgroundColor: "action.hover",
                borderRadius: 1,
                overflowX: "auto",
                fontFamily: "monospace",
                fontSize: 12,
                maxHeight: 320,
              }}
              data-testid="manifest-yaml"
            >
              {yaml.isPending ? "Loading YAML..." : (yaml.data ?? "")}
            </Box>
          </Stack>
        ) : (
          <Typography aria-busy="true" sx={{ marginTop: 1 }}>
            Loading manifest...
          </Typography>
        )}
      </Paper>

      <ReasonDialog
        open={reasonTarget !== null}
        to={reasonTarget}
        onClose={() => setReasonTarget(null)}
        onSubmit={async (reason) => {
          if (reasonTarget === null) return;
          await transition.mutateAsync({ to: reasonTarget, reason });
        }}
      />

      <CompareDialog
        open={compareOpen}
        applicationId={applicationIdSafe}
        current={release}
        tenantNameById={tenantNameById}
        onClose={() => setCompareOpen(false)}
      />
    </Stack>
  );
}

function ManifestDocumentView({
  manifest,
  tenantNameById,
}: {
  manifest: ReleaseManifest["manifest"];
  tenantNameById: Map<string, string>;
}) {
  const document = manifest;
  return (
    <Stack spacing={1} data-testid="manifest-document">
      <Stack direction="row" spacing={1} sx={{ flexWrap: "wrap" }}>
        <Chip size="small" variant="outlined" label={document.release.applicationName} />
        <Chip size="small" variant="outlined" label={document.release.versionName} />
        <Chip size="small" variant="outlined" label={`ring ${document.ring.code} (${document.ring.name})`} />
        {document.environment ? (
          <Chip size="small" variant="outlined" label={`environment ${document.environment}`} />
        ) : null}
        {document.rollout?.percentage != null ? (
          <Chip size="small" variant="outlined" label={`rollout ${document.rollout.percentage}%`} />
        ) : null}
      </Stack>
      {document.artifacts.map((pin) => (
        <Typography variant="body2" key={pin.platform}>
          {pin.platform}: build {pin.buildNumber ?? "—"} · {formatBytes(pin.sizeBytes)} ·{" "}
          {pin.fileName} ·{" "}
          <Tooltip title={pin.sha256}>
            <span style={{ fontFamily: "monospace" }}>{shortSha(pin.sha256)}</span>
          </Tooltip>
        </Typography>
      ))}
      <Typography variant="body2" color="text.secondary">
        Targets:{" "}
        {document.targets
          .map(
            (target) =>
              `${tenantNameById.get(target.tenantId) ?? target.tenantId}${
                target.testerGroupIds.length > 0
                  ? ` (${target.testerGroupIds.length} groups)`
                  : ""
              }`,
          )
          .join(", ")}
      </Typography>
      <Typography variant="body2" color="text.secondary">
        Configuration:{" "}
        {document.configuration
          ? `version ${document.configuration.configVersion ?? 0} · ${Object.keys(document.configuration.featureFlags ?? {}).length} config keys`
          : "none (no environment named)"}
      </Typography>
      <Typography variant="body2" color="text.secondary">
        Required approvers:{" "}
        {document.approval.required.length > 0 ? document.approval.required.join(", ") : "none recorded"}
      </Typography>
    </Stack>
  );
}

interface ReasonDialogProps {
  open: boolean;
  to: ReleaseStatus | null;
  onClose: () => void;
  onSubmit: (reason: string) => Promise<void>;
}

/** The mandatory-reason dialog for the FAILED and ROLLED_BACK edges (a
 * release that died or was pulled explains itself; the reason rides the
 * audit event and the timeline row). */
function ReasonDialog({ open, to, onClose, onSubmit }: ReasonDialogProps) {
  const toast = useToast();
  const [reason, setReason] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const trimmed = reason.trim();
  const error = trimmed === "" ? "A reason is required for this transition" : undefined;

  async function handleSubmit() {
    if (to === null || trimmed === "") return;
    setSubmitting(true);
    try {
      await onSubmit(trimmed);
      onClose();
      setReason("");
    } catch (submitError) {
      toast.showError(submitError);
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>Transition to {to === null ? "" : RELEASE_STATUS_LABELS[to]}?</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ marginTop: 1 }}>
          <Typography>
            A release that failed or was rolled back explains itself: the reason is recorded on
            the audit event and the timeline, permanently.
          </Typography>
          <TextField
            label="Reason (required)"
            value={reason}
            onChange={(event) => setReason(event.target.value)}
            error={error !== undefined}
            helperText={error ?? "Recorded with the transition (FR-024)"}
            multiline
            minRows={2}
            slotProps={{ htmlInput: { "data-testid": "transition-reason" } }}
          />
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button
          color="error"
          variant="contained"
          onClick={() => void handleSubmit()}
          disabled={submitting || error !== undefined}
          data-testid="confirm-transition"
        >
          {submitting ? "Working..." : to === null ? "" : RELEASE_STATUS_LABELS[to]}
        </Button>
      </DialogActions>
    </Dialog>
  );
}

interface CompareDialogProps {
  open: boolean;
  applicationId: string;
  current: ReleaseDetail;
  tenantNameById: Map<string, string>;
  onClose: () => void;
}

/** The manifest diff (S9-03 AC5): what moved between two APPROVED
 * freezes of the same application. Artifact pins are shown by their
 * content facts — build, sha256, size, filename — because those are
 * what the backend's CHANGED equality uses; the rendered identity is
 * the #57 follow-up. */
function CompareDialog({ open, applicationId, current, tenantNameById, onClose }: CompareDialogProps) {
  const toast = useToast();
  const [otherId, setOtherId] = useState<string | null>(null);
  const [diff, setDiff] = useState<ManifestDiff | null>(null);
  const [loading, setLoading] = useState(false);

  const candidates = useQuery({
    queryKey: ["releases", "compare", applicationId],
    queryFn: () => listReleases({ applicationId, limit: 50 }),
    enabled: open && applicationId !== "",
    staleTime: 60_000,
  });
  const options = (candidates.data?.items ?? []).filter((item) => item.id !== current.id);
  const selected = options.find((option) => option.id === otherId) ?? null;

  async function compare() {
    if (selected === null) return;
    setLoading(true);
    setDiff(null);
    try {
      setDiff(await getManifestDiff(applicationId, current.id, selected.id));
    } catch (error) {
      toast.showError(error);
    } finally {
      setLoading(false);
    }
  }

  const artifactEntries = diff ? Object.entries(diff.artifacts) : [];
  const identical =
    diff !== null &&
    artifactEntries.length === 0 &&
    diff.targets.added.length === 0 &&
    diff.targets.removed.length === 0 &&
    Object.keys(diff.targets.groupsChanged).length === 0 &&
    diff.ring === null &&
    diff.environment === null &&
    diff.rollout === null &&
    diff.configurationChanges.length === 0;

  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="md">
      <DialogTitle>Compare frozen manifests</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ marginTop: 1 }}>
          <Typography color="text.secondary">
            A statement about two frozen moments, never about live state. {current.releaseRef} is
            the FROM side; both releases must have crossed into APPROVED.
          </Typography>
          <Stack direction="row" spacing={2} sx={{ alignItems: "center" }}>
            <Autocomplete
              options={options}
              getOptionLabel={(option) => `${option.releaseRef} (${RELEASE_STATUS_LABELS[option.status]})`}
              isOptionEqualToValue={(option, value) => option.id === value.id}
              value={selected}
              onChange={(_event, value) => setOtherId(value?.id ?? null)}
              sx={{ flexGrow: 1 }}
              renderInput={(params) => (
                <TextField {...params} label="Compare with" helperText="Other releases of this application" />
              )}
              data-testid="compare-picker"
            />
            <Button
              variant="contained"
              disabled={selected === null || loading}
              onClick={() => void compare()}
              data-testid="compare-run"
            >
              {loading ? "Comparing..." : "Compare"}
            </Button>
          </Stack>
          {diff ? (
            identical ? (
              <Alert severity="info">The two frozen manifests are identical.</Alert>
            ) : (
              <Stack spacing={1} data-testid="compare-result">
                <Typography variant="body2" color="text.secondary">
                  {diff.from.releaseRef} → {diff.to.releaseRef} (frozen{" "}
                  {new Date(diff.to.frozenAt).toLocaleString()})
                </Typography>
                {artifactEntries.map(([platform, change]) => (
                  <Stack key={platform} spacing={0.5}>
                    <Stack direction="row" spacing={1} sx={{ alignItems: "center" }}>
                      <Chip size="small" variant="outlined" label={platform} />
                      <Chip
                        size="small"
                        color={
                          change.change === "ADDED"
                            ? "success"
                            : change.change === "REMOVED"
                              ? "error"
                              : "warning"
                        }
                        label={change.change}
                      />
                    </Stack>
                    <PinLine label="from" pin={change.from} />
                    <PinLine label="to" pin={change.to} />
                  </Stack>
                ))}
                {diff.targets.added.length > 0 || diff.targets.removed.length > 0 ? (
                  <Typography variant="body2">
                    Targets:{" "}
                    {diff.targets.added
                      .map((tenantId) => `+ ${tenantNameById.get(tenantId) ?? tenantId}`)
                      .concat(
                        diff.targets.removed.map(
                          (tenantId) => `- ${tenantNameById.get(tenantId) ?? tenantId}`,
                        ),
                      )
                      .join(" · ")}
                  </Typography>
                ) : null}
                {Object.entries(diff.targets.groupsChanged).map(([tenantId, change]) => (
                  <Typography variant="body2" key={tenantId}>
                    {tenantNameById.get(tenantId) ?? tenantId} groups:{" "}
                    {change.added.length} added, {change.removed.length} removed
                  </Typography>
                ))}
                {diff.ring ? (
                  <Typography variant="body2">Ring: {diff.ring.from} → {diff.ring.to}</Typography>
                ) : null}
                {diff.environment ? (
                  <Typography variant="body2">
                    Environment: {diff.environment.from} → {diff.environment.to}
                  </Typography>
                ) : null}
                {diff.rollout ? (
                  <Typography variant="body2">
                    Rollout: {diff.rollout.from} → {diff.rollout.to}
                  </Typography>
                ) : null}
                {diff.configurationChanges.length > 0 ? (
                  <Typography variant="body2">
                    Configuration keys moved: {diff.configurationChanges.join(", ")}
                  </Typography>
                ) : null}
              </Stack>
            )
          ) : null}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Close</Button>
      </DialogActions>
    </Dialog>
  );
}

function PinLine({ label, pin }: { label: string; pin: ManifestArtifactPin | null }) {
  if (pin === null) {
    return (
      <Typography variant="body2" color="text.secondary">
        {label}: —
      </Typography>
    );
  }
  return (
    <Typography variant="body2" color="text.secondary">
      {label}: build {pin.buildNumber ?? "—"} · {formatBytes(pin.sizeBytes)} · {pin.fileName} ·{" "}
      <Tooltip title={pin.sha256}>
        <span style={{ fontFamily: "monospace" }}>{shortSha(pin.sha256)}</span>
      </Tooltip>
    </Typography>
  );
}
