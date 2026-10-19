/**
 * The OTA dashboard (S16-15, issue #177; ADR-0010 workstreams 10/11):
 * the tenant-grain live view of over-the-air distribution — the alert
 * badges and firings feed (S16-11), and per bundle the device funnel
 * (checks -> seen -> downloads -> applied with the failure exits),
 * the apply success rate, the failure-reason breakdown, the adoption
 * curve over time, and the crash-rate comparison against the current
 * RELEASED bundle (D9's funnel split: the funnel and health read as
 * composite dashboard documents). Reached from the tenants listing's
 * OTA action like the tenant dashboard; every read is
 * tenant-addressed (tenant:dashboard:read) and auto-refreshed.
 */
import {
  Alert,
  Autocomplete,
  Box,
  Chip,
  CircularProgress,
  LinearProgress,
  Paper,
  Stack,
  TextField,
  Tooltip,
  Typography,
} from "@mui/material";
import { BarChart, LineChart } from "@mui/x-charts";
import { useQuery } from "@tanstack/react-query";
import { useMemo, useState } from "react";
import { useParams } from "react-router-dom";
import { listTenantApplications, type Application } from "../api/applications";
import {
  getOtaAlerts,
  getOtaBundleHealth,
  getOtaFunnel,
  listOtaBundles,
  type OtaAdoptionPoint,
  type OtaAlertFeedView,
  type OtaAlertFiring,
  type OtaBundle,
  type OtaBundleHealthView,
  type OtaFunnelStages,
} from "../api/ota";
import { useAuth } from "../auth/AuthContext";
import { useAllTenants } from "../hooks/useOrganizationDirectory";
import {
  OTA_SEVERITY_COLORS,
  alertRuleExpression,
  applySuccessRate,
  formatDeltaPoints,
  formatPercent,
  funnelRows,
} from "../otaRules";

const REFRESH_MS = 30_000;

/** The adoption curve's rows: one point per day, the 0..1 fraction as
 * a 0..100 percentage for the chart axis. */
export function adoptionChartRows(points: readonly OtaAdoptionPoint[]): { day: string; rate: number }[] {
  return points.map((point) => ({ day: point.day.slice(0, 10), rate: point.adoptionRate * 100 }));
}

