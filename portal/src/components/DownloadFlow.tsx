/**
 * The download flow (issue #38, S8-04, FR-012's distribution-surfaces
 * note): a per-slot Download button on the version rows, and the
 * per-platform install guidance a tester sees BEFORE the URL opens —
 * Android's one-time "install unknown apps" consent (mobile Chrome
 * sideloads the APK), the iOS direct path's ad-hoc/enterprise signing
 * note, and the TestFlight deep-link placeholder row the channel
 * routing table carries for M2/M3. The server resolves the behavior;
 * this screen only renders it (the portal stays thin) and opens the
 * short-lived URL the engine ALLOW minted.
 */
import { useState } from "react";
import { useMutation } from "@tanstack/react-query";
import DownloadIcon from "@mui/icons-material/Download";
import {
  Button,
  CircularProgress,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Stack,
  Typography,
} from "@mui/material";
import { requestDownload, type DownloadIssuance } from "../api/downloads";
import { ApiError } from "../api/problem";
import { useToast } from "./ToastContext";
import { formatBytes } from "./HistoryEntryCard";

/** The minutes a URL remains fetchable, rounded down for the guidance line. */
function minutesLeft(expiresAt: string): number {
  const ms = new Date(expiresAt).getTime() - Date.now();
  return Math.max(0, Math.floor(ms / 60_000));
}

export default function DownloadFlow({
  artifactId,
  versionId,
  platform,
}: {
  artifactId: string;
  versionId: string;
  platform: "IOS" | "ANDROID";
}) {
  const toast = useToast();
  const [issued, setIssued] = useState<DownloadIssuance | null>(null);
  const [denial, setDenial] = useState<ApiError | null>(null);

  const issuance = useMutation({
    mutationFn: () => requestDownload(artifactId, versionId),
    onSuccess: (answer) => setIssued(answer),
    onError: (error) => {
      if (error instanceof ApiError) {
        // A concealed denial (no grant, revoked version, nothing
        // installable) renders exactly like the problem envelope it
        // is — opaque, no layer hints.
        setDenial(error);
        return;
      }
      toast.showError(error);
    },
  });

  const isAndroid = platform === "ANDROID";
  const label = isAndroid ? "Download Android build" : "Download iOS build";

  function openIssuedUrl() {
    if (!issued) {
      return;
    }
    // Relative emulator URLs resolve against the portal origin (the
    // nginx /api proxy serves them same-origin); production URLs are
    // absolute. Either way the browser owns the download from here.
    window.location.assign(issued.url);
  }

  return (
    <>
      <Button
        size="small"
        variant={isAndroid ? "contained" : "outlined"}
        startIcon={<DownloadIcon />}
        onClick={() => issuance.mutate()}
        disabled={issuance.isPending}
        data-testid={`download-trigger-${platform}`}
      >
        {issuance.isPending ? <CircularProgress size={16} color="inherit" /> : label}
      </Button>

      <Dialog
        open={issued !== null || denial !== null}
        onClose={() => {
          setIssued(null);
          setDenial(null);
        }}
        maxWidth="sm"
        fullWidth
        data-testid="download-guidance"
      >
        {issued ? (
          <>
            <DialogTitle data-testid="download-guidance-title">
              {guidanceTitle(issued.behavior)}
            </DialogTitle>
            <DialogContent>
              <Stack spacing={1.5}>
                {guidanceBody(issued.behavior)}
                <Typography variant="body2" color="text.secondary" data-testid="download-guidance-facts">
                  {issued.fileName ?? "artifact"} · {formatBytes(issued.sizeBytes)} · link valid for about{" "}
                  {minutesLeft(issued.expiresAt)} min
                </Typography>
              </Stack>
            </DialogContent>
            <DialogActions>
              <Button onClick={() => setIssued(null)} data-testid="download-guidance-cancel">
                Not now
              </Button>
              <Button variant="contained" onClick={openIssuedUrl} data-testid="download-guidance-continue">
                {issued.behavior === "DEEP_LINK_TESTFLIGHT" ? "Open TestFlight" : "Continue to download"}
              </Button>
            </DialogActions>
          </>
        ) : null}
        {denial ? (
          <>
            <DialogTitle data-testid="download-denied-title">This build is not available</DialogTitle>
            <DialogContent>
              <Typography variant="body2" data-testid="download-denied-body">
                {denial.code
                  ? `${denial.title ?? "Request failed"} (${denial.code}): ${denial.detail ?? ""}`.trim()
                  : "Unexpected error. Please retry."}
              </Typography>
            </DialogContent>
            <DialogActions>
              <Button
                onClick={() => {
                  setDenial(null);
                  issuance.mutate();
                }}
                data-testid="download-denied-retry"
              >
                Retry
              </Button>
              <Button onClick={() => setDenial(null)} data-testid="download-denied-close">
                Close
              </Button>
            </DialogActions>
          </>
        ) : null}
      </Dialog>
    </>
  );
}

function guidanceTitle(behavior: DownloadIssuance["behavior"]): string {
  if (behavior === "OPEN_SIGNED_URL") {
    return "Allow installs from your browser first";
  }
  if (behavior === "OPEN_SIGNED_URL_ADHOC") {
    return "About direct iOS installs";
  }
  return "This build is distributed through TestFlight";
}

function guidanceBody(behavior: DownloadIssuance["behavior"]) {
  if (behavior === "OPEN_SIGNED_URL") {
    return (
      <Typography variant="body2" data-testid="download-guidance-android">
        Android asks for a one-time permission before a browser can install an app. If the download does not
        install automatically: open Settings, then Apps, then your browser (Chrome), then "Install unknown
        apps", and allow installs from this source. Then tap Continue — the APK downloads straight from
        ShipperDog's secure link.
      </Typography>
    );
  }
  if (behavior === "OPEN_SIGNED_URL_ADHOC") {
    return (
      <Typography variant="body2" data-testid="download-guidance-ios">
        Direct iOS installs work only for ad-hoc or enterprise-signed builds whose provisioning includes your
        device. Tap Continue to download the IPA; if the install is refused, this build was not provisioned
        for your device — ask the team to distribute it through TestFlight.
      </Typography>
    );
  }
  return (
    <Typography variant="body2" data-testid="download-guidance-testflight">
      Installation is owned by Apple's TestFlight channel: the download link opens the TestFlight app
      (itms-beta://), where the build installs after you accept the invitation.
    </Typography>
  );
}
