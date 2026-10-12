/**
 * SIEM audit-stream exports editor and delivery log (S14-05, issue
 * #71) — the console surface of the compliance feed: the
 * organization's exports (HTTPS collectors in the SHIPPERDOG_JSON or
 * SPLUNK_HEC shape, or bucket batches with their signed manifests),
 * each editable through the dialog (destination, format, event-type
 * filter, secret-reference rotation, the enabled switch), each
 * verifiable with the test event (one synthetic envelope through the
 * real delivery pipeline), the delivery ledger beside every HTTPS
 * export — SCHEDULED buffering, DELIVERED history, the FAILED
 * dead-letter with its replay action (AC3) — and the bucket
 * export's batch history with its completeness rollup (AC2).
 * Secrets are secret-manager references only; the console never
 * sees material. The surface is the organization administrator's
 * alone (AC5); the server enforces it regardless.
 */
import AddIcon from "@mui/icons-material/Add";
import DeleteIcon from "@mui/icons-material/Delete";
import {
  Alert,
  Box,
  Button,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  FormControlLabel,
  LinearProgress,
  MenuItem,
  Paper,
  Stack,
  Switch,
  TextField,
  Typography,
} from "@mui/material";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import {
  createAuditStreamExport,
  deleteAuditStreamExport,
  fetchAuditStreamBatches,
  fetchAuditStreamDeliveries,
  fetchAuditStreamExports,
  replayAuditStreamDeadLetter,
  testAuditStreamExport,
  updateAuditStreamExport,
  type AuditStreamExport,
  type AuditStreamKind,
} from "../api/auditStreams";
import { useAuth } from "../auth/AuthContext";
import { useToast } from "../components/ToastContext";

/** The editor's draft: everything the dialog can write. */
interface Draft {
  kind: AuditStreamKind;
  format: string;
  eventTypes: string;
  destinationUrl: string;
  bucketUri: string;
  signingSecretRef: string;
  authHeaderName: string;
  authSecretRef: string;
  initialWatermark: string;
  enabled: boolean;
}

function draftOf(exportRow: AuditStreamExport | null): Draft {
  return {
    kind: exportRow?.kind ?? "HTTPS",
    format: exportRow?.format ?? "SHIPPERDOG_JSON",
    eventTypes: (exportRow?.eventTypes ?? []).join(", "),
    destinationUrl: exportRow?.destinationUrl ?? "",
    bucketUri: exportRow?.bucketUri ?? "",
    signingSecretRef: exportRow?.signingSecretRef ?? "",
    authHeaderName: exportRow?.authHeaderName ?? "",
    authSecretRef: exportRow?.authSecretRef ?? "",
    initialWatermark: "",
    enabled: exportRow?.enabled ?? true,
  };
}

function destinationOf(exportRow: AuditStreamExport): string {
  if (exportRow.kind === "BUCKET") {
    return exportRow.bucketUri ?? "";
  }
  return exportRow.destinationUrl ?? "";
}