export default function OtaDashboardPage() {
  const { tenantId: routeTenantId } = useParams<{ tenantId: string }>();
  const { user } = useAuth();
  const orgId = user?.orgId ?? null;
  const tenants = useAllTenants(orgId);
  const [pickedTenantId, setPickedTenantId] = useState<string | null>(null);
  const tenantId = pickedTenantId ?? routeTenantId ?? tenants.tenants[0]?.id ?? null;
  const tenant = tenants.tenants.find((candidate) => candidate.id === tenantId) ?? null;

  // The bundle picker walks the tenant's effective visibility (owned
  // plus authorized-shared applications) — the dashboard reads are
  // tenant-addressed, so the picker must stay inside one tenant.
  const applications = useQuery({
    queryKey: ["tenant-applications", tenantId],
    queryFn: () => listTenantApplications(tenantId as string, { limit: 100 }),
    enabled: tenantId !== null,
    staleTime: 60_000,
  });
  const [pickedApplicationId, setPickedApplicationId] = useState<string | null>(null);
  const applicationId =
    pickedApplicationId ?? (applications.data?.items[0] as Application | undefined)?.id ?? null;
  const selectedApplication =
    applications.data?.items.find((application) => application.id === applicationId) ?? null;

  const bundles = useQuery({
    queryKey: ["ota-bundles", applicationId],
    queryFn: () => listOtaBundles(applicationId as string, { limit: 100 }),
    enabled: applicationId !== null,
    staleTime: 60_000,
    retry: false,
  });
  const [pickedBundleId, setPickedBundleId] = useState<string | null>(null);
  const bundleId = pickedBundleId ?? (bundles.data?.items[0] as OtaBundle | undefined)?.id ?? null;
  const selectedBundle = bundles.data?.items.find((bundle) => bundle.id === bundleId) ?? null;
  const [ring, setRing] = useState("");

  const alerts = useQuery({
    queryKey: ["ota-alerts", tenantId],
    queryFn: () => getOtaAlerts(tenantId as string),
    enabled: tenantId !== null,
    refetchInterval: REFRESH_MS,
    retry: false,
  });

  // The funnel and health exist for a bundle with traffic; a 404 (or
  // any miss) renders the block's honest empty state, never an error.
  const funnel = useQuery({
    queryKey: ["ota-funnel", tenantId, bundleId, ring || undefined],
    queryFn: () => getOtaFunnel(tenantId as string, bundleId as string, ring.trim() === "" ? undefined : ring.trim()),
    enabled: tenantId !== null && bundleId !== null,
    refetchInterval: REFRESH_MS,
    retry: false,
  });

  const health = useQuery({
    queryKey: ["ota-health", tenantId, bundleId],
    queryFn: () => getOtaBundleHealth(tenantId as string, bundleId as string),
    enabled: tenantId !== null && bundleId !== null,
    refetchInterval: REFRESH_MS,
    retry: false,
  });

  const adoption = useMemo(
    () => (funnel.data ? adoptionChartRows(funnel.data.adoption) : []),
    [funnel.data],
  );

  if (tenants.loading) {
    return (
      <Stack spacing={2} sx={{ alignItems: "center", padding: 4 }} data-testid="ota-dashboard-loading">
        <CircularProgress />
        <Typography color="text.secondary">Loading the OTA dashboard...</Typography>
      </Stack>
    );
  }

  if (tenantId === null) {
    return (
      <Stack spacing={2}>
        <Typography variant="h5">OTA dashboard</Typography>
        <Alert severity="info" data-testid="ota-dashboard-no-tenant">
          No tenants in the organization yet — the OTA dashboard reads one tenant at a time.
        </Alert>
      </Stack>
    );
  }

  return (
    <Stack spacing={3}>
      <Stack direction="row" spacing={2} sx={{ alignItems: "baseline" }}>
        <Typography variant="h5" data-testid="ota-dashboard-title">
          {tenant ? `${tenant.name} · ` : ""}OTA dashboard
        </Typography>
        <Typography color="text.secondary" variant="body2">
          Live · every 30 s
        </Typography>
      </Stack>

      <AlertFeedBlock
        failed={alerts.isError}
        pending={alerts.isPending}
        feed={alerts.data ?? null}
      />

      <Paper variant="outlined" sx={{ padding: 2 }} data-testid="ota-bundle-picker">
        <Stack direction="row" spacing={2} sx={{ alignItems: "center", flexWrap: "wrap" }}>
          <Autocomplete
            sx={{ minWidth: 240 }}
            options={tenants.tenants}
            getOptionLabel={(option) => option.name}
            isOptionEqualToValue={(option, value) => option.id === value.id}
            value={tenant}
            onChange={(_event, value) => {
              setPickedTenantId(value?.id ?? null);
              setPickedApplicationId(null);
              setPickedBundleId(null);
            }}
            renderInput={(params) => <TextField {...params} label="Tenant" required />}
            data-testid="ota-tenant-picker"
          />
          <Autocomplete
            sx={{ minWidth: 240 }}
            options={applications.data?.items ?? []}
            loading={applications.isPending}
            getOptionLabel={(option) => option.name}
            isOptionEqualToValue={(option, value) => option.id === value.id}
            value={selectedApplication}
            onChange={(_event, value) => {
              setPickedApplicationId(value?.id ?? null);
              setPickedBundleId(null);
            }}
            renderInput={(params) => <TextField {...params} label="Application" required />}
            data-testid="ota-dashboard-application-picker"
          />
          <Autocomplete
            sx={{ minWidth: 200 }}
            options={bundles.data?.items ?? []}
            loading={bundles.isPending}
            getOptionLabel={(option) => option.bundleVersion}
            isOptionEqualToValue={(option, value) => option.id === value.id}
            value={selectedBundle}
            onChange={(_event, value) => setPickedBundleId(value?.id ?? null)}
            renderInput={(params) => (
              <TextField {...params} label="Bundle" required helperText="SIGNED-and-later bundles" />
            )}
            data-testid="ota-bundle-picker-field"
          />
          <TextField
            sx={{ minWidth: 160 }}
            label="Ring filter (optional)"
            value={ring}
            onChange={(event) => setRing(event.target.value)}
            helperText="Narrows the funnel to one ring's devices"
            slotProps={{ htmlInput: { "data-testid": "ota-ring-filter" } }}
          />
        </Stack>
      </Paper>

      {bundleId === null ? (
        <Typography color="text.secondary" data-testid="ota-dashboard-no-bundle">
          No SIGNED bundles for this application yet — author one from the OTA bundles screen.
        </Typography>
      ) : (
        <>
          <FunnelBlock
            failed={funnel.isError}
            pending={funnel.isPending}
            releaseRef={funnel.data?.releaseRef ?? ""}
            stages={funnel.data?.stages ?? null}
            failures={funnel.data?.failures ?? []}
          />
          <Paper variant="outlined" sx={{ padding: 2 }} data-testid="ota-adoption-section">
            <Typography variant="h6">Adoption over time</Typography>
            {funnel.isError ? (
              <Alert severity="warning" sx={{ marginTop: 1 }}>
                No adoption curve for this bundle yet.
              </Alert>
            ) : adoption.length === 0 ? (
              <Typography color="text.secondary" sx={{ marginTop: 1 }} data-testid="ota-adoption-empty">
                No adoption recorded yet — the curve fills as devices check and apply.
              </Typography>
            ) : (
              <Box sx={{ marginTop: 1 }} data-testid="ota-adoption-chart">
                <LineChart
                  height={220}
                  xAxis={[{ data: adoption.map((row) => row.day), scaleType: "band" }]}
                  yAxis={[{ label: "adoption %", min: 0, max: 100 }]}
                  series={[
                    {
                      data: adoption.map((row) => row.rate),
                      label: "Adoption",
                      color: "#2563eb",
                      showMark: true,
                    },
                  ]}
                  margin={{ left: 60 }}
                  hideLegend
                />
              </Box>
            )}
          </Paper>
          <HealthBlock failed={health.isError} health={health.data ?? null} />
        </>
      )}
    </Stack>
  );
}

