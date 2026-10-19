/**
 * One authorized version of the version-history view (issue #37,
 * S8-03): the version name, the lifecycle status badge (DRAFT, the
 * M2-reserved ACTIVE, ARCHIVED, REVOKED — INV-006 keeps the terminal
 * states listed while a grant covers them), the entry's access TTL
 * when it has one, and the per-platform installable rows (build
 * number from artifact extraction, size, upload date — AC4). Since
 * S16-16 (issue #178) an entry that is an OTA bundle's own versions
 * row (ADR-0010 D2) also renders its manifest — the chip that says
 * the version ships over the air and the checksum row a tester
 * verifies an installed build against. The whole card deep-links to
 * the version detail page so a build link can be shared when
 * reproducing an issue.
 */
import HistoryIcon from "@mui/icons-material/History";
import BugReportIcon from "@mui/icons-material/BugReport";
import CloudDownloadIcon from "@mui/icons-material/CloudDownload";
import { Button, Card, CardActionArea, CardActions, CardContent, Chip, Stack, Typography } from "@mui/material";
import { useState } from "react";
import { Link as RouterLink } from "react-router-dom";
import type { HistoryEntry, HistoryEntryOta } from "../api/history";
import DownloadFlow from "./DownloadFlow";
import FeedbackDialog from "./FeedbackDialog";

export default function HistoryEntryCard({
  entry,
  tenantId,
  applicationId,
  autoReport = false,
  reportHint = "",
}: {
  entry: HistoryEntry;
  tenantId: string;
  applicationId: string;
  /**
   * The deep-link handoff (S15-01, issue #72): the version page the
   * embedded SDK's feedback link opens arrives with ?report=1 — the
   * dialog opens itself, with the capped crash hint prefilled.
   */
  autoReport?: boolean;
  reportHint?: string;
}) {
  const [reporting, setReporting] = useState(autoReport);

  return (
    <Card data-testid={`history-entry-${entry.versionId}`}>
      <CardActionArea
        component={RouterLink}
        to={`/applications/${tenantId}/${applicationId}/versions/${entry.versionId}`}
      >
        <CardContent>
          <Stack spacing={1}>
            <Stack direction="row" spacing={1} sx={{ alignItems: "center" }}>
              <HistoryIcon color="primary" fontSize="small" />
              <Typography variant="h6" component="h2" sx={{ flexGrow: 1 }} data-testid="history-entry-name">
                {entry.versionName}
              </Typography>
              {entry.ota ? (
                <Chip
                  size="small"
                  color="secondary"
                  icon={<CloudDownloadIcon />}
                  label="OTA update"
                  data-testid="history-entry-ota-chip"
                />
              ) : null}
              <StatusBadge status={entry.status} testId="history-entry-status" />
            </Stack>
            {entry.expiresAt ? (
              <Typography variant="caption" color="text.secondary" data-testid="history-entry-expiry">
                Access until {formatDate(entry.expiresAt)}
              </Typography>
            ) : null}
            {entry.ota ? <OtaManifest ota={entry.ota} /> : null}
            {entry.platforms.length > 0 ? (
              <Stack spacing={0.5}>
                {entry.platforms.map((slot) => (
                  <Stack
                    key={slot.platform}
                    direction="row"
                    spacing={1}
                    useFlexGap
                    sx={{ flexWrap: "wrap", alignItems: "center" }}
                    data-testid={`history-slot-${entry.versionId}-${slot.platform}`}
                  >
                    <Chip size="small" variant="outlined" color="primary" label={platformLabel(slot.platform)} />
                    <Typography variant="body2" color="text.secondary">
                      {slot.buildNumber !== null ? `Build ${slot.buildNumber} · ` : ""}
                      {formatBytes(slot.sizeBytes)} · Uploaded {formatDate(slot.uploadedAt)}
                    </Typography>
                  </Stack>
                ))}
              </Stack>
            ) : (
              <Typography variant="body2" color="text.secondary" data-testid="history-no-slots">
                No builds available for this version yet.
              </Typography>
            )}
          </Stack>
        </CardContent>
      </CardActionArea>
      <CardActions sx={{ pt: 0 }} data-testid={`history-downloads-${entry.versionId}`}>
        {entry.platforms.map((slot) => (
          <DownloadFlow
            key={slot.platform}
            artifactId={slot.artifactId}
            versionId={entry.versionId}
            platform={slot.platform}
            tenantId={tenantId}
          />
        ))}
        {/* S11-01 (issue #46): the app screen's report entry point —
         * the dialog pins the submission to this version's context
         * (and a build when the tester names one). Always present: a
         * version with no builds yet can still be reported on. */}
        <Button
          size="small"
          startIcon={<BugReportIcon />}
          onClick={() => setReporting(true)}
          sx={{ ml: "auto" }}
          data-testid={`feedback-card-trigger-${entry.versionId}`}
        >
          Report issue
        </Button>
      </CardActions>
      <FeedbackDialog
        open={reporting}
        onClose={() => setReporting(false)}
        versionName={entry.versionName}
        initialDescription={reportHint}
        context={{ tenantId, applicationId, versionId: entry.versionId }}
        builds={entry.platforms.map((slot) => ({
          artifactId: slot.artifactId,
          label: `${platformLabel(slot.platform)} build${slot.buildNumber !== null ? ` ${slot.buildNumber}` : ""}`,
        }))}
      />
    </Card>
  );
}

