/**
 * Version detail — the deep-linkable view of one authorized version
 * (issue #37, S8-03, AC5): the shareable URL a tester opens when
 * reproducing an issue. The eligibility route's versionId probe is
 * the entire authority: a version in the caller's authorized set
 * renders its status badge and platform rows; anything else —
 * unknown, a foreign application's, or unauthorized — renders the
 * opaque 404 page, byte-indistinguishable across the three (PRD 12:
 * the page leaks nothing about which case it is).
 */
import { useQuery } from "@tanstack/react-query";
import ArrowBackIcon from "@mui/icons-material/ArrowBack";
import BugReportIcon from "@mui/icons-material/BugReport";
import { Box, Button, Card, CardContent, Chip, CircularProgress, Stack, Typography } from "@mui/material";
import { useState } from "react";
import { Link as RouterLink, useParams, useSearchParams } from "react-router-dom";
import { ApiError } from "../api/problem";
import { fetchVersionEntry, HISTORY_STALE_TIME_MS } from "../api/history";
import FeedbackDialog from "../components/FeedbackDialog";
import HistoryEntryCard from "../components/HistoryEntryCard";
import NotFoundPage from "../components/NotFoundPage";

export default function VersionPage() {
  const { tenantId = "", applicationId = "", versionId = "" } = useParams();
  const [reporting, setReporting] = useState(false);
  // The S15-01 deep-link handoff (issue #72): the embedded SDK's
  // feedback link arrives as ?report=1&hint=<capped crash summary> —
  // the page opens its report dialog with the hint prefilled (the
  // tester edits and submits; nothing posts itself).
  const [searchParams] = useSearchParams();
  const reportDeepLink = searchParams.get("report") === "1";
  const reportHint = searchParams.get("hint") ?? "";

  const probe = useQuery({
    queryKey: ["history-entry", tenantId, applicationId, versionId],
    queryFn: () => fetchVersionEntry(tenantId, applicationId, versionId),
    staleTime: HISTORY_STALE_TIME_MS,
    retry: false,
  });

  if (probe.isError && probe.error instanceof ApiError && probe.error.status === 404) {
    return <NotFoundPage />;
  }

  // A 200 whose set does not contain the version (the console
  // persona's honest-empty answer) is the same opaque miss for this
  // page's purposes: nothing to show, no reason to show.
  if (probe.isSuccess && !probe.data?.entries.some((candidate) => candidate.versionId === versionId)) {
    return <NotFoundPage />;
  }

  const page = probe.data;
  const application = page?.application;
  const entry = page?.entries.find((candidate) => candidate.versionId === versionId);

  return (
    <Stack spacing={2}>
      <Stack direction="row" spacing={1} sx={{ alignItems: "center" }}>
        <Button
          size="small"
          startIcon={<ArrowBackIcon />}
          component={RouterLink}
          to={`/applications/${tenantId}/${applicationId}`}
          data-testid="version-back"
        >
          Version history
        </Button>
      </Stack>

      <Stack direction="row" spacing={1} sx={{ alignItems: "center" }}>
        <Typography variant="h5" component="h1" data-testid="version-title">
          {application ? application.name : "Version"}
        </Typography>
        {application?.status === "ARCHIVED" ? (
          <Chip size="small" color="warning" label="Archived" data-testid="version-app-archived" />
        ) : null}
      </Stack>

      {/* S11-01 (issue #46): the version screen's report entry point —
       * the whole page IS the submission's context (this version, the
       * builds its slots name), so the report needs nothing else. */}
      {entry ? (
        <Button
          size="small"
          variant="outlined"
          startIcon={<BugReportIcon />}
          onClick={() => setReporting(true)}
          data-testid={`feedback-trigger-${versionId}`}
          sx={{ alignSelf: "flex-start" }}
        >
          Report issue
        </Button>
      ) : null}

      {probe.isPending ? (
        <Box data-testid="version-loading" role="status" aria-label="Loading version">
          <CircularProgress size={28} sx={{ display: "block", mx: "auto", my: 4 }} />
        </Box>
      ) : null}

      {probe.isError ? (
        <Card data-testid="version-error">
          <CardContent>
            <Stack spacing={1.5} sx={{ alignItems: "center", textAlign: "center", py: 3 }}>
              <Typography variant="h6">Could not load the version</Typography>
              <Typography variant="body2" color="text.secondary">
                {probe.error instanceof ApiError && probe.error.code
                  ? `${probe.error.title ?? "Request failed"} (${probe.error.code})`
                  : "Unexpected error. Please retry."}
              </Typography>
              <Button variant="contained" size="small" onClick={() => probe.refetch()} data-testid="version-error-retry">
                Retry
              </Button>
            </Stack>
          </CardContent>
        </Card>
      ) : null}

      {probe.isSuccess && entry ? (
        <HistoryEntryCard
          entry={entry}
          tenantId={tenantId}
          applicationId={applicationId}
          autoReport={reportDeepLink}
          reportHint={reportHint}
        />
      ) : null}

      {entry ? (
        <FeedbackDialog
          open={reporting}
          onClose={() => setReporting(false)}
          versionName={entry.versionName}
          context={{ tenantId, applicationId, versionId }}
          builds={entry.platforms.map((slot) => ({
            artifactId: slot.artifactId,
            label: `${slot.platform === "IOS" ? "iOS" : "Android"} build${slot.buildNumber !== null ? ` ${slot.buildNumber}` : ""}`,
          }))}
        />
      ) : null}
    </Stack>
  );
}