// ---------------------------------------------------------------------------
// The alert feed: badges + standing firings + recent history
// ---------------------------------------------------------------------------

function AlertFeedBlock({
  failed,
  pending,
  feed,
}: {
  failed: boolean;
  pending: boolean;
  feed: OtaAlertFeedView | null;
}) {
  return (
    <Paper variant="outlined" sx={{ padding: 2 }} data-testid="ota-alerts-section">
      <Stack direction="row" spacing={2} sx={{ alignItems: "center", flexWrap: "wrap" }}>
        <Typography variant="h6" sx={{ flexGrow: 1 }}>
          Alerts
        </Typography>
        {failed ? (
          <Alert severity="error">Failed to load the alert feed.</Alert>
        ) : pending || feed === null ? null : (
          <>
            <Chip
              size="small"
              color="error"
              variant={feed.counts.critical > 0 ? "filled" : "outlined"}
              label={`${feed.counts.critical} critical`}
              data-testid="ota-alerts-critical"
            />
            <Chip
              size="small"
              color="warning"
              variant={feed.counts.warning > 0 ? "filled" : "outlined"}
              label={`${feed.counts.warning} warning`}
              data-testid="ota-alerts-warning"
            />
            <Chip
              size="small"
              color="info"
              variant={feed.counts.info > 0 ? "filled" : "outlined"}
              label={`${feed.counts.info} info`}
              data-testid="ota-alerts-info"
            />
          </>
        )}
      </Stack>
      {pending ? (
        <Typography color="text.secondary" sx={{ marginTop: 1 }} aria-busy="true">
          Loading the alert feed...
        </Typography>
      ) : feed === null || (feed.standing.length === 0 && feed.recent.length === 0) ? (
        <Typography color="text.secondary" sx={{ marginTop: 1 }} data-testid="ota-alerts-empty">
          No OTA alerts — rules fire from the OTA bundles screen&apos;s rule store.
        </Typography>
      ) : (
        <Stack spacing={1} sx={{ marginTop: 1 }}>
          {feed.standing.map((firing) => (
            <FiringRow key={firing.id} firing={firing} standing />
          ))}
          {feed.recent.map((firing) => (
            <FiringRow key={firing.id} firing={firing} />
          ))}
        </Stack>
      )}
    </Paper>
  );
}

