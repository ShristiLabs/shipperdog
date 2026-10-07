/**
 * Application detail — the version history view (issue #37, S8-03,
 * FR-013): the authorized versions of one application, newest first,
 * reached from an S8-02 catalog card or a shared deep link. The
 * server computes the set (the decision API's eligibility listing,
 * PRD 26: never a filtered bigger list): old versions stay listed
 * while a grant covers them (INV-006), ARCHIVED and REVOKED versions
 * keep their badges, and the latest-N window shows exactly the N
 * newest (FR-011). Pages at the story's 20 through the cursor
 * envelope; an application the tester cannot reach renders the
 * opaque 404 page, byte-indistinguishable from an unknown one.
 */
import { useInfiniteQuery, useQueryClient } from "@tanstack/react-query";
import ArrowBackIcon from "@mui/icons-material/ArrowBack";
import RefreshIcon from "@mui/icons-material/Refresh";
import {
  Box,
  Button,
  Card,
  CardContent,
  Chip,
  CircularProgress,
  Stack,
  Typography,
} from "@mui/material";
import { useState } from "react";
import { Link as RouterLink, useParams } from "react-router-dom";
import { ApiError } from "../api/problem";
import { fetchHistoryPage, HISTORY_STALE_TIME_MS } from "../api/history";
import HistoryEntryCard from "../components/HistoryEntryCard";
import NotFoundPage from "../components/NotFoundPage";

export default function ApplicationPage() {
  const { tenantId = "", applicationId = "" } = useParams();
  const queryClient = useQueryClient();
  const [refreshing, setRefreshing] = useState(false);

  const history = useInfiniteQuery({
    queryKey: ["history", tenantId, applicationId],
    queryFn: ({ pageParam }) => fetchHistoryPage(tenantId, applicationId, { cursor: pageParam }),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (lastPage) => lastPage.next_cursor,
    staleTime: HISTORY_STALE_TIME_MS,
  });

  if (history.isError && history.error instanceof ApiError && history.error.status === 404) {
    return <NotFoundPage />;
  }

  const page = history.data?.pages[0];
  const application = page?.application;
  const entries = history.data?.pages.flatMap((p) => p.entries) ?? [];

  async function handleRefresh() {
    setRefreshing(true);
    try {
      // Invalidation refetches the active query — one refetch, not two.
      await queryClient.invalidateQueries({ queryKey: ["history", tenantId, applicationId] });
    } finally {
      setRefreshing(false);
    }
  }

  return (
    <Stack spacing={2}>
      <Stack direction="row" spacing={1} sx={{ alignItems: "center" }}>
        <Button
          size="small"
          startIcon={<ArrowBackIcon />}
          component={RouterLink}
          to="/"
          data-testid="history-back"
        >
          My Applications
        </Button>
      </Stack>

      <Stack direction="row" spacing={1} sx={{ alignItems: "center", justifyContent: "space-between" }}>
        <Stack direction="row" spacing={1} sx={{ alignItems: "center" }}>
          <Typography variant="h5" component="h1" data-testid="history-title">
            {application ? application.name : "Version history"}
          </Typography>
          {application?.status === "ARCHIVED" ? (
            <Chip size="small" color="warning" label="Archived" data-testid="history-app-archived" />
          ) : null}
        </Stack>
        <Button
          size="small"
          startIcon={<RefreshIcon />}
          onClick={handleRefresh}
          disabled={refreshing || history.isPending}
          data-testid="history-refresh"
        >
          {refreshing ? "Refreshing" : "Refresh"}
        </Button>
      </Stack>

      {history.isPending ? (
        <Box data-testid="history-loading" role="status" aria-label="Loading version history">
          <CircularProgress size={28} sx={{ display: "block", mx: "auto", my: 4 }} />
        </Box>
      ) : null}

      {history.isError ? <HistoryError error={history.error} onRetry={() => history.refetch()} /> : null}

      {history.isSuccess && entries.length === 0 ? <HistoryEmpty /> : null}

      {history.isSuccess && entries.length > 0 ? (
        <Stack spacing={2} data-testid="history-list">
          {entries.map((entry) => (
            <HistoryEntryCard
              key={entry.versionId}
              entry={entry}
              tenantId={tenantId}
              applicationId={applicationId}
            />
          ))}
          {history.hasNextPage ? (
            <Button
              variant="outlined"
              onClick={() => history.fetchNextPage()}
              disabled={history.isFetchingNextPage}
              data-testid="history-load-more"
            >
              {history.isFetchingNextPage ? <CircularProgress size={20} sx={{ mr: 1 }} /> : null}
              Load more
            </Button>
          ) : null}
        </Stack>
      ) : null}
    </Stack>
  );
}

/** A grantless-but-linked view of an authorized application: honest, not an error. */
function HistoryEmpty() {
  return (
    <Card data-testid="history-empty">
      <CardContent>
        <Stack spacing={1.5} sx={{ alignItems: "center", textAlign: "center", py: 3 }}>
          <Typography variant="h6">No versions yet</Typography>
          <Typography variant="body2" color="text.secondary" sx={{ maxWidth: 360 }}>
            You are not authorized for any version of this application yet. When a build is
            shared with you, it will appear here.
          </Typography>
        </Stack>
      </CardContent>
    </Card>
  );
}

/** API failures render the problem envelope honestly, with a retry. */
function HistoryError({ error, onRetry }: { error: unknown; onRetry: () => void }) {
  const apiError = error instanceof ApiError ? error : null;
  return (
    <Card data-testid="history-error">
      <CardContent>
        <Stack spacing={1.5} sx={{ alignItems: "center", textAlign: "center", py: 3 }}>
          <Typography variant="h6">Could not load the version history</Typography>
          <Typography variant="body2" color="text.secondary" data-testid="history-error-detail">
            {apiError
              ? `${apiError.title ?? "Request failed"}${apiError.code ? ` (${apiError.code})` : ""}`
              : "Unexpected error. Please retry."}
          </Typography>
          {apiError?.traceId ? (
            <Typography variant="caption" color="text.secondary">
              trace {apiError.traceId}
            </Typography>
          ) : null}
          <Button variant="contained" size="small" onClick={onRetry} data-testid="history-error-retry">
            Retry
          </Button>
        </Stack>
      </CardContent>
    </Card>
  );
}
