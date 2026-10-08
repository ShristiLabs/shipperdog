/**
 * Release dashboard (issue #59, S9-05) — PRD 32's "heart of the
 * product": one screen per release showing build, quality, approvals,
 * and distribution state so a Release Manager judges release health at
 * a glance. Blocks: the artifact slots with their frozen pins (hashes
 * at APPROVED, the S9-03 manifest), quality gates — LIVE since S10-03:
 * the ring's expected set with per-gate states (passed, failed,
 * no-data fail-closed, not-reported), observed values against the
 * definitions' thresholds, evidence links, the override record with
 * its reason, and the governed override action — approvals (S10-02:
 * the bound policy's rounds with their group modes, the waiting-on
 * list, recorded signatures and skip rationales, decisions, and the
 * rework restart), distribution (the starting ring and target set;
 * ring promotion tracking is E10), the append-only state timeline
 * (S9-02 history with actors/timestamps/reasons), the frozen manifest
 * viewer with the YAML compliance render and the diff against another
 * APPROVED release of the same application. Lifecycle actions offer
 * PRD 17's legal edges; no read-shaped surface is keyed to
 * app:releases:manage, so actions render for every legal edge and the
 * server's verdict is the gate (denials surface the problem+json code,
 * the ApplicationDetailPage precedent). Detail, timeline, gates,
 * approvals, and manifest auto-refresh (30s) — the dashboard is a
 * live view.
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
import {
  getReleaseGates,
  overrideGate,
  type ReleaseGate,
  type ReleaseGates,
} from "../api/gates";
import { getApplicationVersion } from "../api/versions";
import {
  decideReleaseApproval,
  getReleaseApprovals,
  restartApprovalRound,
  type ApprovalState,
  type ApprovalStep,
} from "../api/workflows";
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
import { evaluateRound, roleLabel } from "./approvalRules";
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

  // The approval state exists exactly when the release bound a policy
  // at its crossing into APPROVAL_PENDING; before that the surface's
  // honest 404 is the empty state, not an error (the manifest's own
  // pattern). Every other failure is a real error.
  const approvals = useQuery({
    queryKey: ["release-approvals", releaseIdSafe],
    queryFn: () => getReleaseApprovals(applicationIdSafe, releaseIdSafe),
    enabled: applicationIdSafe !== "" && releaseIdSafe !== "" && release !== null,
    retry: false,
    refetchInterval: REFRESH_MS,
  });
  const approvalsMissing =
    approvals.isError && approvals.error instanceof ApiError && approvals.error.status === 404;

  // The gate block resolves for every readable release (the view
  // carries the ring's expected set beside the recorded results); only
  // a genuine failure is an error — there is no miss state.
  const gates = useQuery({
    queryKey: ["release-gates", releaseIdSafe],
    queryFn: () => getReleaseGates(applicationIdSafe, releaseIdSafe),
    enabled: applicationIdSafe !== "" && releaseIdSafe !== "" && release !== null,
    retry: false,
    refetchInterval: REFRESH_MS,
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
    void queryClient.invalidateQueries({ queryKey: ["release-approvals", releaseIdSafe] });
    void queryClient.invalidateQueries({ queryKey: ["release-gates", releaseIdSafe] });
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

  const decide = useMutation({
    mutationFn: (input: { decision: "APPROVE" | "REJECT"; step: number; reason?: string }) =>
      decideReleaseApproval(applicationIdSafe, releaseIdSafe, input),
    onSuccess: () => {
      invalidateRelease();
      toast.showSuccess("Approval decision recorded");
    },
    onError: toast.showError,
  });

  const restart = useMutation({
    mutationFn: (reason?: string) => restartApprovalRound(applicationIdSafe, releaseIdSafe, reason),
    onSuccess: () => {
      invalidateRelease();
      toast.showSuccess("Approval round restarted");
    },
    onError: toast.showError,
  });

  const override = useMutation({
    mutationFn: (input: { gateType: string; reason: string }) =>
      overrideGate(applicationIdSafe, releaseIdSafe, input.gateType, input.reason),
    onSuccess: (gate) => {
      invalidateRelease();
      toast.showSuccess(`Gate ${gate.gateType} overridden`);
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

        <GatesSection
          gates={gates.data ?? null}
          loading={gates.isPending}
          failed={gates.isError}
          memberNameById={memberNameById}
          overriding={override.isPending}
          onOverride={(gateType, reason) => override.mutate({ gateType, reason })}
        />

        <ApprovalsSection
          state={approvals.data ?? null}
          missing={approvalsMissing}
          failed={approvals.isError && !approvalsMissing}
          releaseStatus={release.status}
          memberNameById={memberNameById}
          deciding={decide.isPending}
          onDecide={(input) => decide.mutate(input)}
          restarting={restart.isPending}
          onRestart={(reason) => restart.mutate(reason)}
          frozenRequired={manifest.data?.manifest.approval.required ?? null}
        />
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

interface GatesSectionProps {
  gates: ReleaseGates | null;
  loading: boolean;
  failed: boolean;
  memberNameById: Map<string, string>;
  overriding: boolean;
  onOverride: (gateType: string, reason: string) => void;
}

/** The Quality Gates block of PRD 32, live since S10-03: the ring's
 * expected set with per-gate states — passed with its evidence link,
 * failed (BLOCKED while required and not overridden), the metric
 * gates' NO_DATA (fail-closed "no data yet", visually distinct from a
 * failure), and the not-reported PENDING state — numeric gates
 * rendered as "observed (threshold T)", the override record with its
 * actor and mandatory reason, and the governed override action (the
 * server's app:releases:gate-override verdict is the authority; a
 * refusal surfaces its problem+json code). */
