/**
 * The tenant dashboard (issue #50, S11-05, PRD 29): the tenant-grain
 * landing screen — the tenant block's four counts, the in-flight
 * releases targeting the tenant (each deep-linking to its release
 * screen), and the downloads trend over the last seven days rendered
 * from the S11-04 tenant series (one server read; the per-(bucket,
 * release, ring) points folded with the counts-sum rule the analytics
 * contract demands). Reached from the tenants listing's Dashboard
 * action; auto-refreshed every 30 seconds like the org view.
 */
import { Alert, Box, Chip, CircularProgress, Paper, Stack, Typography } from "@mui/material";
import { BarChart } from "@mui/x-charts";
import { useQuery } from "@tanstack/react-query";
import { useNavigate, useParams } from "react-router-dom";
import { foldSeriesPoints, listMetricsSeries, type MetricPoint } from "../api/analytics";
import { getTenantDashboard } from "../api/dashboards";
import { getTenant } from "../api/tenants";
import { DASHBOARD_REFETCH_MS } from "./DashboardPage";

const SEVEN_DAYS_MS = 7 * 24 * 60 * 60 * 1000;

/** The chart's rows: one folded value per day bucket, oldest first. */
export function downloadsChartRows(points: readonly MetricPoint[]): { day: string; downloads: number }[] {
  return foldSeriesPoints(points, "DOWNLOADS").map((point) => ({
    day: point.bucketStart.slice(0, 10),
    downloads: point.value,
  }));
}

export default function TenantDashboardPage() {
  const { tenantId } = useParams<{ tenantId: string }>();
  const navigate = useNavigate();

  const tenant = useQuery({
    queryKey: ["tenant", tenantId],
    queryFn: () => getTenant(tenantId as string),
    enabled: tenantId !== undefined,
  });

  const dashboard = useQuery({
    queryKey: ["tenant-dashboard", tenantId],
    queryFn: () => getTenantDashboard(tenantId as string),
    enabled: tenantId !== undefined,
    refetchInterval: DASHBOARD_REFETCH_MS,
  });

  const window = useQuery({
    queryKey: ["tenant-downloads", tenantId],
    queryFn: () => {
      const now = new Date();
      return listMetricsSeries({
        tenantId: tenantId as string,
        metric: "DOWNLOADS",
        grain: "day",
        from: new Date(now.getTime() - SEVEN_DAYS_MS).toISOString(),
        to: now.toISOString(),
        limit: 100,
      });
    },
    enabled: tenantId !== undefined,
    refetchInterval: DASHBOARD_REFETCH_MS,
  });

  if (tenant.isPending || dashboard.isPending) {
    return (
      <Stack spacing={2} sx={{ alignItems: "center", padding: 4 }} data-testid="tenant-dashboard-loading">
        <CircularProgress />
        <Typography color="text.secondary">Loading the tenant dashboard…</Typography>
      </Stack>
    );
  }

  if (tenant.isError || dashboard.isError) {
    return (
      <Stack spacing={2}>
        <Typography variant="h5">Tenant dashboard</Typography>
        <Alert severity="error" data-testid="tenant-dashboard-error">
          The tenant dashboard could not be loaded.
        </Alert>
      </Stack>
    );
  }

  const counts = dashboard.data.counts;
  const rows = window.data ? downloadsChartRows(window.data.items) : [];
  return (
    <Stack spacing={3}>
      <Stack direction="row" sx={{ justifyContent: "space-between", alignItems: "baseline" }}>
        <Typography variant="h5" data-testid="tenant-dashboard-title">
          {tenant.data.name} · dashboard
        </Typography>
        <Typography color="text.secondary" variant="body2">
          Live · every 30 s
        </Typography>
      </Stack>

      <Stack direction="row" spacing={2} sx={{ flexWrap: "wrap" }} useFlexGap>
        <Paper sx={{ padding: 2, minWidth: 160 }} data-testid="tenant-count-applications">
          <Typography color="text.secondary" variant="body2">
            Applications
          </Typography>
          <Typography variant="h4">{counts.applications}</Typography>
        </Paper>
        <Paper sx={{ padding: 2, minWidth: 160 }} data-testid="tenant-count-versions">
          <Typography color="text.secondary" variant="body2">
            Available versions
          </Typography>
          <Typography variant="h4">{counts.availableVersions}</Typography>
        </Paper>
        <Paper sx={{ padding: 2, minWidth: 160 }} data-testid="tenant-count-testers">
          <Typography color="text.secondary" variant="body2">
            Active testers
          </Typography>
          <Typography variant="h4">{counts.activeTesters}</Typography>
        </Paper>
        <Paper sx={{ padding: 2, minWidth: 160 }} data-testid="tenant-count-pending">
          <Typography color="text.secondary" variant="body2">
            Pending releases
          </Typography>
          <Typography variant="h4">{counts.pendingReleases}</Typography>
        </Paper>
      </Stack>

      <Paper>
        <Typography variant="h6" sx={{ padding: 2 }}>
          Pending releases
        </Typography>
        {dashboard.data.pendingReleases.length === 0 ? (
          <Typography sx={{ padding: 2, paddingTop: 0 }} color="text.secondary" data-testid="tenant-pending-empty">
            No releases in flight for this tenant.
          </Typography>
        ) : (
          <Stack spacing={1} sx={{ padding: 2, paddingTop: 0 }}>
            {dashboard.data.pendingReleases.map((release) => (
              <Paper
                key={release.releaseId}
                variant="outlined"
                sx={{ padding: 1.5, cursor: "pointer" }}
                onClick={() => navigate(`/releases/${release.applicationId}/${release.releaseId}`)}
                data-testid={`tenant-pending-${release.releaseId}`}
              >
                <Stack
                  direction="row"
                  spacing={2}
                  sx={{ alignItems: "center", justifyContent: "space-between" }}
                >
                  <Typography>
                    {release.releaseRef} · {release.applicationName} · {release.status}
                  </Typography>
                  <Chip
                    size="small"
                    color={release.ring === "production" ? "success" : "info"}
                    label={release.ring ?? "unplaced"}
                  />
                </Stack>
              </Paper>
            ))}
          </Stack>
        )}
      </Paper>

      <Paper>
        <Typography variant="h6" sx={{ padding: 2, paddingBottom: 0 }}>
          Downloads · last 7 days
        </Typography>
        {rows.length === 0 ? (
          <Typography sx={{ padding: 2 }} color="text.secondary" data-testid="downloads-empty">
            No downloads recorded in the last 7 days.
          </Typography>
        ) : (
          <Box sx={{ padding: 2 }} data-testid="downloads-chart">
            <BarChart
              height={220}
              xAxis={[{ data: rows.map((row) => row.day), scaleType: "band" }]}
              yAxis={[{ label: "downloads" }]}
              series={[{ data: rows.map((row) => row.downloads), label: "Downloads", color: "#2563eb" }]}
              margin={{ left: 60 }}
              hideLegend
            />
          </Box>
        )}
      </Paper>
    </Stack>
  );
}
