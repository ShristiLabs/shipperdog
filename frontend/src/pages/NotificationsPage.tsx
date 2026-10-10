/**
 * Notification subscriptions editor and delivery log (S12-01, AC4/AC5)
 * — the console surface of FR-023's routing table: the organization's
 * subscriptions (event names from the frozen vocabulary x channel x
 * recipients), each editable through the dialog (reroute events,
 * change recipients, rotate the webhook secret, park with the enabled
 * switch), and the delivery ledger beside them — every attempt with
 * its status and last error, the FAILED rows the integrator works.
 * Client-side validation refuses the obvious shapes (no events, no
 * recipients on in-app/email, no URL/secret on webhook); the server
 * re-validates everything (the frozen vocabulary is the catalog) and
 * refusals surface the problem+json code — the console is never the
 * gate.
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
  createSubscription,
  deleteSubscription,
  fetchDeliveries,
  fetchSubscriptions,
  updateSubscription,
  type DeliveryRow,
  type NotificationChannel,
  type Subscription,
} from "../api/notifications";
import { useAuth } from "../auth/AuthContext";
import { useToast } from "../components/ToastContext";

/** The frozen event-name vocabulary the picker offers (docs/notifications.md). */
const EVENT_NAMES = [
  "ARTIFACT_UPLOADED",
  "ARTIFACT_VALIDATED",
  "ARTIFACT_FAILED",
  "TESTER_DOWNLOADED",
  "RELEASE_CREATED",
  "RELEASE_TRANSITIONED",
  "APPROVAL_REQUESTED",
  "RELEASE_APPROVED",
  "RELEASE_PROMOTED",
  "TESTER_INVITED",
  "FEEDBACK_RECEIVED",
  "FEEDBACK_MARKED_CRITICAL",
];

/** The recipient-role vocabulary the picker offers (the RBAC roles). */
const ROLES = [
  "ORG_ADMIN",
  "APP_OWNER",
  "DEVELOPER",
  "QA_ENGINEER",
  "RELEASE_MANAGER",
  "SECURITY_REVIEWER",
  "COMPLIANCE_REVIEWER",
];

/** The editor's draft: everything the dialog can write. */
interface Draft {
  eventTypes: string[];
  channel: NotificationChannel;
  recipientRoles: string;
  recipientUserIds: string;
  webhookUrl: string;
  webhookSecret: string;
  enabled: boolean;
}

function draftOf(subscription: Subscription | null): Draft {
  return {
    eventTypes: subscription?.eventTypes ?? ["RELEASE_PROMOTED"],
    channel: subscription?.channel ?? "IN_APP",
    recipientRoles: (subscription?.recipientRoles ?? []).join(", "),
    recipientUserIds: (subscription?.recipientUserIds ?? []).join(", "),
    webhookUrl: subscription?.webhookUrl ?? "",
    webhookSecret: "",
    enabled: subscription?.enabled ?? true,
  };
}

function recipientSummary(subscription: Subscription): string {
  if (subscription.channel === "WEBHOOK") {
    return subscription.webhookUrl ?? "";
  }
  const roles = subscription.recipientRoles;
  const users = subscription.recipientUserIds.length;
  const parts: string[] = [];
  if (roles.length > 0) parts.push(roles.map((role) => role.toLowerCase().replace(/_/g, " ")).join(", "));
  if (users > 0) parts.push(`${users} user${users === 1 ? "" : "s"}`);
  return parts.join(" + ");
}

function statusColor(status: DeliveryRow["status"]): "success" | "warning" | "error" {
  if (status === "DELIVERED") return "success";
  if (status === "SCHEDULED") return "warning";
  return "error";
}