function GatesSection({ gates, loading, failed, memberNameById, overriding, onOverride }: GatesSectionProps) {
  const [overrideTarget, setOverrideTarget] = useState<string | null>(null);

  return (
    <Paper variant="outlined" sx={{ padding: 2, flexGrow: 1 }} data-testid="gates-section">
      <Typography variant="h6">Quality gates</Typography>
      {failed ? (
        <Alert severity="error" sx={{ marginTop: 1 }} data-testid="gates-error">
          Failed to load the gate state.
        </Alert>
      ) : loading || gates === null ? (
        <Typography color="text.secondary" sx={{ marginTop: 1 }} data-testid="gates-loading" aria-busy="true">
          Loading gates...
        </Typography>
      ) : gates.gates.length === 0 ? (
        <Typography color="text.secondary" sx={{ marginTop: 1 }} data-testid="gates-empty">
          No gates required for this ring and none reported — the organization defines required
          gates per ring (org-wide defaults or ring-specific overrides).
        </Typography>
      ) : (
        <Stack spacing={1} sx={{ marginTop: 1 }}>
          <Stack spacing={0.5}>
            {gates.gates.map((gate) => (
              <GateRow
                key={gate.gateType}
                gate={gate}
                memberNameById={memberNameById}
                overriding={overriding}
                onOverride={setOverrideTarget}
              />
            ))}
          </Stack>
          <Typography variant="body2" color="text.secondary">
            A failed or data-less required gate blocks the READY crossing until passed or
            overridden; overrides are audited with actor and reason.
          </Typography>
        </Stack>
      )}
      <OverrideDialog
        open={overrideTarget !== null}
        gateType={overrideTarget}
        onClose={() => setOverrideTarget(null)}
        overriding={overriding}
        onSubmit={(reason) => {
          if (overrideTarget === null) return;
          onOverride(overrideTarget, reason);
        }}
      />
    </Paper>
  );
}

/** One gate of the block: the type, the state chip (BLOCKED for a
 * required failed gate), the numeric render, the evidence link, the
 * metric source note, the override record, and the override action. */