function FiringRow({ firing, standing }: { firing: OtaAlertFiring; standing?: boolean }) {
  const severity = firing.severity;
  return (
    <Stack
      direction="row"
      spacing={1}
      sx={{ alignItems: "center", flexWrap: "wrap" }}
      data-testid={`ota-firing-${standing ? "standing" : "recent"}-${firing.id}`}
    >
      <Chip
        size="small"
        color={OTA_SEVERITY_COLORS[severity]}
        variant={standing ? "filled" : "outlined"}
        label={standing ? `${severity.toLowerCase()} firing` : `${severity.toLowerCase()} resolved`}
      />
      <Typography variant="body2" sx={{ fontFamily: "monospace" }}>
        {alertRuleExpression(firing)}
      </Typography>
      <Typography variant="body2" color="text.secondary">
        observed {firing.observedValue}
      </Typography>
      <Typography variant="body2" color="text.secondary">
        fired {new Date(firing.firedAt).toLocaleString()}
        {!standing && firing.resolvedAt !== null
          ? ` · resolved ${new Date(firing.resolvedAt).toLocaleString()}`
          : ""}
      </Typography>
      {firing.detail ? (
        <Tooltip title={firing.detail}>
          <Typography variant="body2" color="text.secondary" sx={{ fontStyle: "italic" }}>
            {firing.detail}
          </Typography>
        </Tooltip>
      ) : null}
    </Stack>
  );
}

// ---------------------------------------------------------------------------
// The funnel: stage bars, success rate, failure-reason breakdown
// ---------------------------------------------------------------------------

function FunnelBlock({
  failed,
  pending,
  releaseRef,
  stages,
  failures,
}: {
  failed: boolean;
  pending: boolean;
  releaseRef: string;
  stages: OtaFunnelStages | null;
  failures: Array<{ reason: string; count: number }>;
}) {
  const rows = stages !== null ? funnelRows(stages) : [];
  const success = stages !== null ? applySuccessRate(stages) : null;
  return (
    <Paper variant="outlined" sx={{ padding: 2 }} data-testid="ota-funnel-section">
      <Stack direction="row" spacing={2} sx={{ alignItems: "center", flexWrap: "wrap" }}>
        <Typography variant="h6" sx={{ flexGrow: 1 }}>
          Device funnel
        </Typography>
        {releaseRef !== "" ? <Chip size="small" variant="outlined" label={releaseRef} /> : null}
        {success !== null ? (
          <Tooltip title="applied / (applied + failed) — the share of apply attempts that landed">
            <Chip
              size="small"
              color={success >= 0.98 ? "success" : success >= 0.95 ? "warning" : "error"}
              label={`apply success ${formatPercent(success)}`}
              data-testid="ota-success-rate"
            />
          </Tooltip>
        ) : (
          <Chip size="small" variant="outlined" label="no apply attempts yet" data-testid="ota-success-rate" />
        )}
      </Stack>
      {failed ? (
        <Alert severity="warning" sx={{ marginTop: 1 }}>
          No funnel recorded for this bundle yet.
        </Alert>
      ) : pending || stages === null ? (
        <Typography color="text.secondary" sx={{ marginTop: 1 }} aria-busy="true">
          Loading the funnel...
        </Typography>
      ) : (
        <Stack spacing={1.5} sx={{ marginTop: 1 }}>
          {rows.map((row) => (
            <Stack key={row.label} spacing={0.25} data-testid={`ota-funnel-stage-${row.label.toLowerCase().replace(/\s+/g, "-")}`}>
              <Stack direction="row" spacing={1} sx={{ alignItems: "baseline" }}>
                <Typography variant="body2" sx={{ minWidth: 120 }}>
                  {row.label}
                </Typography>
                <Typography variant="body2" color="text.secondary">
                  {row.value.toLocaleString()} ({row.share}%)
                </Typography>
              </Stack>
              <LinearProgress variant="determinate" value={row.share} />
            </Stack>
          ))}
          <Stack direction="row" spacing={2} sx={{ marginTop: 0.5 }}>
            <Chip size="small" color="error" variant="outlined" label={`failed ${stages.failed}`} data-testid="ota-funnel-failed" />
            <Chip size="small" color="warning" variant="outlined" label={`rolled back ${stages.rolledBack}`} data-testid="ota-funnel-rolled-back" />
          </Stack>
          <Box>
            <Typography variant="subtitle2" sx={{ marginTop: 1 }}>
              Failure reasons
            </Typography>
            {failures.length === 0 ? (
              <Typography color="text.secondary" data-testid="ota-failures-empty">
                No failed applies recorded.
              </Typography>
            ) : (
              <Box sx={{ marginTop: 1 }} data-testid="ota-failures-chart">
                <BarChart
                  height={160}
                  xAxis={[{ scaleType: "band", data: failures.map((failure) => failure.reason) }]}
                  yAxis={[{ label: "applies" }]}
                  series={[{ data: failures.map((failure) => failure.count), label: "Failures", color: "#d32f2f" }]}
                  margin={{ left: 60 }}
                  hideLegend
                />
              </Box>
            )}
          </Box>
        </Stack>
      )}
    </Paper>
  );
}