export default function NotificationsPage() {
  const { user } = useAuth();
  const orgId = user?.orgId ?? null;
  const toast = useToast();
  const queryClient = useQueryClient();
  const [editorOpen, setEditorOpen] = useState(false);
  const [editing, setEditing] = useState<Subscription | null>(null);
  const [draft, setDraft] = useState<Draft>(draftOf(null));

  const subscriptions = useQuery({
    queryKey: ["notification-subscriptions", orgId],
    queryFn: () => fetchSubscriptions(orgId as string),
    enabled: orgId !== null,
  });

  const deliveries = useQuery({
    queryKey: ["notification-deliveries", orgId],
    queryFn: () => fetchDeliveries(orgId as string),
    enabled: orgId !== null,
  });

  async function refresh() {
    await queryClient.invalidateQueries({ queryKey: ["notification-subscriptions", orgId] });
    await queryClient.invalidateQueries({ queryKey: ["notification-deliveries", orgId] });
  }

  const save = useMutation({
    mutationFn: async () => {
      if (editing !== null) {
        return updateSubscription(orgId as string, editing.id, {
          eventTypes: draft.eventTypes,
          recipientRoles:
            draft.channel === "WEBHOOK"
              ? undefined
              : draft.recipientRoles
                  .split(",")
                  .map((role) => role.trim())
                  .filter((role) => role !== ""),
          recipientUserIds:
            draft.channel === "WEBHOOK"
              ? undefined
              : draft.recipientUserIds
                  .split(",")
                  .map((id) => id.trim())
                  .filter((id) => id !== ""),
          webhookUrl: draft.channel === "WEBHOOK" && draft.webhookUrl.trim() !== "" ? draft.webhookUrl.trim() : undefined,
          webhookSecret: draft.webhookSecret.trim() === "" ? undefined : draft.webhookSecret,
          enabled: draft.enabled,
        });
      }
      return createSubscription(orgId as string, {
        eventTypes: draft.eventTypes,
        channel: draft.channel,
        recipientRoles:
          draft.channel === "WEBHOOK"
            ? undefined
            : draft.recipientRoles
                .split(",")
                .map((role) => role.trim())
                .filter((role) => role !== ""),
        recipientUserIds:
          draft.channel === "WEBHOOK"
            ? undefined
            : draft.recipientUserIds
                .split(",")
                .map((id) => id.trim())
                .filter((id) => id !== ""),
        webhookUrl: draft.channel === "WEBHOOK" ? draft.webhookUrl.trim() : undefined,
        webhookSecret: draft.channel === "WEBHOOK" ? draft.webhookSecret : undefined,
      });
    },
    onSuccess: async () => {
      toast.showSuccess("Subscription saved");
      setEditorOpen(false);
      await refresh();
    },
    onError: (error) => {
      toast.showError(error);
    },
  });

  const remove = useMutation({
    mutationFn: (subscription: Subscription) => deleteSubscription(orgId as string, subscription.id),
    onSuccess: async () => {
      toast.showSuccess("Subscription deleted");
      await refresh();
    },
    onError: (error) => {
      toast.showError(error);
    },
  });

  function openCreate() {
    setEditing(null);
    setDraft(draftOf(null));
    setEditorOpen(true);
  }

  function openEdit(subscription: Subscription) {
    setEditing(subscription);
    setDraft(draftOf(subscription));
    setEditorOpen(true);
  }

  const recipientShapeValid =
    draft.channel === "WEBHOOK"
      ? draft.webhookUrl.trim() !== "" && (editing !== null || draft.webhookSecret.length >= 16)
      : draft.recipientRoles.trim() !== "" || draft.recipientUserIds.trim() !== "";

  return (
    <Stack spacing={2}>
      <Stack direction="row" spacing={2} sx={{ alignItems: "center" }}>
        <Typography variant="h5" sx={{ flexGrow: 1 }} data-testid="notifications-title">
          Notifications
        </Typography>
        <Button variant="contained" startIcon={<AddIcon />} onClick={openCreate} data-testid="subscription-create">
          New subscription
        </Button>
      </Stack>
      <Typography color="text.secondary">
        The routing table for domain events (the frozen vocabulary ships with the bus): which events go to
        which channel and recipients — the in-app inbox, email, or a signed webhook. The delivery log below
        shows every attempt; failed deliveries retry with backoff and stay visible when exhausted.
      </Typography>

      {subscriptions.isError ? (
        <Alert severity="error" data-testid="subscriptions-error">
          Failed to load the subscription routing.
        </Alert>
      ) : subscriptions.isPending ? (
        <LinearProgress aria-label="loading subscriptions" />
      ) : (
        <Stack spacing={1} data-testid="subscription-list">
          {subscriptions.data?.length === 0 ? (
            <Typography color="text.secondary" data-testid="subscription-empty">
              No subscriptions yet — create one to route events to your team.
            </Typography>
          ) : null}
          {subscriptions.data?.map((subscription) => (
            <Paper
              key={subscription.id}
              variant="outlined"
              sx={{ padding: 2 }}
              data-testid={`subscription-${subscription.id}`}
            >
              <Stack direction="row" spacing={2} sx={{ alignItems: "center", flexWrap: "wrap" as const }}>
                <Chip
                  size="small"
                  label={subscription.channel === "IN_APP" ? "in-app" : subscription.channel.toLowerCase()}
                  variant="outlined"
                />
                <Typography variant="body2" sx={{ fontFamily: "monospace" }}>
                  {subscription.eventTypes.join(", ")}
                </Typography>
                <Typography variant="body2" color="text.secondary">
                  {recipientSummary(subscription)}
                </Typography>
                <Chip
                  size="small"
                  variant="outlined"
                  label={subscription.enabled ? "enabled" : "parked"}
                  color={subscription.enabled ? "default" : "warning"}
                />
                <Box sx={{ flexGrow: 1 }} />
                <Button
                  size="small"
                  onClick={() => openEdit(subscription)}
                  data-testid={`subscription-edit-${subscription.id}`}
                >
                  Edit
                </Button>
                <Button
                  size="small"
                  color="error"
                  startIcon={<DeleteIcon />}
                  onClick={() => remove.mutate(subscription)}
                  data-testid={`subscription-delete-${subscription.id}`}
                >
                  Delete
                </Button>
              </Stack>
            </Paper>
          ))}
        </Stack>
      )}

      <Typography variant="h6" sx={{ marginTop: 2 }} data-testid="deliveries-title">
        Delivery log
      </Typography>
      {deliveries.isError ? (
        <Alert severity="error" data-testid="deliveries-error">
          Failed to load the delivery log.
        </Alert>
      ) : deliveries.isPending ? (
        <LinearProgress aria-label="loading deliveries" />
      ) : (
        <Stack spacing={1} data-testid="delivery-list">
          {deliveries.data?.length === 0 ? (
            <Typography color="text.secondary" data-testid="deliveries-empty">
              No deliveries yet.
            </Typography>
          ) : null}
          {deliveries.data?.slice(0, 20).map((delivery) => (
            <Paper key={delivery.id} variant="outlined" sx={{ padding: 1.5 }} data-testid={`delivery-${delivery.id}`}>
              <Stack direction="row" spacing={2} sx={{ alignItems: "center", flexWrap: "wrap" as const }}>
                <Chip size="small" label={delivery.status} color={statusColor(delivery.status)} variant="outlined" />
                <Typography variant="body2" sx={{ fontFamily: "monospace" }}>
                  {delivery.eventName}
                </Typography>
                <Typography variant="body2" color="text.secondary">
                  {delivery.channel.toLowerCase()} &rarr; {delivery.recipientKey}
                </Typography>
                <Typography variant="body2" color="text.secondary">
                  {delivery.attempts} attempt{delivery.attempts === 1 ? "" : "s"}
                </Typography>
                {delivery.lastError ? (
                  <Typography variant="body2" color="error" sx={{ maxWidth: 480, overflowWrap: "anywhere" }}>
                    {delivery.lastError}
                  </Typography>
                ) : null}
              </Stack>
            </Paper>
          ))}
        </Stack>
      )}

      <Dialog open={editorOpen} onClose={() => setEditorOpen(false)} fullWidth maxWidth="sm">
        <DialogTitle>{editing === null ? "New subscription" : "Edit subscription"}</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ marginTop: 1 }}>
            <TextField
              select
              slotProps={{ select: { multiple: true } }}
              label="Events (the frozen vocabulary)"
              value={draft.eventTypes}
              onChange={(event) =>
                setDraft({ ...draft, eventTypes: event.target.value as unknown as string[] })
              }
              data-testid="subscription-events"
            >
              {EVENT_NAMES.map((name) => (
                <MenuItem key={name} value={name}>
                  {name}
                </MenuItem>
              ))}
            </TextField>
            {editing === null ? (
              <TextField
                select
                label="Channel"
                value={draft.channel}
                onChange={(event) => setDraft({ ...draft, channel: event.target.value as NotificationChannel })}
                data-testid="subscription-channel"
              >
                <MenuItem value="IN_APP">In-app inbox</MenuItem>
                <MenuItem value="EMAIL">Email</MenuItem>
                <MenuItem value="WEBHOOK">Webhook (signed)</MenuItem>
              </TextField>
            ) : null}
            {draft.channel === "WEBHOOK" ? (
              <>
                <TextField
                  label="Webhook URL (https://)"
                  value={draft.webhookUrl}
                  onChange={(event) => setDraft({ ...draft, webhookUrl: event.target.value })}
                  data-testid="subscription-webhook-url"
                />
                <TextField
                  label={`Signing secret${editing !== null ? " (blank keeps the standing secret)" : " (16+ characters)"}`}
                  value={draft.webhookSecret}
                  onChange={(event) => setDraft({ ...draft, webhookSecret: event.target.value })}
                  data-testid="subscription-webhook-secret"
                />
              </>
            ) : (
              <>
                <TextField
                  select
                  slotProps={{ select: { multiple: true } }}
                  label="Recipient roles"
                  value={draft.recipientRoles
                    .split(",")
                    .map((role) => role.trim())
                    .filter((role) => role !== "")}
                  onChange={(event) =>
                    setDraft({
                      ...draft,
                      recipientRoles: (event.target.value as unknown as string[]).join(", "),
                    })
                  }
                  data-testid="subscription-roles"
                >
                  {ROLES.map((role) => (
                    <MenuItem key={role} value={role}>
                      {role}
                    </MenuItem>
                  ))}
                </TextField>
                <TextField
                  label="Recipient user ids (comma-separated, optional)"
                  value={draft.recipientUserIds}
                  onChange={(event) => setDraft({ ...draft, recipientUserIds: event.target.value })}
                  data-testid="subscription-users"
                />
              </>
            )}
            {editing !== null ? (
              <FormControlLabel
                control={
                  <Switch
                    checked={draft.enabled}
                    onChange={(event) => setDraft({ ...draft, enabled: event.target.checked })}
                    data-testid="subscription-enabled"
                  />
                }
                label="Enabled (a parked subscription routes nothing)"
              />
            ) : null}
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setEditorOpen(false)}>Cancel</Button>
          <Button
            variant="contained"
            disabled={save.isPending || draft.eventTypes.length === 0 || !recipientShapeValid}
            onClick={() => save.mutate()}
            data-testid="subscription-save"
          >
            Save
          </Button>
        </DialogActions>
      </Dialog>
    </Stack>
  );
}