export default function AuditStreamsPage() {
  const toast = useToast();
  const queryClient = useQueryClient();
  const { user } = useAuth();
  const orgId = user?.orgId ?? null;
  const organizationId = orgId as string;
  const [dialogOpen, setDialogOpen] = useState(false);
  const [editing, setEditing] = useState<AuditStreamExport | null>(null);
  const [draft, setDraft] = useState<Draft>(draftOf(null));
  const [statusFilter, setStatusFilter] = useState<string>("");
  const [selected, setSelected] = useState<AuditStreamExport | null>(null);

  const exportsQuery = useQuery({
    queryKey: ["audit-stream-exports", orgId],
    queryFn: () => fetchAuditStreamExports(organizationId),
    enabled: orgId !== null,
  });

  const deliveriesQuery = useQuery({
    queryKey: ["audit-stream-deliveries", orgId, selected?.id, statusFilter],
    queryFn: () =>
      fetchAuditStreamDeliveries(
        organizationId,
        selected?.id ?? "",
        statusFilter === "" ? undefined : (statusFilter as "SCHEDULED" | "DELIVERED" | "FAILED"),
      ),
    enabled: selected !== null && selected.kind === "HTTPS",
  });

  const batchesQuery = useQuery({
    queryKey: ["audit-stream-batches", orgId, selected?.id],
    queryFn: () => fetchAuditStreamBatches(organizationId, selected?.id ?? ""),
    enabled: selected !== null && selected.kind === "BUCKET",
  });

  const invalidate = () => {
    queryClient.invalidateQueries({ queryKey: ["audit-stream-exports", orgId] });
    if (selected !== null) {
      queryClient.invalidateQueries({ queryKey: ["audit-stream-deliveries", organizationId] });
      queryClient.invalidateQueries({ queryKey: ["audit-stream-batches", organizationId] });
    }
  };

  const saveMutation = useMutation({
    mutationFn: () => {
      const eventTypes = draft.eventTypes
        .split(",")
        .map((entry) => entry.trim())
        .filter((entry) => entry.length > 0);
      if (editing === null) {
        return createAuditStreamExport(organizationId, {
          kind: draft.kind,
          format: draft.kind === "HTTPS" ? (draft.format as "SHIPPERDOG_JSON" | "SPLUNK_HEC") : undefined,
          eventTypes,
          destinationUrl: draft.kind === "HTTPS" ? draft.destinationUrl.trim() : undefined,
          bucketUri: draft.kind === "BUCKET" ? draft.bucketUri.trim() : undefined,
          signingSecretRef: draft.signingSecretRef.trim(),
          authHeaderName:
            draft.kind === "HTTPS" && draft.authHeaderName.trim().length > 0
              ? draft.authHeaderName.trim()
              : undefined,
          authSecretRef:
            draft.kind === "HTTPS" && draft.authSecretRef.trim().length > 0
              ? draft.authSecretRef.trim()
              : undefined,
          initialWatermark:
            draft.kind === "BUCKET" && draft.initialWatermark.trim().length > 0
              ? draft.initialWatermark.trim()
              : undefined,
        });
      }
      return updateAuditStreamExport(organizationId, editing.id, {
        format: draft.kind === "HTTPS" ? (draft.format as "SHIPPERDOG_JSON" | "SPLUNK_HEC") : undefined,
        eventTypes,
        destinationUrl: draft.kind === "HTTPS" ? draft.destinationUrl.trim() : undefined,
        bucketUri: draft.kind === "BUCKET" ? draft.bucketUri.trim() : undefined,
        signingSecretRef: draft.signingSecretRef.trim().length > 0 ? draft.signingSecretRef.trim() : undefined,
        authHeaderName:
          draft.kind === "HTTPS" && draft.authHeaderName.trim().length > 0
            ? draft.authHeaderName.trim()
            : undefined,
        authSecretRef:
          draft.kind === "HTTPS" && draft.authSecretRef.trim().length > 0
              ? draft.authSecretRef.trim()
              : undefined,
        enabled: draft.enabled,
      });
    },
    onSuccess: () => {
      toast.showSuccess("Export saved");
      setDialogOpen(false);
      invalidate();
    },
    onError: (error) => toast.showError(error),
  });

  const deleteMutation = useMutation({
    mutationFn: (exportId: string) => deleteAuditStreamExport(organizationId, exportId),
    onSuccess: () => {
      toast.showSuccess("Export deleted");
      setSelected(null);
      invalidate();
    },
    onError: (error) => toast.showError(error),
  });

  const testMutation = useMutation({
    mutationFn: (exportId: string) => testAuditStreamExport(organizationId, exportId),
    onSuccess: (result) => {
      if (result.delivered) {
        toast.showSuccess("Test event delivered");
      } else {
        toast.showError(new Error(result.error ?? "the collector refused the test event"));
      }
    },
    onError: (error) => toast.showError(error),
  });

  const replayMutation = useMutation({
    mutationFn: (exportId: string) => replayAuditStreamDeadLetter(organizationId, exportId),
    onSuccess: (result) => {
      toast.showSuccess(`${result.replayed} dead-lettered deliveries returned to the retry schedule`);
      invalidate();
    },
    onError: (error) => toast.showError(error),
  });

  const openCreate = () => {
    setEditing(null);
    setDraft(draftOf(null));
    setDialogOpen(true);
  };

  const openEdit = (exportRow: AuditStreamExport) => {
    setEditing(exportRow);
    setDraft(draftOf(exportRow));
    setDialogOpen(true);
  };

  return (
    <Stack spacing={3}>
      <Stack direction="row" sx={{ justifyContent: "space-between", alignItems: "center" }}>
        <Typography variant="h4">SIEM audit streams</Typography>
        <Button
          variant="contained"
          startIcon={<AddIcon />}
          onClick={openCreate}
          data-testid="audit-stream-create"
        >
          New export
        </Button>
      </Stack>
      <Typography variant="body2" color="text.secondary">
        The compliance feed of docs/siem.md: audit events streamed to your SIEM in real time
        (HTTPS collectors, HMAC-signed) or in periodic bucket batches with signed manifests.
        Secrets are secret-manager references; the console never carries material.
      </Typography>

      {exportsQuery.isLoading ? <LinearProgress /> : null}
      {exportsQuery.isError ? (
        <Alert severity="error">The export listing failed to load.</Alert>
      ) : null}

      {(exportsQuery.data ?? []).map((exportRow) => (
        <Paper key={exportRow.id} sx={{ p: 2 }} data-testid={`audit-stream-${exportRow.id}`}>
          <Stack direction="row" spacing={2} sx={{ justifyContent: "space-between", alignItems: "flex-start" }}>
            <Stack spacing={1}>
              <Stack direction="row" spacing={1} sx={{ alignItems: "center" }}>
                <Chip label={exportRow.kind} size="small" color={exportRow.enabled ? "primary" : "default"} />
                {exportRow.format !== null ? <Chip label={exportRow.format} size="small" variant="outlined" /> : null}
                <Chip
                  label={exportRow.eventTypes.length === 0 ? "ALL events" : `${exportRow.eventTypes.length} event types`}
                  size="small"
                  variant="outlined"
                />
                {!exportRow.enabled ? <Chip label="parked" size="small" color="warning" /> : null}
              </Stack>
              <Typography variant="body1" data-testid={`audit-stream-destination-${exportRow.id}`}>
                {destinationOf(exportRow)}
              </Typography>
              <Typography variant="body2" color="text.secondary">
                {exportRow.kind === "BUCKET"
                  ? `${exportRow.batchesWritten} batches, ${exportRow.eventsExported} events exported (watermark ${exportRow.watermark ?? "not started"})`
                  : `signing ref ${exportRow.signingSecretRef}${exportRow.authHeaderName !== null ? `, auth header ${exportRow.authHeaderName}` : ""}`}
              </Typography>
            </Stack>
            <Stack direction="row" spacing={1}>
              <Button size="small" onClick={() => testMutation.mutate(exportRow.id)} data-testid={`audit-stream-test-${exportRow.id}`}>
                Test
              </Button>
              <Button size="small" onClick={() => openEdit(exportRow)}>
                Edit
              </Button>
              <Button size="small" onClick={() => setSelected(selected?.id === exportRow.id ? null : exportRow)}>
                {selected?.id === exportRow.id ? "Hide log" : exportRow.kind === "BUCKET" ? "Batches" : "Deliveries"}
              </Button>
              <Button
                size="small"
                color="error"
                startIcon={<DeleteIcon />}
                onClick={() => deleteMutation.mutate(exportRow.id)}
                aria-label={`Delete export ${exportRow.id}`}
              >
                Delete
              </Button>
            </Stack>
          </Stack>

          {selected?.id === exportRow.id && exportRow.kind === "HTTPS" ? (
            <Box sx={{ mt: 2 }}>
              <Stack direction="row" spacing={1} sx={{ mb: 1, alignItems: "center" }}>
                <TextField
                  select
                  size="small"
                  label="Status"
                  value={statusFilter}
                  onChange={(event) => setStatusFilter(event.target.value)}
                  sx={{ minWidth: 160 }}
                >
                  <MenuItem value="">All</MenuItem>
                  <MenuItem value="SCHEDULED">SCHEDULED</MenuItem>
                  <MenuItem value="DELIVERED">DELIVERED</MenuItem>
                  <MenuItem value="FAILED">FAILED</MenuItem>
                </TextField>
                <Button
                  size="small"
                  variant="outlined"
                  onClick={() => replayMutation.mutate(exportRow.id)}
                  data-testid={`audit-stream-replay-${exportRow.id}`}
                >
                  Replay dead-letter
                </Button>
              </Stack>
              {(deliveriesQuery.data ?? []).length === 0 ? (
                <Typography variant="body2" color="text.secondary">
                  No deliveries match.
                </Typography>
              ) : (
                (deliveriesQuery.data ?? []).map((delivery) => (
                  <Stack
                    key={delivery.id}
                    direction="row"
                    spacing={2}
                    sx={{ py: 0.5, alignItems: "center", borderBottom: "1px solid", borderColor: "divider" }}
                    data-testid={`audit-stream-delivery-${delivery.id}`}
                  >
                    <Chip
                      size="small"
                      label={delivery.status}
                      color={delivery.status === "DELIVERED" ? "success" : delivery.status === "FAILED" ? "error" : "default"}
                    />
                    <Typography variant="body2" sx={{ minWidth: 90 }}>
                      {delivery.attempts} attempt{delivery.attempts === 1 ? "" : "s"}
                    </Typography>
                    <Typography variant="body2" color="text.secondary" sx={{ flex: 1 }}>
                      {delivery.lastError ?? delivery.deliveredAt ?? delivery.createdAt}
                    </Typography>
                  </Stack>
                ))
              )}
            </Box>
          ) : null}

          {selected?.id === exportRow.id && exportRow.kind === "BUCKET" ? (
            <Box sx={{ mt: 2 }}>
              {(batchesQuery.data ?? []).length === 0 ? (
                <Typography variant="body2" color="text.secondary">
                  No batches written yet.
                </Typography>
              ) : (
                (batchesQuery.data ?? []).map((batch) => (
                  <Stack
                    key={batch.id}
                    direction="row"
                    spacing={2}
                    sx={{ py: 0.5, borderBottom: "1px solid", borderColor: "divider" }}
                    data-testid={`audit-stream-batch-${batch.id}`}
                  >
                    <Chip size="small" label={`seq ${batch.seq}`} />
                    <Typography variant="body2" sx={{ minWidth: 70 }}>
                      {batch.eventCount} events
                    </Typography>
                    <Typography variant="body2" color="text.secondary" sx={{ flex: 1 }} noWrap>
                      {batch.objectUri}
                    </Typography>
                    <Typography variant="body2" color="text.secondary" noWrap sx={{ maxWidth: 220 }}>
                      {batch.batchSha256.slice(0, 12)}...
                    </Typography>
                  </Stack>
                ))
              )}
            </Box>
          ) : null}
        </Paper>
      ))}

      {(exportsQuery.data ?? []).length === 0 && !exportsQuery.isLoading ? (
        <Paper sx={{ p: 3 }}>
          <Typography variant="body1">No SIEM exports configured.</Typography>
          <Typography variant="body2" color="text.secondary">
            The audit trail stays local until an export exists — see docs/siem.md for the envelope
            schema and the HMAC verification recipe.
          </Typography>
        </Paper>
      ) : null}

      <Dialog open={dialogOpen} onClose={() => setDialogOpen(false)} fullWidth maxWidth="sm">
        <DialogTitle>{editing === null ? "New SIEM export" : "Edit SIEM export"}</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ mt: 1 }}>
            <TextField
              select
              label="Kind"
              value={draft.kind}
              onChange={(event) => setDraft({ ...draft, kind: event.target.value as AuditStreamKind })}
              disabled={editing !== null}
            >
              <MenuItem value="HTTPS">HTTPS collector (real time)</MenuItem>
              <MenuItem value="BUCKET">Bucket batch (periodic)</MenuItem>
            </TextField>
            {draft.kind === "HTTPS" ? (
              <>
                <TextField
                  select
                  label="Format"
                  value={draft.format}
                  onChange={(event) => setDraft({ ...draft, format: event.target.value })}
                >
                  <MenuItem value="SHIPPERDOG_JSON">ShipperDog JSON envelope</MenuItem>
                  <MenuItem value="SPLUNK_HEC">Splunk HEC event</MenuItem>
                </TextField>
                <TextField
                  label="Collector URL (https://)"
                  value={draft.destinationUrl}
                  onChange={(event) => setDraft({ ...draft, destinationUrl: event.target.value })}
                />
              </>
            ) : (
              <>
                <TextField
                  label="Bucket URI (gs://bucket/prefix)"
                  value={draft.bucketUri}
                  onChange={(event) => setDraft({ ...draft, bucketUri: event.target.value })}
                />
                <TextField
                  label="Initial watermark (ISO-8601, empty = start at now; 1970-01-01T00:00:00Z backfills all)"
                  value={draft.initialWatermark}
                  onChange={(event) => setDraft({ ...draft, initialWatermark: event.target.value })}
                  disabled={editing !== null}
                />
              </>
            )}
            <TextField
              label="Event-type filter (comma-separated audit event types, empty = ALL)"
              value={draft.eventTypes}
              onChange={(event) => setDraft({ ...draft, eventTypes: event.target.value })}
            />
            <TextField
              label="Signing secret reference (secret manager)"
              value={draft.signingSecretRef}
              onChange={(event) => setDraft({ ...draft, signingSecretRef: event.target.value })}
              helperText={editing !== null ? "Supply a new reference to rotate" : undefined}
            />
            {draft.kind === "HTTPS" ? (
              <>
                <TextField
                  label="Auth header name (optional, e.g. Authorization)"
                  value={draft.authHeaderName}
                  onChange={(event) => setDraft({ ...draft, authHeaderName: event.target.value })}
                />
                <TextField
                  label="Auth secret reference (optional; resolves to the header value, e.g. the HEC token)"
                  value={draft.authSecretRef}
                  onChange={(event) => setDraft({ ...draft, authSecretRef: event.target.value })}
                />
              </>
            ) : null}
            <FormControlLabel
              control={<Switch checked={draft.enabled} onChange={(event) => setDraft({ ...draft, enabled: event.target.checked })} />}
              label="Enabled"
            />
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setDialogOpen(false)}>Cancel</Button>
          <Button variant="contained" onClick={() => saveMutation.mutate()} data-testid="audit-stream-save">
            {editing === null ? "Create" : "Save"}
          </Button>
        </DialogActions>
      </Dialog>
    </Stack>
  );
}