function GateRow({
  gate,
  memberNameById,
  overriding,
  onOverride,
}: {
  gate: ReleaseGate;
  memberNameById: Map<string, string>;
  overriding: boolean;
  onOverride: (gateType: string) => void;
}) {
  const blocking = gate.required && !gate.overridden && gate.status !== "PASSED";
  const thresholdLabel =
    gate.threshold === null
      ? null
      : gate.schema === "COUNT_MAX"
        ? `max ${gate.threshold}`
        : `threshold ${gate.threshold}`;
  return (
    <Stack spacing={0.25} data-testid={`gate-${gate.gateType}`}>
      <Stack direction="row" spacing={1} sx={{ alignItems: "center", flexWrap: "wrap" as const }}>
        <Typography variant="body2" sx={{ minWidth: 150 }}>
          {gate.gateType}
          {gate.required ? "" : " (advisory)"}
        </Typography>
        {gate.status === "PASSED" ? (
          <Chip size="small" color="success" variant="filled" label="passed" />
        ) : gate.status === "FAILED" ? (
          <Chip size="small" color="error" variant="filled" label="failed" />
        ) : gate.status === "NO_DATA" ? (
          <Chip size="small" color="warning" variant="outlined" label="no data yet (fail-closed)" />
        ) : (
          <Chip size="small" color="default" variant="outlined" label="not reported yet" />
        )}
        {blocking ? <Chip size="small" color="error" variant="outlined" label="BLOCKED" /> : null}
        {gate.overridden ? (
          <Chip size="small" color="warning" variant="filled" label="overridden" />
        ) : null}
        {gate.observedValue !== null && thresholdLabel !== null ? (
          <Typography variant="body2" color="text.secondary">
            {gate.observedValue} ({thresholdLabel})
          </Typography>
        ) : thresholdLabel !== null ? (
          <Typography variant="body2" color="text.secondary">
            {thresholdLabel}
          </Typography>
        ) : null}
        {gate.source === "METRIC" ? (
          <Tooltip title="Resolved from analytics at evaluation time (S11-04's feeds)">
            <Typography variant="body2" color="text.secondary" sx={{ fontStyle: "italic" }}>
              from analytics
            </Typography>
          </Tooltip>
        ) : null}
        {gate.evidenceUrl !== null ? (
          <a href={gate.evidenceUrl} target="_blank" rel="noreferrer" data-testid={`gate-evidence-${gate.gateType}`}>
            evidence
          </a>
        ) : null}
        {gate.status !== "PASSED" && gate.status !== null && !gate.overridden && gate.status !== "PENDING" ? (
          <Button
            size="small"
            variant="outlined"
            color="warning"
            disabled={overriding}
            onClick={() => onOverride(gate.gateType)}
            data-testid={`override-${gate.gateType}`}
          >
            Override
          </Button>
        ) : null}
      </Stack>
      {gate.overridden && gate.overriddenBy !== null ? (
        <Typography variant="body2" color="text.secondary" sx={{ fontStyle: "italic" }}>
          overridden by {memberNameById.get(gate.overriddenBy) ?? gate.overriddenBy} — &ldquo;
          {gate.overriddenReason}&rdquo;
          {gate.overriddenAt !== null ? ` (${new Date(gate.overriddenAt).toLocaleString()})` : ""}
        </Typography>
      ) : null}
      {gate.history.length > 1 ? (
        <Typography variant="body2" color="text.secondary">
          {gate.history.length} recorded results (latest wins)
        </Typography>
      ) : null}
    </Stack>
  );
}

interface OverrideDialogProps {
  open: boolean;
  gateType: string | null;
  onClose: () => void;
  overriding: boolean;
  onSubmit: (reason: string) => void;
}

/** The mandatory-reason dialog of the gate override (AC3): the
 * governed exception explains itself — actor, reason, and moment ride
 * the row and the audit event. */
function OverrideDialog({ open, gateType, onClose, overriding, onSubmit }: OverrideDialogProps) {
  const [reason, setReason] = useState("");
  const canSubmit = reason.trim().length > 0 && !overriding;
  return (
    <Dialog open={open && gateType !== null} onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle>Override gate {gateType}</DialogTitle>
      <DialogContent>
        <TextField
          autoFocus
          fullWidth
          multiline
          minRows={2}
          label="Reason (mandatory, audited)"
          value={reason}
          onChange={(event) => setReason(event.target.value)}
          sx={{ marginTop: 1 }}
          slotProps={{ htmlInput: { "data-testid": "override-reason" } }}
        />
        <Typography variant="body2" color="text.secondary" sx={{ marginTop: 1 }}>
          The gate keeps its recorded state; the override alone stops it blocking, and the record
          (actor, reason, moment) stays on the release.
        </Typography>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button
          variant="contained"
          color="warning"
          disabled={!canSubmit}
          onClick={() => {
            onSubmit(reason.trim());
            setReason("");
            onClose();
          }}
          data-testid="override-submit"
        >
          {overriding ? "Overriding..." : "Override"}
        </Button>
      </DialogActions>
    </Dialog>
  );
}

interface ApprovalsSectionProps {
  state: ApprovalState | null;
  missing: boolean;
  failed: boolean;
  releaseStatus: string;
  memberNameById: Map<string, string>;
  deciding: boolean;
  onDecide: (input: { decision: "APPROVE" | "REJECT"; step: number; reason?: string }) => void;
  restarting: boolean;
  onRestart: (reason?: string) => void;
  frozenRequired: string[] | null;
}

