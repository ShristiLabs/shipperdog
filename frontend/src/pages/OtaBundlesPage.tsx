/**
 * OTA bundles screen (S16-15, issue #177; ADR-0010 workstreams 3, 4,
 * 11): the console's OTA authoring home for one application — the
 * bundle listing (only SIGNED-and-later rows are listable) with the
 * V55 status filter and the author's revocation, the upload wizard
 * walking the create -> direct-to-storage upload session ->
 * finalize+sign chain and answering the resulting SIGNED manifest
 * (sha256, size, platformTag, keyId — the SDK's embedding facts, D8),
 * the exactly-once sdc1. app tokens (the value shows once, at issue;
 * the registry stores only its SHA-256), and the per-application
 * OTA alert-rule store (the dashboard feed's source). The screen is
 * application-addressed throughout (the picker's ReleasesPage
 * pattern); every write stays server-enforced.
 */
import ContentCopyIcon from "@mui/icons-material/ContentCopy";
import UploadFileIcon from "@mui/icons-material/UploadFile";
import {
  Alert,
  Autocomplete,
  Button,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  LinearProgress,
  MenuItem,
  Paper,
  Stack,
  Switch,
  TextField,
  Tooltip,
  Typography,
} from "@mui/material";
import { DataGrid, type GridColDef } from "@mui/x-data-grid";
import { useInfiniteQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { useMemo, useState } from "react";
import { useParams } from "react-router-dom";
import {
  createOtaAlertRule,
  createOtaBundle,
  deleteOtaAlertRule,
  finalizeOtaBundle,
  issueOtaAppToken,
  listOtaAlertRules,
  listOtaAppTokens,
  listOtaBundles,
  putOtaBundleBytes,
  revokeOtaAppToken,
  revokeOtaBundle,
  updateOtaAlertRule,
  type OtaAlertRule,
  type OtaAlertRuleInput,
  type OtaAppToken,
  type OtaBundle,
} from "../api/ota";
import { useAuth } from "../auth/AuthContext";
import { useToast } from "../components/ToastContext";
import { useAllApplications } from "../hooks/useOrganizationDirectory";
import {
  OTA_ALERT_COMPARATORS,
  OTA_ALERT_METRICS,
  OTA_ALERT_SEVERITIES,
  OTA_BUNDLE_STATUSES,
  OTA_BUNDLE_STATUS_COLORS,
  OTA_BUNDLE_STATUS_LABELS,
  OTA_SEVERITY_COLORS,
  alertRuleExpression,
  type OtaBundleStatus,
} from "../otaRules";

function formatBytes(size: number): string {
  if (size < 1024) return `${size} B`;
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`;
  return `${(size / (1024 * 1024)).toFixed(1)} MB`;
}

function shortSha(sha: string): string {
  return sha.length > 12 ? `${sha.slice(0, 12)}...` : sha;
}

/** The wizard's machine: the form, the in-flight chain, and the
 * landed SIGNED manifest the operator embeds from. */
type WizardState =
  | { step: "form" }
  | { step: "chain"; label: string }
  | { step: "signed"; bundle: OtaBundle };

export default function OtaBundlesPage() {
  const { applicationId: routeApplicationId } = useParams<{ applicationId: string }>();
  const { user } = useAuth();
  const orgId = user?.orgId ?? null;
  const toast = useToast();
  const queryClient = useQueryClient();

  const directory = useAllApplications(orgId);
  const [pickedApplicationId, setPickedApplicationId] = useState<string | null>(null);
  const applicationId =
    pickedApplicationId ?? routeApplicationId ?? directory.applications[0]?.id ?? null;
  const selectedApplication =
    directory.applications.find((application) => application.id === applicationId) ?? null;

  const [status, setStatus] = useState<OtaBundleStatus | "">("");
  const [wizardOpen, setWizardOpen] = useState(false);
  const [revokeTarget, setRevokeTarget] = useState<OtaBundle | null>(null);

  const bundles = useInfiniteQuery({
    queryKey: ["ota-bundles", applicationId, status || undefined],
    queryFn: ({ pageParam }) =>
      listOtaBundles(applicationId as string, {
        ...(status !== "" ? { status } : {}),
        limit: 50,
        cursor: pageParam,
      }),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (lastPage) => lastPage.nextCursor,
    enabled: applicationId !== null,
  });
  const rows = useMemo(() => bundles.data?.pages.flatMap((page) => page.items) ?? [], [bundles.data]);

  function invalidateBundles() {
    void queryClient.invalidateQueries({ queryKey: ["ota-bundles", applicationId] });
  }

  const revoke = useMutation({
    mutationFn: (bundle: OtaBundle) => revokeOtaBundle(bundle.id, applicationId as string),
    onSuccess: (bundle) => {
      invalidateBundles();
      toast.showSuccess(`Bundle ${bundle.bundleVersion} revoked — devices now answer no-update`);
    },
    onError: toast.showError,
  });

  const columns: GridColDef<OtaBundle>[] = [
    { field: "bundleVersion", headerName: "Bundle", width: 120 },
    { field: "platformTag", headerName: "Platform", width: 130 },
    {
      field: "status",
      headerName: "Status",
      width: 130,
      renderCell: (params) => {
        const value = params.row.status as OtaBundleStatus;
        return (
          <Chip
            size="small"
            label={OTA_BUNDLE_STATUS_LABELS[value] ?? value}
            color={OTA_BUNDLE_STATUS_COLORS[value] ?? "default"}
            variant={value === "UPLOADING" ? "outlined" : "filled"}
          />
        );
      },
    },
    {
      field: "forced",
      headerName: "Forced",
      width: 90,
      valueGetter: (value: boolean) => (value ? "forced" : "optional"),
    },
    {
      field: "sha256",
      headerName: "sha256",
      width: 150,
      renderCell: (params) => (
        <Tooltip title={params.row.sha256}>
          <Typography variant="body2" sx={{ fontFamily: "monospace" }}>
            {shortSha(params.row.sha256)}
          </Typography>
        </Tooltip>
      ),
    },
    {
      field: "sizeBytes",
      headerName: "Size",
      width: 110,
      valueGetter: (value: number) => formatBytes(value),
    },
    {
      field: "keyId",
      headerName: "Signing key",
      width: 140,
      renderCell: (params) => (
        <Tooltip title={params.row.keyId}>
          <Typography variant="body2" sx={{ fontFamily: "monospace" }}>
            {shortSha(params.row.keyId)}
          </Typography>
        </Tooltip>
      ),
    },
    {
      field: "releaseId",
      headerName: "Release",
      width: 150,
      valueGetter: (value: string | null) => value ?? "—",
    },
    {
      field: "createdAt",
      headerName: "Created",
      type: "dateTime",
      width: 180,
      valueGetter: (value: string) => new Date(value),
    },
    {
      field: "actions",
      headerName: "Actions",
      sortable: false,
      filterable: false,
      width: 120,
      renderCell: (params) => (
        <Button
          size="small"
          color="error"
          disabled={params.row.status !== "SIGNED" && params.row.status !== "RELEASED"}
          onClick={() => setRevokeTarget(params.row)}
          data-testid={`revoke-bundle-${params.row.id}`}
        >
          Revoke
        </Button>
      ),
    },
  ];

  if (orgId === null) {
    return (
      <Stack spacing={2}>
        <Typography variant="h5">OTA bundles</Typography>
        <Alert severity="info">OTA authoring needs an organization context.</Alert>
      </Stack>
    );
  }

  return (
    <Stack spacing={2}>
      <Stack direction="row" spacing={2} sx={{ alignItems: "center" }}>
        <Typography variant="h5" sx={{ flexGrow: 1 }} data-testid="ota-bundles-title">
          OTA bundles
        </Typography>
        <Button
          variant="contained"
          startIcon={<UploadFileIcon />}
          disabled={applicationId === null}
          onClick={() => setWizardOpen(true)}
          data-testid="upload-bundle"
        >
          Upload bundle
        </Button>
      </Stack>

      <Stack direction="row" spacing={2} sx={{ alignItems: "center", flexWrap: "wrap" }}>
        <Autocomplete
          sx={{ minWidth: 280 }}
          options={directory.applications}
          loading={directory.loading}
          getOptionLabel={(option) => option.name}
          isOptionEqualToValue={(option, value) => option.id === value.id}
          value={selectedApplication}
          onChange={(_event, value) => setPickedApplicationId(value?.id ?? null)}
          renderInput={(params) => <TextField {...params} label="Application" required />}
          data-testid="ota-application-picker"
        />
        <TextField
          select
          sx={{ minWidth: 160 }}
          label="Status"
          value={status}
          onChange={(event) => setStatus(event.target.value as OtaBundleStatus | "")}
          slotProps={{ htmlInput: { "data-testid": "ota-status-filter" } }}
        >
          <MenuItem value="">All</MenuItem>
          {OTA_BUNDLE_STATUSES.map((value) => (
            <MenuItem key={value} value={value}>
              {OTA_BUNDLE_STATUS_LABELS[value]}
            </MenuItem>
          ))}
        </TextField>
      </Stack>

      {applicationId === null ? (
        <Typography color="text.secondary" data-testid="ota-bundles-no-application">
          No applications in the organization yet — OTA bundles are authored per application.
        </Typography>
      ) : bundles.isError ? (
        <Alert severity="error" data-testid="ota-bundles-error">
          Failed to load the bundles.
        </Alert>
      ) : (
        <Paper variant="outlined" sx={{ padding: 2 }} data-testid="ota-bundles-section">
          <div style={{ height: 400, width: "100%" }} data-testid="ota-bundles-grid">
            <DataGrid
              rows={rows}
              columns={columns}
              getRowId={(row: OtaBundle) => row.id}
              loading={bundles.isPending}
              density="compact"
              disableRowSelectionOnClick
              hideFooter
            />
          </div>
          {bundles.hasNextPage ? (
            <Button
              onClick={() => void bundles.fetchNextPage()}
              disabled={bundles.isFetchingNextPage}
              sx={{ marginTop: 1 }}
              data-testid="ota-bundles-load-more"
            >
              {bundles.isFetchingNextPage ? "Loading..." : "Load more"}
            </Button>
          ) : null}
        </Paper>
      )}

      {applicationId !== null ? (
        <>
          <AppTokensSection applicationId={applicationId} />
          <AlertRulesSection applicationId={applicationId} bundles={rows} />
        </>
      ) : null}

      <UploadWizard
        open={wizardOpen}
        applicationId={applicationId}
        onClose={() => setWizardOpen(false)}
        onSigned={() => invalidateBundles()}
      />

      <Dialog open={revokeTarget !== null} onClose={() => setRevokeTarget(null)} maxWidth="sm" fullWidth>
        <DialogTitle>Revoke bundle?</DialogTitle>
        <DialogContent>
          <Typography>
            Revoking <strong>{revokeTarget?.bundleVersion}</strong> withdraws the SIGNED bundle: the
            device check answers no-update for it (the S16-07 refusal), and the revocation is
            recorded. This is the author&apos;s withdrawal, not a device-side rollback.
          </Typography>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setRevokeTarget(null)}>Cancel</Button>
          <Button
            color="error"
            variant="contained"
            data-testid="confirm-revoke-bundle"
            onClick={() => {
              if (revokeTarget) revoke.mutate(revokeTarget);
              setRevokeTarget(null);
            }}
          >
            Revoke
          </Button>
        </DialogActions>
      </Dialog>
    </Stack>
  );
}

// ---------------------------------------------------------------------------
// The upload wizard: create -> upload session -> finalize + sign
// ---------------------------------------------------------------------------

interface UploadWizardProps {
  open: boolean;
  applicationId: string | null;
  onClose: () => void;
  onSigned: () => void;
}

const SEMVER_TRIPLE = /^\d+\.\d+\.\d+$/;

/** The wizard walks ADR-0010's authoring chain one dialog: the
 * metadata form, then the three-step chain (create the draft with its
 * upload grant, stream the bytes direct to storage, finalize+sign —
 * the Ed25519 key generates on the app's first finalize), then the
 * landed SIGNED manifest with the facts an SDK embeds: sha256, size,
 * platformTag, keyId (D8's copyable pair). */
function UploadWizard({ open, applicationId, onClose, onSigned }: UploadWizardProps) {
  const toast = useToast();
  const [state, setState] = useState<WizardState>({ step: "form" });
  const [bundleVersion, setBundleVersion] = useState("");
  const [platformTag, setPlatformTag] = useState("");
  const [forced, setForced] = useState(false);
  const [minAppVersion, setMinAppVersion] = useState("");
  const [file, setFile] = useState<File | null>(null);
  const [error, setError] = useState<string | null>(null);

  const versionError =
    bundleVersion.trim() === ""
      ? undefined
      : SEMVER_TRIPLE.test(bundleVersion.trim())
        ? undefined
        : "The bundle version is a semver triple (e.g. 2.4.1) — it labels the bundle's own versions row";
  const platformError =
    platformTag.trim() === "" || platformTag.trim().length <= 32
      ? undefined
      : "The platform tag is 1-32 characters (an open vocabulary)";
  const canSubmit =
    applicationId !== null &&
    bundleVersion.trim() !== "" &&
    versionError === undefined &&
    platformTag.trim() !== "" &&
    platformError === undefined &&
    file !== null;

  function reset() {
    setState({ step: "form" });
    setBundleVersion("");
    setPlatformTag("");
    setForced(false);
    setMinAppVersion("");
    setFile(null);
    setError(null);
  }

  async function run() {
    if (applicationId === null || file === null) return;
    setError(null);
    try {
      setState({ step: "chain", label: "Creating the bundle draft and upload session" });
      const draft = await createOtaBundle({
        applicationId,
        bundleVersion: bundleVersion.trim(),
        platformTag: platformTag.trim(),
        fileName: file.name,
        forced,
        ...(minAppVersion.trim() !== "" ? { minAppVersion: minAppVersion.trim() } : {}),
      });
      setState({ step: "chain", label: `Uploading ${formatBytes(file.size)} to the granted session` });
      await putOtaBundleBytes(draft.upload.uploadUrl, file);
      setState({ step: "chain", label: "Finalizing and signing (hashing back the stored bytes)" });
      const finalized = await finalizeOtaBundle(draft.id, draft.versionId);
      setState({ step: "signed", bundle: finalized.bundle });
      onSigned();
      toast.showSuccess(`Bundle ${finalized.bundle.bundleVersion} is SIGNED`);
    } catch (failure) {
      setState({ step: "form" });
      setError(failure instanceof Error ? failure.message : "The upload chain failed");
      toast.showError(failure);
    }
  }

  return (
    <Dialog
      open={open}
      onClose={state.step === "chain" ? undefined : onClose}
      fullWidth
      maxWidth="sm"
      data-testid="upload-wizard"
    >
      <DialogTitle>Upload OTA bundle</DialogTitle>
      <DialogContent>
        {state.step === "form" ? (
          <Stack spacing={2} sx={{ marginTop: 1 }} data-testid="wizard-form">
            <TextField
              label="Bundle version (semver triple)"
              value={bundleVersion}
              onChange={(event) => setBundleVersion(event.target.value)}
              error={versionError !== undefined}
              helperText={versionError ?? "Labels the bundle's dedicated versions row (D2)"}
              slotProps={{ htmlInput: { "data-testid": "wizard-bundle-version" } }}
              required
            />
            <TextField
              label="Platform tag"
              value={platformTag}
              onChange={(event) => setPlatformTag(event.target.value)}
              error={platformError !== undefined}
              helperText={platformError ?? "Open vocabulary, 1-32 characters (e.g. js-bundle, assets-2)"}
              slotProps={{ htmlInput: { "data-testid": "wizard-platform-tag" } }}
              required
            />
            <TextField
              label="Minimum app version (optional)"
              value={minAppVersion}
              onChange={(event) => setMinAppVersion(event.target.value)}
              helperText="Devices on older apps never see the bundle (D7's per-bundle default)"
              slotProps={{ htmlInput: { "data-testid": "wizard-min-app-version" } }}
            />
            <Stack direction="row" spacing={1} sx={{ alignItems: "center" }}>
              <Switch
                checked={forced}
                onChange={(event) => setForced(event.target.checked)}
                data-testid="wizard-forced"
              />
              <Typography>Forced (D7: the update applies without asking)</Typography>
            </Stack>
            <Button component="label" variant="outlined" data-testid="wizard-file-button">
              {file ? `${file.name} (${formatBytes(file.size)})` : "Choose the bundle archive"}
              <input
                type="file"
                hidden
                onChange={(event) => setFile(event.target.files?.[0] ?? null)}
                data-testid="wizard-file-input"
              />
            </Button>
            {error ? <Alert severity="error" data-testid="wizard-error">{error}</Alert> : null}
          </Stack>
        ) : state.step === "chain" ? (
          <Stack spacing={2} sx={{ marginTop: 1, padding: 2 }} data-testid="wizard-chain">
            <LinearProgress />
            <Typography>{state.label}...</Typography>
          </Stack>
        ) : (
          <Stack spacing={1.5} sx={{ marginTop: 1 }} data-testid="wizard-signed">
            <Alert severity="success">
              Bundle <strong>{state.bundle.bundleVersion}</strong> is SIGNED — the manifest below is
              what devices verify.
            </Alert>
            <SignedManifestRow label="sha256" value={state.bundle.sha256} mono copy />
            <SignedManifestRow label="Size" value={formatBytes(state.bundle.sizeBytes)} />
            <SignedManifestRow label="Platform" value={state.bundle.platformTag} />
            <SignedManifestRow label="Signing keyId" value={state.bundle.keyId} mono copy />
            <SignedManifestRow
              label="Signature"
              value={state.bundle.signature}
              mono
              copy
              shortened
            />
            <Typography variant="body2" color="text.secondary">
              The checksum is signed under the app&apos;s active Ed25519 key — copy the public half
              (keyId) from the application&apos;s detail page into the SDK before it ships (D8).
            </Typography>
          </Stack>
        )}
      </DialogContent>
      <DialogActions>
        {state.step === "form" ? (
          <>
            <Button onClick={onClose}>Cancel</Button>
            <Button
              variant="contained"
              disabled={!canSubmit}
              onClick={() => void run()}
              data-testid="wizard-submit"
            >
              Create, upload, and sign
            </Button>
          </>
        ) : state.step === "chain" ? (
          <Button disabled>Working...</Button>
        ) : (
          <>
            <Button
              onClick={() => {
                reset();
                onClose();
              }}
              data-testid="wizard-close"
            >
              Done
            </Button>
            <Button onClick={reset} data-testid="wizard-again">
              Upload another
            </Button>
          </>
        )}
      </DialogActions>
    </Dialog>
  );
}

function SignedManifestRow({
  label,
  value,
  mono,
  copy,
  shortened,
}: {
  label: string;
  value: string;
  mono?: boolean;
  copy?: boolean;
  shortened?: boolean;
}) {
  const toast = useToast();
  const shown = shortened && value.length > 24 ? `${value.slice(0, 24)}...` : value;
  async function copyValue() {
    try {
      await navigator.clipboard.writeText(value);
      toast.showSuccess(`${label} copied`);
    } catch {
      toast.showError(new Error("clipboard-unavailable"));
    }
  }
  return (
    <Stack direction="row" spacing={1} sx={{ alignItems: "center" }} data-testid={`signed-${label.toLowerCase().replace(/[^a-z0-9]+/g, "-")}`}>
      <Typography variant="body2" sx={{ minWidth: 120, color: "text.secondary" }}>
        {label}
      </Typography>
      <Typography variant="body2" sx={{ fontFamily: mono ? "monospace" : undefined, wordBreak: "break-all" }}>
        {shown}
      </Typography>
      {copy ? (
        <Tooltip title={`Copy ${label}`}>
          <Button size="small" startIcon={<ContentCopyIcon />} onClick={() => void copyValue()}>
            Copy
          </Button>
        </Tooltip>
      ) : null}
    </Stack>
  );
}

// ---------------------------------------------------------------------------
// The sdc1. app tokens: issue (value exactly once), list, revoke
// ---------------------------------------------------------------------------

function AppTokensSection({ applicationId }: { applicationId: string }) {
  const toast = useToast();
  const queryClient = useQueryClient();
  const [issueOpen, setIssueOpen] = useState(false);
  const [revokeTarget, setRevokeTarget] = useState<OtaAppToken | null>(null);
  const [issuedValue, setIssuedValue] = useState<{ name: string; value: string } | null>(null);

  const tokens = useInfiniteQuery({
    queryKey: ["ota-app-tokens", applicationId],
    queryFn: ({ pageParam }) =>
      listOtaAppTokens(applicationId, { limit: 50, cursor: pageParam }),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (lastPage) => lastPage.nextCursor,
  });
  const rows = useMemo(() => tokens.data?.pages.flatMap((page) => page.items) ?? [], [tokens.data]);

  function invalidate() {
    void queryClient.invalidateQueries({ queryKey: ["ota-app-tokens", applicationId] });
  }

  const issue = useMutation({
    mutationFn: (name: string) => issueOtaAppToken(applicationId, name),
    onSuccess: (issued) => {
      invalidate();
      setIssuedValue({ name: issued.token.name, value: issued.value });
    },
    onError: toast.showError,
  });

  const revoke = useMutation({
    mutationFn: (token: OtaAppToken) => revokeOtaAppToken(token.id, applicationId),
    onSuccess: () => {
      invalidate();
      toast.showSuccess("App token revoked — devices presenting it now fail the check");
    },
    onError: toast.showError,
  });

  return (
    <Paper variant="outlined" sx={{ padding: 2 }} data-testid="ota-app-tokens">
      <Stack direction="row" spacing={2} sx={{ alignItems: "center", marginBottom: 1 }}>
        <Typography variant="h6" sx={{ flexGrow: 1 }}>
          Device app tokens
        </Typography>
        <Button size="small" variant="contained" onClick={() => setIssueOpen(true)} data-testid="issue-app-token">
          Issue token
        </Button>
      </Stack>
      <Typography color="text.secondary" sx={{ marginBottom: 1 }}>
        The sdc1. identifier embedded in the shipped app (D3): an identifier, not a secret — it
        enables rate limiting and unambiguous app resolution at the device check.
      </Typography>
      {tokens.isError ? (
        <Alert severity="error">Failed to load the app tokens.</Alert>
      ) : rows.length === 0 ? (
        <Typography color="text.secondary" data-testid="ota-app-tokens-empty">
          No app tokens issued yet.
        </Typography>
      ) : (
        <Stack spacing={0.5} data-testid="ota-app-token-rows">
          {rows.map((token) => (
            <Stack
              key={token.id}
              direction="row"
              spacing={1}
              sx={{ alignItems: "center", flexWrap: "wrap" }}
              data-testid={`ota-app-token-${token.id}`}
            >
              <Chip
                size="small"
                color={token.revoked ? "default" : "success"}
                variant={token.revoked ? "outlined" : "filled"}
                label={token.revoked ? "revoked" : "active"}
              />
              <Typography variant="body2">{token.name}</Typography>
              {token.revokedAt !== null ? (
                <Typography variant="body2" color="text.secondary">
                  revoked {new Date(token.revokedAt).toLocaleString()}
                </Typography>
              ) : null}
              {!token.revoked ? (
                <Button size="small" color="error" onClick={() => setRevokeTarget(token)}>
                  Revoke
                </Button>
              ) : null}
            </Stack>
          ))}
        </Stack>
      )}

      <NameDialog
        open={issueOpen}
        title="Issue device app token"
        helper="The human name the registry distinguishes tokens by (1-200 characters)."
        submitLabel="Issue"
        submitTestId="confirm-issue-app-token"
        onClose={() => setIssueOpen(false)}
        onSubmit={async (name) => {
          await issue.mutateAsync(name);
        }}
      />

      <Dialog open={revokeTarget !== null} onClose={() => setRevokeTarget(null)} maxWidth="sm" fullWidth>
        <DialogTitle>Revoke app token?</DialogTitle>
        <DialogContent>
          <Typography>
            Revoking <strong>{revokeTarget?.name}</strong> fails every device check presenting it.
            The value itself is unrecoverable — only its SHA-256 was ever stored.
          </Typography>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setRevokeTarget(null)}>Cancel</Button>
          <Button
            color="error"
            variant="contained"
            data-testid="confirm-revoke-app-token"
            onClick={() => {
              if (revokeTarget) revoke.mutate(revokeTarget);
              setRevokeTarget(null);
            }}
          >
            Revoke
          </Button>
        </DialogActions>
      </Dialog>

      {/* The value shows exactly once, here (the registry stores only
          its SHA-256); closing the dialog discards it forever. */}
      <Dialog open={issuedValue !== null} onClose={() => setIssuedValue(null)} fullWidth maxWidth="sm">
        <DialogTitle>Copy the app token now</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ marginTop: 1 }}>
            <Alert severity="warning" data-testid="app-token-once">
              This value is shown exactly once and cannot be recovered — the registry stores only
              its SHA-256. Embed it in the shipped app (D3) before closing.
            </Alert>
            <Stack direction="row" spacing={1} sx={{ alignItems: "center" }}>
              <Typography variant="body2" sx={{ fontFamily: "monospace", wordBreak: "break-all", flexGrow: 1 }}>
                {issuedValue?.value}
              </Typography>
              <Button
                size="small"
                startIcon={<ContentCopyIcon />}
                onClick={async () => {
                  if (issuedValue === null) return;
                  try {
                    await navigator.clipboard.writeText(issuedValue.value);
                    toast.showSuccess("App token copied");
                  } catch {
                    toast.showError(new Error("clipboard-unavailable"));
                  }
                }}
                data-testid="copy-app-token"
              >
                Copy
              </Button>
            </Stack>
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setIssuedValue(null)} data-testid="app-token-dismiss">
            I have stored it
          </Button>
        </DialogActions>
      </Dialog>
    </Paper>
  );
}

// ---------------------------------------------------------------------------
// The alert-rule store (the dashboard feed's source, S16-11)
// ---------------------------------------------------------------------------

function AlertRulesSection({ applicationId, bundles }: { applicationId: string; bundles: OtaBundle[] }) {
  const toast = useToast();
  const queryClient = useQueryClient();
  const [editorTarget, setEditorTarget] = useState<{ rule: OtaAlertRule | null } | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<OtaAlertRule | null>(null);

  const rules = useInfiniteQuery({
    queryKey: ["ota-alert-rules", applicationId],
    queryFn: ({ pageParam }) => listOtaAlertRules(applicationId, { limit: 50, cursor: pageParam }),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (lastPage) => lastPage.nextCursor,
  });
  const rows = useMemo(() => rules.data?.pages.flatMap((page) => page.items) ?? [], [rules.data]);

  function invalidate() {
    void queryClient.invalidateQueries({ queryKey: ["ota-alert-rules", applicationId] });
  }

  const remove = useMutation({
    mutationFn: (rule: OtaAlertRule) => deleteOtaAlertRule(rule.id, applicationId),
    onSuccess: () => {
      invalidate();
      toast.showSuccess("Alert rule deleted");
    },
    onError: toast.showError,
  });

  return (
    <Paper variant="outlined" sx={{ padding: 2 }} data-testid="ota-alert-rules">
      <Stack direction="row" spacing={2} sx={{ alignItems: "center", marginBottom: 1 }}>
        <Typography variant="h6" sx={{ flexGrow: 1 }}>
          OTA alert rules
        </Typography>
        <Button size="small" variant="contained" onClick={() => setEditorTarget({ rule: null })} data-testid="create-alert-rule">
          New rule
        </Button>
      </Stack>
      <Typography color="text.secondary" sx={{ marginBottom: 1 }}>
        Each rule watches one OTA metric over a window; firings land on the tenant&apos;s OTA
        dashboard feed (S16-11).
      </Typography>
      {rules.isError ? (
        <Alert severity="error">Failed to load the alert rules.</Alert>
      ) : rows.length === 0 ? (
        <Typography color="text.secondary" data-testid="ota-alert-rules-empty">
          No alert rules yet.
        </Typography>
      ) : (
        <Stack spacing={0.5} data-testid="ota-alert-rule-rows">
          {rows.map((rule) => (
            <Stack
              key={rule.id}
              direction="row"
              spacing={1}
              sx={{ alignItems: "center", flexWrap: "wrap" }}
              data-testid={`ota-alert-rule-${rule.id}`}
            >
              <Chip size="small" color={OTA_SEVERITY_COLORS[rule.severity]} label={rule.severity.toLowerCase()} />
              <Typography variant="body2" sx={{ fontFamily: "monospace" }}>
                {alertRuleExpression(rule)}
              </Typography>
              {rule.otaBundleId !== null ? (
                <Chip
                  size="small"
                  variant="outlined"
                  label={`bundle ${bundles.find((bundle) => bundle.id === rule.otaBundleId)?.bundleVersion ?? "scoped"}`}
                />
              ) : (
                <Chip size="small" variant="outlined" label="application-wide" />
              )}
              {rule.standingFiring !== null ? (
                <Chip size="small" color={OTA_SEVERITY_COLORS[rule.severity]} variant="filled" label="firing" />
              ) : null}
              <Button size="small" onClick={() => setEditorTarget({ rule })}>
                Edit
              </Button>
              <Button size="small" color="error" onClick={() => setDeleteTarget(rule)}>
                Delete
              </Button>
            </Stack>
          ))}
        </Stack>
      )}

      {editorTarget !== null ? (
        <AlertRuleDialog
          applicationId={applicationId}
          bundles={bundles}
          rule={editorTarget.rule}
          onClose={() => setEditorTarget(null)}
          onSaved={() => {
            setEditorTarget(null);
            invalidate();
          }}
        />
      ) : null}

      <Dialog open={deleteTarget !== null} onClose={() => setDeleteTarget(null)} maxWidth="sm" fullWidth>
        <DialogTitle>Delete alert rule?</DialogTitle>
        <DialogContent>
          <Typography>
            {deleteTarget ? alertRuleExpression(deleteTarget) : ""} — deleting stops the watch;
            standing firings resolve.
          </Typography>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setDeleteTarget(null)}>Cancel</Button>
          <Button
            color="error"
            variant="contained"
            data-testid="confirm-delete-alert-rule"
            onClick={() => {
              if (deleteTarget) remove.mutate(deleteTarget);
              setDeleteTarget(null);
            }}
          >
            Delete
          </Button>
        </DialogActions>
      </Dialog>
    </Paper>
  );
}

interface AlertRuleDialogProps {
  applicationId: string;
  bundles: OtaBundle[];
  rule: OtaAlertRule | null;
  onClose: () => void;
  onSaved: () => void;
}

/** The rule editor (create and the PUT replace share it): the watch —
 * metric, comparator, threshold, window, severity — with the optional
 * bundle scope (an unscoped rule watches the application). */
function AlertRuleDialog({ applicationId, bundles, rule, onClose, onSaved }: AlertRuleDialogProps) {
  const toast = useToast();
  const [metric, setMetric] = useState<OtaAlertRuleInput["metric"]>(rule?.metric ?? "OTA_CRASH_FREE_RATE");
  const [comparator, setComparator] = useState<OtaAlertRuleInput["comparator"]>(rule?.comparator ?? "LT");
  const [threshold, setThreshold] = useState(rule ? String(rule.threshold) : "0.995");
  const [windowMinutes, setWindowMinutes] = useState(rule ? String(rule.windowMinutes) : "15");
  const [severity, setSeverity] = useState<OtaAlertRuleInput["severity"]>(rule?.severity ?? "WARNING");
  const [bundleScope, setBundleScope] = useState<string | null>(rule?.otaBundleId ?? null);
  const [submitting, setSubmitting] = useState(false);

  const thresholdValue = Number(threshold);
  const windowValue = Number(windowMinutes);
  const thresholdError =
    threshold.trim() !== "" && Number.isFinite(thresholdValue) ? undefined : "The threshold is a number";
  const windowError =
    windowMinutes.trim() !== "" && Number.isInteger(windowValue) && windowValue >= 1 && windowValue <= 10_080
      ? undefined
      : "The window is 1-10080 minutes";

  async function submit() {
    setSubmitting(true);
    try {
      const input: OtaAlertRuleInput = {
        applicationId,
        ...(bundleScope !== null ? { otaBundleId: bundleScope } : {}),
        metric,
        comparator,
        threshold: thresholdValue,
        windowMinutes: windowValue,
        severity,
      };
      if (rule === null) {
        await createOtaAlertRule(input);
        toast.showSuccess("Alert rule created");
      } else {
        await updateOtaAlertRule(rule.id, input);
        toast.showSuccess("Alert rule updated");
      }
      onSaved();
    } catch (error) {
      toast.showError(error);
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm" data-testid="alert-rule-dialog">
      <DialogTitle>{rule === null ? "New OTA alert rule" : `Edit rule`}</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ marginTop: 1 }}>
          <TextField
            select
            label="Metric"
            value={metric}
            onChange={(event) => setMetric(event.target.value as OtaAlertRuleInput["metric"])}
            slotProps={{ htmlInput: { "data-testid": "rule-metric" } }}
          >
            {OTA_ALERT_METRICS.map((option) => (
              <MenuItem key={option.value} value={option.value}>
                {option.label}
              </MenuItem>
            ))}
          </TextField>
          <Stack direction="row" spacing={2}>
            <TextField
              select
              label="Comparator"
              value={comparator}
              onChange={(event) => setComparator(event.target.value as OtaAlertRuleInput["comparator"])}
              sx={{ minWidth: 120 }}
              slotProps={{ htmlInput: { "data-testid": "rule-comparator" } }}
            >
              {OTA_ALERT_COMPARATORS.map((option) => (
                <MenuItem key={option.value} value={option.value}>
                  {option.label}
                </MenuItem>
              ))}
            </TextField>
            <TextField
              label="Threshold"
              value={threshold}
              onChange={(event) => setThreshold(event.target.value)}
              error={thresholdError !== undefined}
              helperText={thresholdError ?? "Counts are integers; rates are 0..1 fractions"}
              sx={{ flexGrow: 1 }}
              slotProps={{ htmlInput: { "data-testid": "rule-threshold" } }}
            />
          </Stack>
          <Stack direction="row" spacing={2}>
            <TextField
              label="Window (minutes)"
              value={windowMinutes}
              onChange={(event) => setWindowMinutes(event.target.value.replace(/[^0-9]/g, ""))}
              error={windowError !== undefined}
              helperText={windowError ?? "1-10080"}
              sx={{ flexGrow: 1 }}
              slotProps={{ htmlInput: { "data-testid": "rule-window" } }}
            />
            <TextField
              select
              label="Severity"
              value={severity}
              onChange={(event) => setSeverity(event.target.value as OtaAlertRuleInput["severity"])}
              sx={{ minWidth: 140 }}
              slotProps={{ htmlInput: { "data-testid": "rule-severity" } }}
            >
              {OTA_ALERT_SEVERITIES.map((option) => (
                <MenuItem key={option} value={option}>
                  {option.toLowerCase()}
                </MenuItem>
              ))}
            </TextField>
          </Stack>
          <Autocomplete
            options={bundles}
            getOptionLabel={(option) => option.bundleVersion}
            isOptionEqualToValue={(option, value) => option.id === value.id}
            value={bundles.find((bundle) => bundle.id === bundleScope) ?? null}
            onChange={(_event, value) => setBundleScope(value?.id ?? null)}
            renderInput={(params) => (
              <TextField {...params} label="Bundle scope (optional)" helperText="Empty watches the whole application" />
            )}
            data-testid="rule-bundle-scope"
          />
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button
          variant="contained"
          disabled={submitting || thresholdError !== undefined || windowError !== undefined}
          onClick={() => void submit()}
          data-testid="rule-submit"
        >
          {submitting ? "Saving..." : rule === null ? "Create" : "Save"}
        </Button>
      </DialogActions>
    </Dialog>
  );
}

interface NameDialogProps {
  open: boolean;
  title: string;
  helper: string;
  submitLabel: string;
  submitTestId: string;
  onClose: () => void;
  onSubmit: (name: string) => Promise<void>;
}

function NameDialog({ open, title, helper, submitLabel, submitTestId, onClose, onSubmit }: NameDialogProps) {
  const toast = useToast();
  const [name, setName] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const trimmed = name.trim();
  const error = trimmed === "" ? "A name is required" : trimmed.length > 200 ? "Max 200 characters" : undefined;

  async function submit() {
    setSubmitting(true);
    try {
      await onSubmit(trimmed);
      onClose();
      setName("");
    } catch (failure) {
      toast.showError(failure);
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>{title}</DialogTitle>
      <DialogContent>
        <TextField
          autoFocus
          fullWidth
          label="Name"
          value={name}
          onChange={(event) => setName(event.target.value)}
          error={error !== undefined}
          helperText={error ?? helper}
          sx={{ marginTop: 1 }}
          slotProps={{ htmlInput: { "data-testid": "app-token-name" } }}
        />
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button
          variant="contained"
          disabled={submitting || error !== undefined}
          onClick={() => void submit()}
          data-testid={submitTestId}
        >
          {submitting ? "Working..." : submitLabel}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