/** The lifecycle badge: the version's own state, colored for scanning. */
function StatusBadge({ status, testId }: { status: string; testId: string }) {
  if (status === "ARCHIVED") {
    return <Chip size="small" color="warning" label="Archived" data-testid={testId} />;
  }
  if (status === "REVOKED") {
    return <Chip size="small" color="error" label="Revoked" data-testid={testId} />;
  }
  if (status === "ACTIVE") {
    return <Chip size="small" color="success" label="Active" data-testid={testId} />;
  }
  return <Chip size="small" variant="outlined" label="Draft" data-testid={testId} />;
}

/**
 * The OTA bundle manifest rows (S16-16, issue #178): what the
 * device-facing check serves, surfaced for the tester — the label and
 * open platform tag the update identifies itself by, the byte size,
 * the BUNDLE's own lifecycle (beside the entry badge, which carries
 * the version row's status — two different axes), and the SHA-256 the
 * installed build's update flow verified. A REVOKED bundle keeps its
 * manifest (history, never re-resolution).
 */
function OtaManifest({ ota }: { ota: HistoryEntryOta }) {
  return (
    <Stack
      spacing={0.5}
      sx={{ backgroundColor: "action.hover", borderRadius: 1, p: 1 }}
      data-testid="history-entry-ota-manifest"
    >
      <Typography variant="caption" color="text.secondary">
        OTA bundle {ota.bundleVersion} · {ota.platform} ·{" "}
        {ota.sizeBytes !== null ? formatBytes(ota.sizeBytes) : "size pending"} · bundle {ota.status}
        {ota.releaseId ? " · on an OTA release" : ""}
      </Typography>
      <Typography variant="caption" sx={{ fontFamily: "monospace", wordBreak: "break-all" }} data-testid="history-entry-ota-sha">
        sha256 {ota.sha256}
      </Typography>
    </Stack>
  );
}

function platformLabel(platform: HistoryEntry["platforms"][number]["platform"]): string {
  return platform === "IOS" ? "iOS" : "Android";
}

/** ISO-8601 UTC instants render as the tester's local date. */
export function formatDate(iso: string): string {
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? iso : date.toLocaleDateString();
}

/** Byte sizes render in the unit the tester scans fastest. */
export function formatBytes(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes < 0) {
    return String(bytes);
  }
  if (bytes < 1024) {
    return `${bytes} B`;
  }
  const units = ["KB", "MB", "GB"];
  let value = bytes;
  let unit = "B";
  for (const next of units) {
    if (value < 1024) {
      break;
    }
    value /= 1024;
    unit = next;
  }
  return `${value >= 10 ? Math.round(value) : Math.round(value * 10) / 10} ${unit}`;
}