const STEP_STATUS_COLORS: Record<ApprovalStep["status"], "success" | "error" | "default" | "warning"> = {
  APPROVED: "success",
  REJECTED: "error",
  PENDING: "default",
  SKIPPED: "warning",
};

/** The approvals block (PRD 32; S10-02 AC5): the live round's topology
 * — groups with their modes, each step's demand, signature, or
 * recorded skip — with the waiting-on list the evaluator derives (the
 * active group's pending steps; approvalRules mirrors the backend
 * evaluator), the decision affordances on exactly those steps, the
 * rework restart on a rejected round, and the earlier rounds with
 * their rejection reasons. Group modes render verbatim: an ALL group
 * waits on every pending sibling; an ANY group is satisfied by the
 * first signature and its siblings carry the skip rationale naming
 * which signature satisfied the group. */
function ApprovalsSection({
  state,
  missing,
  failed,
  releaseStatus,
  memberNameById,
  deciding,
  onDecide,
  restarting,
  onRestart,
  frozenRequired,
}: ApprovalsSectionProps) {
  const [rejectTarget, setRejectTarget] = useState<number | null>(null);
  const current =
    state === null ? null : state.rounds.find((round) => round.roundNumber === state.currentRoundNumber) ?? null;
  const evaluation = current === null ? null : evaluateRound(current.steps);
  const waitingNames = evaluation === null ? [] : evaluation.waitingOn.map((step) => roleLabel(step.approvalType));
  const earlierRounds = state === null ? [] : state.rounds.filter((round) => round.roundNumber !== state.currentRoundNumber);

  return (
    <Paper variant="outlined" sx={{ padding: 2, flexGrow: 1 }} data-testid="approvals-section">
      <Typography variant="h6">Approvals</Typography>
      {failed ? (
        <Alert severity="error" sx={{ marginTop: 1 }} data-testid="approvals-error">
          Failed to load the approval state.
        </Alert>
      ) : missing ? (
        <Typography color="text.secondary" sx={{ marginTop: 1 }} data-testid="approvals-empty">
          No approval policy bound this release — it crossed without sign-offs.
          {frozenRequired !== null && frozenRequired.length > 0
            ? ` The frozen document records the required-approver set: ${frozenRequired.join(", ")}.`
            : ""}
        </Typography>
      ) : state === null ? (
        <Typography color="text.secondary" sx={{ marginTop: 1 }} data-testid="approvals-loading" aria-busy="true">
          Loading approvals...
        </Typography>
      ) : current === null ? (
        <Typography color="text.secondary" sx={{ marginTop: 1 }} data-testid="approvals-empty">
          No approval rounds recorded for this release.
        </Typography>
      ) : (
        <Stack spacing={1} sx={{ marginTop: 1 }}>
          <Stack direction="row" spacing={1} sx={{ alignItems: "center", flexWrap: "wrap" as const }}>
            <Chip
              size="small"
              color={current.status === "SATISFIED" ? "success" : current.status === "REJECTED" ? "error" : "default"}
              variant="outlined"
              label={`round ${current.roundNumber} · ${current.status.toLowerCase()} · ${current.workflowName}`}
            />
            {evaluation !== null && evaluation.outcome === "PENDING" ? (
              <Typography variant="body2" data-testid="approvals-waiting-on">
                Waiting on: {waitingNames.join(", ")}
              </Typography>
            ) : null}
          </Stack>
          <Stack spacing={0.5}>
            {current.steps.map((step) => {
              const waiting =
                evaluation !== null && evaluation.waitingOn.some((pending) => pending.position === step.position);
              return (
                <Stack key={step.position} spacing={0.25} data-testid={`approval-step-${step.position}`}>
                  <Stack direction="row" spacing={1} sx={{ alignItems: "center", flexWrap: "wrap" as const }}>
                    <Typography variant="body2" sx={{ minWidth: 90 }}>
                      {roleLabel(step.approvalType)}
                      {step.groupMode === "ANY" ? " (any-of)" : ""}
                    </Typography>
                    <Chip
                      size="small"
                      color={STEP_STATUS_COLORS[step.status]}
                      variant={step.status === "PENDING" ? "outlined" : "filled"}
                      label={waiting ? "WAITING" : step.status.toLowerCase()}
                    />
                    {step.status === "APPROVED" && step.decidedAt !== null ? (
                      <Typography variant="body2" color="text.secondary">
                        {memberNameById.get(step.approverId ?? "") ?? step.approverId} ·{" "}
                        {new Date(step.decidedAt).toLocaleString()}
                      </Typography>
                    ) : null}
                    {step.status === "SKIPPED" && step.reason !== null ? (
                      <Tooltip title={step.reason}>
                        <Typography variant="body2" color="text.secondary" sx={{ fontStyle: "italic" }}>
                          skipped — {step.reason}
                        </Typography>
                      </Tooltip>
                    ) : null}
                    {step.status === "REJECTED" && step.reason !== null ? (
                      <Typography variant="body2" color="text.secondary" sx={{ fontStyle: "italic" }}>
                        &ldquo;{step.reason}&rdquo;
                      </Typography>
                    ) : null}
                    {waiting ? (
                      <>
                        <Button
                          size="small"
                          variant="outlined"
                          color="success"
                          disabled={deciding}
                          onClick={() => onDecide({ decision: "APPROVE", step: step.position })}
                          data-testid={`approve-step-${step.position}`}
                        >
                          Approve
                        </Button>
                        <Button
                          size="small"
                          variant="outlined"
                          color="error"
                          disabled={deciding}
                          onClick={() => setRejectTarget(step.position)}
                          data-testid={`reject-step-${step.position}`}
                        >
                          Reject
                        </Button>
                      </>
                    ) : null}
                  </Stack>
                </Stack>
              );
            })}
          </Stack>
          {current.status === "REJECTED" && releaseStatus === "APPROVAL_PENDING" ? (
            <Button
              size="small"
              variant="outlined"
              disabled={restarting}
              onClick={() => onRestart()}
              data-testid="approvals-restart"
            >
              {restarting ? "Restarting..." : "Restart approval round (rework)"}
            </Button>
          ) : null}
          {earlierRounds.length > 0 ? (
            <Stack spacing={0.25}>
              <Typography variant="body2" color="text.secondary">
                Earlier rounds:{" "}
                {earlierRounds
                  .map((round) => `round ${round.roundNumber} ${round.status.toLowerCase()}`)
                  .join(" · ")}
              </Typography>
              {earlierRounds
                .filter((round) => round.status === "REJECTED")
                .flatMap((round) =>
                  round.steps
                    .filter((step) => step.status === "REJECTED" && step.reason !== null)
                    .map((step) => (
                      <Typography key={`${round.roundNumber}-${step.position}`} variant="body2" color="text.secondary">
                        Round {round.roundNumber}: {roleLabel(step.approvalType)} rejected — &ldquo;{step.reason}&rdquo;
                      </Typography>
                    )),
                )}
            </Stack>
          ) : null}
          <RejectDialog
            open={rejectTarget !== null}
            onClose={() => setRejectTarget(null)}
            onSubmit={async (reason) => {
              if (rejectTarget === null) return;
              onDecide({ decision: "REJECT", step: rejectTarget, reason });
            }}
          />
        </Stack>
      )}
    </Paper>
  );
}

