/**
 * The organization dashboard (issue #50, S11-05, PRD 29): the console's
 * landing screen — the six counts, the per-application release-health
 * rows (the BLOCKED/TESTING/HEALTHY/INACTIVE derivation the server
 * computes), and the caller's role-filtered pending actions, every
 * item deep-linking to the release screen it acts on (AC3). The whole
 * view is one server read the nav probe already authorized,
 * auto-refreshed every 30 seconds (the issue's technical note).
 */
import {
  Alert,
  Box,
  Button,
  Chip,
  CircularProgress,
  Divider,
  List,
  ListItemButton,
  ListItemText,
  Paper,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  Typography,
} from "@mui/material";
import { useQuery } from "@tanstack/react-query";
import { useMemo } from "react";
import { useNavigate } from "react-router-dom";
import {
  getOrgDashboard,
  type ApprovalStep,
  type ApplicationHealthRow,
  type OrgDashboard,
} from "../api/dashboards";
import { ApiError } from "../api/problem";
import { useAuth } from "../auth/AuthContext";

/** The refresh cadence (the story's technical note: cache briefly, 30 s). */
export const DASHBOARD_REFETCH_MS = 30_000;

export const HEALTH_COLORS: Record<ApplicationHealthRow["health"], "success" | "info" | "error" | "default"> = {
  HEALTHY: "success",
  TESTING: "info",
  BLOCKED: "error",
  INACTIVE: "default",
};

export const HEALTH_LABELS: Record<ApplicationHealthRow["health"], string> = {
  HEALTHY: "Healthy",
  TESTING: "Testing",
  BLOCKED: "Blocked",
  INACTIVE: "Inactive",
};

const APPROVAL_TYPE_LABELS: Record<string, string> = {
  QA: "QA",
  SECURITY: "Security",
  COMPLIANCE: "Compliance",
  PRODUCT: "Product",
  RELEASE_MANAGER: "Release manager",
};

function CountCard({ label, value, testid }: { label: string; value: number; testid: string }) {
  return (
    <Paper sx={{ padding: 2, minWidth: 140 }} data-testid={testid}>
      <Typography color="text.secondary" variant="body2">
        {label}
      </Typography>
      <Typography variant="h4">{value}</Typography>
    </Paper>
  );
}

/** The §29 health bar: the in-flight releases stacked production /
 * testing / blocked, proportional (a full-neutral bar when nothing
 * is in flight — the honest INACTIVE shape). */
export function HealthBar({ row }: { row: ApplicationHealthRow }) {
  const inFlight = row.production + row.testing + row.blockedFindings;
  const segments =
    inFlight === 0
      ? [{ flex: 1, color: "action.disabledBackground" }]
      : [
          { flex: row.production, color: "success.main" },
          { flex: row.testing, color: "info.main" },
          { flex: row.blockedFindings, color: "error.main" },
        ].filter((segment) => segment.flex > 0);
  return (
    <Box
      sx={{ display: "flex", height: 10, borderRadius: 1, overflow: "hidden", minWidth: 160 }}
      data-testid={`health-bar-${row.applicationId}`}
    >
      {segments.map((segment, index) => (
        <Box key={index} sx={{ flex: segment.flex, backgroundColor: segment.color }} />
      ))}
    </Box>
  );
}

function PendingStepItem({ step }: { step: ApprovalStep }) {
  const navigate = useNavigate();
  return (
    <ListItemButton
      onClick={() => navigate(`/releases/${step.applicationId}/${step.releaseId}`)}
      data-testid={`pending-approval-${step.releaseId}-${step.approvalType}`}
    >
      <ListItemText
        primary={
          <>
            {step.releaseRef} · {step.applicationName} ·{" "}
            {APPROVAL_TYPE_LABELS[step.approvalType] ?? step.approvalType} (round {step.roundNumber})
          </>
        }
        secondary={step.decidableByCaller ? "Waiting on you" : "In the queue"}
      />
      {step.decidableByCaller ? <Chip size="small" color="primary" label="Decide" /> : null}
    </ListItemButton>
  );
}