// ---------------------------------------------------------------------------
// The crash-rate comparison: current vs new, with the delta
// ---------------------------------------------------------------------------

function HealthBlock({ failed, health }: { failed: boolean; health: OtaBundleHealthView | null }) {
  return (
    <Paper variant="outlined" sx={{ padding: 2 }} data-testid="ota-health-section">
      <Typography variant="h6">Crash-rate comparison</Typography>
      {failed ? (
        <Alert severity="warning" sx={{ marginTop: 1 }}>
          No health statement for this bundle yet.
        </Alert>
      ) : health === null ? (
        <Typography color="text.secondary" sx={{ marginTop: 1 }} aria-busy="true">
          Loading the health comparison...
        </Typography>
      ) : (
        <Stack spacing={1.5} sx={{ marginTop: 1 }}>
          <Stack direction="row" spacing={2} sx={{ flexWrap: "wrap" }}>
            <HealthSide label="New bundle" side={health.newBundle} highlight />
            <HealthSide label="Current released" side={health.currentBundle} />
          </Stack>
          {health.crashFreeRateDelta !== null ? (
            <Stack direction="row" spacing={1} sx={{ alignItems: "center" }}>
              <Typography variant="body2" sx={{ color: "text.secondary" }}>
                Crash-free delta (new - current):
              </Typography>
              <Chip
                size="small"
                color={health.crashFreeRateDelta >= 0 ? "success" : "error"}
                label={formatDeltaPoints(health.crashFreeRateDelta)}
                data-testid="ota-health-delta"
              />
              <Typography variant="caption" color="text.secondary">
                a negative delta is the halt signal (D10)
              </Typography>
            </Stack>
          ) : (
            <Typography variant="body2" color="text.secondary" data-testid="ota-health-no-delta">
              No comparable current bundle (or no sessions on a side) — the delta needs both.
            </Typography>
          )}
        </Stack>
      )}
    </Paper>
  );
}

function HealthSide({
  label,
  side,
  highlight,
}: {
  label: string;
  side: OtaBundleHealthView["currentBundle"];
  highlight?: boolean;
}) {
  if (side === null) {
    return (
      <Paper variant="outlined" sx={{ padding: 1.5, minWidth: 220 }}>
        <Typography color="text.secondary" variant="body2">
          {label}: none (no RELEASED predecessor)
        </Typography>
      </Paper>
    );
  }
  return (
    <Paper
      variant="outlined"
      sx={{ padding: 1.5, minWidth: 220, borderColor: highlight ? "primary.main" : undefined }}
      data-testid="ota-health-side"
    >
      <Stack spacing={0.5}>
        <Typography variant="body2" sx={{ fontWeight: highlight ? 600 : undefined }}>
          {label}: {side.bundleVersion} ({side.releaseRef})
        </Typography>
        <Typography variant="h5" data-testid="ota-health-rate">
          {side.crashFreeRate !== null ? formatPercent(side.crashFreeRate) : "no sessions"}
        </Typography>
        <Typography variant="caption" color="text.secondary">
          {side.sessions.toLocaleString()} sessions · {side.crashSessions.toLocaleString()} crashes
        </Typography>
      </Stack>
    </Paper>
  );
}