interface RejectDialogProps {
  open: boolean;
  onClose: () => void;
  onSubmit: (reason: string) => Promise<void>;
}

/** The mandatory-reason dialog for a rejection (the reason rides the
 * rejection row and the audit event; a rejection is never anonymous). */
function RejectDialog({ open, onClose, onSubmit }: RejectDialogProps) {
  const toast = useToast();
  const [reason, setReason] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const trimmed = reason.trim();
  const error = trimmed === "" ? "A rejection explains itself — the reason is required" : undefined;

  async function handleSubmit() {
    if (trimmed === "") return;
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
      <DialogTitle>Reject this approval step?</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ marginTop: 1 }}>
          <Typography>
            A rejection terminates the round and blocks the release&apos;s progression — the release
            stays in approval pending until the rework restart opens a new round. The reason is
            recorded permanently.
          </Typography>
          <TextField
            label="Reason (required)"
            value={reason}
            onChange={(event) => setReason(event.target.value)}
            error={error !== undefined}
            helperText={error ?? "Recorded with the rejection"}
            multiline
            minRows={2}
            slotProps={{ htmlInput: { "data-testid": "reject-reason" } }}
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
          data-testid="confirm-reject"
        >
          {submitting ? "Working..." : "Reject"}
        </Button>
      </DialogActions>
    </Dialog>
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