export default function DashboardPage() {
  const { user } = useAuth();
  const orgId = user?.orgId ?? null;
  const navigate = useNavigate();

  const query = useQuery({
    queryKey: ["org-dashboard", orgId],
    queryFn: () => getOrgDashboard(orgId as string),
    enabled: orgId !== null,
    refetchInterval: DASHBOARD_REFETCH_MS,
  });

  const decidableCount = useMemo(
    () =>
      query.data?.pendingActions.approvals.filter((step) => step.decidableByCaller).length ?? 0,
    [query.data],
  );

  if (orgId === null) {
    return (
      <Stack spacing={2}>
        <Typography variant="h5">Dashboard</Typography>
        <Alert severity="info">
          Your account has no active organization membership, so the organization dashboard is
          unavailable. Sign in with an organization role, or administer a tenant from its page.
        </Alert>
      </Stack>
    );
  }

  if (query.isPending) {
    return (
      <Stack spacing={2} sx={{ alignItems: "center", padding: 4 }} data-testid="dashboard-loading">
        <CircularProgress />
        <Typography color="text.secondary">Loading the organization dashboard…</Typography>
      </Stack>
    );
  }

  if (query.isError) {
    const denied = query.error instanceof ApiError && [403, 404].includes(query.error.status);
    return (
      <Stack spacing={2}>
        <Typography variant="h5">Dashboard</Typography>
        <Alert severity={denied ? "info" : "error"} data-testid="dashboard-error">
          {denied
            ? "Your role does not include the organization dashboard. Use the sections you can see in the navigation."
            : "The organization dashboard could not be loaded. Try again."}
        </Alert>
        {!denied ? (
          <Button onClick={() => query.refetch()} sx={{ alignSelf: "flex-start" }}>
            Retry
          </Button>
        ) : null}
      </Stack>
    );
  }

  const dashboard = query.data as OrgDashboard;
  const counts = dashboard.counts;
  const actions = dashboard.pendingActions;
  return (
    <Stack spacing={3}>
      <Stack direction="row" sx={{ justifyContent: "space-between", alignItems: "baseline" }}>
        <Typography variant="h5">Organization dashboard</Typography>
        <Typography color="text.secondary" variant="body2" data-testid="dashboard-generated">
          Live · refreshed {new Date(dashboard.generatedAt).toLocaleTimeString()} · every 30 s
        </Typography>
      </Stack>

      <Stack direction="row" spacing={2} sx={{ flexWrap: "wrap" }} useFlexGap>
        <CountCard label="Applications" value={counts.applications} testid="count-applications" />
        <CountCard label="Tenants" value={counts.tenants} testid="count-tenants" />
        <CountCard label="Active releases" value={counts.activeReleases} testid="count-active-releases" />
        <CountCard label="Pending approvals" value={counts.pendingApprovals} testid="count-pending-approvals" />
        <CountCard label="Testing" value={counts.testing} testid="count-testing" />
        <CountCard label="Production" value={counts.production} testid="count-production" />
      </Stack>

      <Paper>
        <Typography variant="h6" sx={{ padding: 2, paddingBottom: 0 }}>
          Release health by application
        </Typography>
        <Table size="small" data-testid="health-table">
          <TableHead>
            <TableRow>
              <TableCell>Application</TableCell>
              <TableCell>Health</TableCell>
              <TableCell>In-flight releases</TableCell>
              <TableCell>Distribution</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {dashboard.applications.length === 0 ? (
              <TableRow>
                <TableCell colSpan={4} data-testid="health-empty">
                  No applications registered yet.
                </TableCell>
              </TableRow>
            ) : (
              dashboard.applications.map((row) => (
                <TableRow
                  key={row.applicationId}
                  hover
                  sx={{ cursor: "pointer" }}
                  onClick={() => navigate(`/releases/${row.applicationId}`)}
                  data-testid={`health-row-${row.applicationId}`}
                >
                  <TableCell>{row.name}</TableCell>
                  <TableCell>
                    <Chip
                      size="small"
                      color={HEALTH_COLORS[row.health]}
                      label={HEALTH_LABELS[row.health]}
                    />
                  </TableCell>
                  <TableCell>{row.activeReleases}</TableCell>
                  <TableCell>
                    <Stack direction="row" spacing={1} sx={{ alignItems: "center" }}>
                      <HealthBar row={row} />
                      <Typography variant="caption" color="text.secondary">
                        {row.production} prod · {row.testing} test
                        {row.blockedFindings > 0 ? ` · ${row.blockedFindings} blocked` : ""}
                      </Typography>
                    </Stack>
                  </TableCell>
                </TableRow>
              ))
            )}
          </TableBody>
        </Table>
      </Paper>

      <Paper>
        <Typography variant="h6" sx={{ padding: 2, paddingBottom: 0 }}>
          Pending actions
        </Typography>
        {decidableCount > 0 ? (
          <Typography variant="body2" color="primary" sx={{ padding: 2, paddingBottom: 0 }}>
            {decidableCount} step{decidableCount === 1 ? "" : "s"} waiting on you.
          </Typography>
        ) : null}
        <List>
          {actions.approvals.length === 0 &&
          actions.reviews.length === 0 &&
          actions.failedGates.length === 0 ? (
            <ListItemButton disabled>
              <ListItemText primary="Nothing needs attention." secondary="Approvals, reviews, and gates are clear." />
            </ListItemButton>
          ) : null}
          {actions.approvals.length > 0 ? (
            <>
              <Divider component="li" />
              <Typography variant="subtitle2" sx={{ padding: 2, paddingBottom: 0 }}>
                {actions.approvals.length} approval step{actions.approvals.length === 1 ? "" : "s"} pending
              </Typography>
              {actions.approvals.map((step) => (
                <PendingStepItem key={`${step.releaseId}-${step.approvalType}`} step={step} />
              ))}
            </>
          ) : null}
          {actions.reviews.length > 0 ? (
            <>
              <Divider component="li" />
              <Typography variant="subtitle2" sx={{ padding: 2, paddingBottom: 0 }}>
                {actions.reviews.length} security review{actions.reviews.length === 1 ? "" : "s"} pending
              </Typography>
              {actions.reviews.map((step) => (
                <PendingStepItem key={`review-${step.releaseId}-${step.approvalType}`} step={step} />
              ))}
            </>
          ) : null}
          {actions.failedGates.length > 0 ? (
            <>
              <Divider component="li" />
              <Typography variant="subtitle2" sx={{ padding: 2, paddingBottom: 0 }}>
                {actions.failedGates.length} failed quality gate{actions.failedGates.length === 1 ? "" : "s"}
              </Typography>
              {actions.failedGates.map((gate) => (
                <ListItemButton
                  key={`${gate.releaseId}-${gate.gateType}`}
                  onClick={() => navigate(`/releases/${gate.applicationId}/${gate.releaseId}`)}
                  data-testid={`failed-gate-${gate.releaseId}-${gate.gateType}`}
                >
                  <ListItemText
                    primary={`${gate.releaseRef} · ${gate.applicationName} · ${gate.gateType}`}
                    secondary="A blocking gate failed and has not been overridden"
                  />
                </ListItemButton>
              ))}
            </>
          ) : null}
        </List>
      </Paper>
    </Stack>
  );
}
