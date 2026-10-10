/**
 * Distribution channels (S13-01, issue #61) — the adapter framework's
 * console surface: the channel catalog (the registered adapters with
 * their capabilities, read-only facts the registry owns) and the
 * credential-delegation chain's bindings (bind a store account at the
 * organization, application, or application+environment level;
 * resolution is first-defined-wins walking environment to application
 * to organization, PRD 25). Secret material enters exactly once
 * through the bind dialog and is never echoed — the table carries the
 * opaque reference the account resolves through, and the verify
 * action answers only whether that reference RESOLVES. Client-side
 * validation refuses the obvious shapes; the server re-validates
 * everything (the level's parents, the provider vocabulary, the
 * material's presence) and refusals surface the problem+json code.
 */
import AddIcon from "@mui/icons-material/Add";
import DeleteIcon from "@mui/icons-material/Delete";
import TaskAltIcon from "@mui/icons-material/TaskAlt";
import {
  Alert,
  Box,
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
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  TextField,
  Typography,
} from "@mui/material";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import {
  bindStoreAccount,
  deleteStoreAccount,
  listChannels,
  listStoreAccounts,
  verifyStoreAccount,
  type StoreAccount,
} from "../api/distribution";
import { listEnvironments } from "../api/environments";
import { listApplications } from "../api/applications";
import { useAuth } from "../auth/AuthContext";
import { useToast } from "../components/ToastContext";

/** The bind dialog's level picker: PRD 25's three delegation levels. */
type Level = "organization" | "application" | "environment";

interface Draft {
  level: Level;
  provider: string;
  name: string;
  applicationId: string;
  environmentId: string;
  material: string;
}

function emptyDraft(): Draft {
  return { level: "organization", provider: "", name: "", applicationId: "", environmentId: "", material: "" };
}

export default function ChannelsPage() {
  const { user } = useAuth();
  const orgId = user?.orgId ?? null;
  const toast = useToast();
  const queryClient = useQueryClient();
  const [dialogOpen, setDialogOpen] = useState(false);
  const [draft, setDraft] = useState<Draft>(emptyDraft());

  const channels = useQuery({
    queryKey: ["distribution-channels", orgId],
    queryFn: () => listChannels(orgId as string),
    enabled: orgId !== null,
  });

  const accounts = useQuery({
    queryKey: ["distribution-credentials", orgId],
    queryFn: () => listStoreAccounts(orgId as string),
    enabled: orgId !== null,
  });

  const applications = useQuery({
    queryKey: ["distribution-applications", orgId],
    queryFn: () => listApplications({ organizationId: orgId as string, limit: 100 }),
    enabled: orgId !== null && dialogOpen,
  });

  const environments = useQuery({
    queryKey: ["distribution-environments", orgId],
    queryFn: () => listEnvironments(orgId as string),
    enabled: orgId !== null && dialogOpen,
  });

  const invalidate = () => {
    queryClient.invalidateQueries({ queryKey: ["distribution-credentials", orgId] });
  };

  const bind = useMutation({
    mutationFn: () =>
      bindStoreAccount({
        organizationId: orgId as string,
        applicationId: draft.level === "organization" ? null : draft.applicationId || null,
        environmentId: draft.level === "environment" ? draft.environmentId || null : null,
        provider: draft.provider.trim().toLowerCase(),
        name: draft.name.trim(),
        secretMaterial: draft.material,
      }),
    onSuccess: (account: StoreAccount) => {
      toast.showSuccess(`Bound ${account.provider} at the ${account.level} level`);
      setDialogOpen(false);
      setDraft(emptyDraft());
      invalidate();
    },
    onError: (error: Error) => toast.showError(error.message),
  });

  const verify = useMutation({
    mutationFn: (account: StoreAccount) => verifyStoreAccount(orgId as string, account.id),
    onSuccess: ({ resolved }: { resolved: boolean }, account: StoreAccount) => {
      if (resolved) {
        toast.showSuccess(`${account.provider} (${account.level}) resolves through the secret store`);
      } else {
        toast.showError(`${account.provider} (${account.level}) does not resolve; rotate the binding`);
      }
    },
    onError: (error: Error) => toast.showError(error.message),
  });

  const remove = useMutation({
    mutationFn: (account: StoreAccount) => deleteStoreAccount(orgId as string, account.id),
    onSuccess: (_result, account: StoreAccount) => {
      toast.showSuccess(`Removed the ${account.level}-level ${account.provider} binding`);
      invalidate();
    },
    onError: (error: Error) => toast.showError(error.message),
  });

  if (orgId === null) {
    return (
      <Alert severity="info">Your account has no active organization membership; distribution is unavailable.</Alert>
    );
  }

  const draftIncomplete =
    draft.provider.trim().length === 0 ||
    draft.name.trim().length === 0 ||
    draft.material.length === 0 ||
    (draft.level !== "organization" && draft.applicationId === "") ||
    (draft.level === "environment" && draft.environmentId === "");

  return (
    <Stack spacing={3}>
      <Stack direction="row" sx={{ justifyContent: "space-between", alignItems: "center" }}>
        <Box>
          <Typography variant="h5">Distribution channels</Typography>
          <Typography variant="body2" color="text.secondary">
            The registered channel adapters and the store accounts their dispatches resolve (environment overrides
            application overrides organization).
          </Typography>
        </Box>
        <Button
          variant="contained"
          startIcon={<AddIcon />}
          onClick={() => setDialogOpen(true)}
          data-testid="bind-account-button"
        >
          Bind store account
        </Button>
      </Stack>

      {channels.isPending || accounts.isPending ? <LinearProgress /> : null}

      {channels.isError ? <Alert severity="error">The channel catalog could not be loaded.</Alert> : null}
      {channels.data ? (
        <Paper elevation={2} data-testid="channel-catalog">
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>Channel</TableCell>
                <TableCell>Capabilities</TableCell>
                <TableCell>Credential providers</TableCell>
                <TableCell>Description</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {channels.data.channels.map((channel) => (
                <TableRow key={channel.channelId} data-testid={`channel-${channel.channelId}`}>
                  <TableCell>
                    <Typography variant="body2" sx={{ fontWeight: 600 }}>
                      {channel.displayName}
                    </Typography>
                    <Typography variant="caption" color="text.secondary">
                      {channel.channelId}
                    </Typography>
                  </TableCell>
                  <TableCell>
                    <Stack direction="row" spacing={0.5} sx={{ flexWrap: "wrap", gap: 0.5 }}>
                      {channel.capabilities.map((capability) => (
                        <Chip key={capability} label={capability} size="small" variant="outlined" />
                      ))}
                    </Stack>
                  </TableCell>
                  <TableCell>
                    {channel.credentialProviders.length === 0 ? (
                      <Typography variant="caption" color="text.secondary">
                        none required
                      </Typography>
                    ) : (
                      <Stack direction="row" spacing={0.5} sx={{ flexWrap: "wrap", gap: 0.5 }}>
                        {channel.credentialProviders.map((provider) => (
                          <Chip key={provider} label={provider} size="small" color="primary" variant="outlined" />
                        ))}
                      </Stack>
                    )}
                  </TableCell>
                  <TableCell>
                    <Typography variant="body2" color="text.secondary">
                      {channel.description}
                    </Typography>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </Paper>
      ) : null}

      {accounts.isError ? <Alert severity="error">The credential bindings could not be loaded.</Alert> : null}
      {accounts.data ? (
        <Paper elevation={2} data-testid="store-accounts">
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>Provider</TableCell>
                <TableCell>Account</TableCell>
                <TableCell>Level</TableCell>
                <TableCell>Secret reference</TableCell>
                <TableCell align="right">Actions</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {accounts.data.items.length === 0 ? (
                <TableRow>
                  <TableCell colSpan={5}>
                    <Typography variant="body2" color="text.secondary">
                      No store accounts bound. Organization-level defaults are inherited by every application and
                      environment; overrides win walking environment to application to organization.
                    </Typography>
                  </TableCell>
                </TableRow>
              ) : null}
              {accounts.data.items.map((account) => (
                <TableRow key={account.id} data-testid={`account-${account.provider}-${account.level}`}>
                  <TableCell>{account.provider}</TableCell>
                  <TableCell>{account.name}</TableCell>
                  <TableCell>
                    <Chip
                      label={account.level}
                      size="small"
                      color={account.level === "organization" ? "default" : "primary"}
                      variant="outlined"
                    />
                  </TableCell>
                  <TableCell>
                    <Typography variant="caption" sx={{ wordBreak: "break-all" }}>
                      {account.secretReference}
                    </Typography>
                  </TableCell>
                  <TableCell align="right">
                    <Stack direction="row" spacing={1} sx={{ justifyContent: "flex-end" }}>
                      <Button
                        size="small"
                        startIcon={<TaskAltIcon />}
                        onClick={() => verify.mutate(account)}
                        disabled={verify.isPending}
                        aria-label={`verify ${account.provider} ${account.level}`}
                      >
                        Verify
                      </Button>
                      <Button
                        size="small"
                        color="error"
                        startIcon={<DeleteIcon />}
                        onClick={() => remove.mutate(account)}
                        disabled={remove.isPending}
                        aria-label={`remove ${account.provider} ${account.level}`}
                      >
                        Remove
                      </Button>
                    </Stack>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </Paper>
      ) : null}

      <Dialog open={dialogOpen} onClose={() => setDialogOpen(false)} fullWidth maxWidth="sm">
        <DialogTitle>Bind store account</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ marginTop: 1 }}>
            <TextField
              select
              label="Level"
              value={draft.level}
              onChange={(event) => setDraft({ ...draft, level: event.target.value as Level })}
              helperText="Environment overrides application overrides organization (PRD 25)."
            >
              <MenuItem value="organization">Organization (the inherited default)</MenuItem>
              <MenuItem value="application">Application override</MenuItem>
              <MenuItem value="environment">Application + environment override</MenuItem>
            </TextField>
            {draft.level !== "organization" ? (
              <TextField
                select
                required
                label="Application"
                value={draft.applicationId}
                onChange={(event) => setDraft({ ...draft, applicationId: event.target.value, environmentId: "" })}
              >
                {(applications.data?.items ?? []).map((application) => (
                  <MenuItem key={application.id} value={application.id}>
                    {application.name}
                  </MenuItem>
                ))}
              </TextField>
            ) : null}
            {draft.level === "environment" ? (
              <TextField
                select
                required
                label="Environment"
                value={draft.environmentId}
                onChange={(event) => setDraft({ ...draft, environmentId: event.target.value })}
                helperText="Production credentials are not QA credentials: different environments, different rows."
              >
                {(environments.data?.items ?? []).map((environment) => (
                  <MenuItem key={environment.id} value={environment.id}>
                    {environment.name} ({environment.code})
                  </MenuItem>
                ))}
              </TextField>
            ) : null}
            <TextField
              label="Provider"
              required
              value={draft.provider}
              onChange={(event) => setDraft({ ...draft, provider: event.target.value })}
              helperText="The store's vocabulary: apple, google-play, ..."
            />
            <TextField
              label="Account name"
              required
              value={draft.name}
              onChange={(event) => setDraft({ ...draft, name: event.target.value })}
            />
            <TextField
              label="Secret material"
              type="password"
              required
              value={draft.material}
              onChange={(event) => setDraft({ ...draft, material: event.target.value })}
              helperText="Stored once through the secret store; only the opaque reference is ever shown again."
            />
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setDialogOpen(false)}>Cancel</Button>
          <Button
            variant="contained"
            disabled={draftIncomplete || bind.isPending}
            onClick={() => bind.mutate()}
            data-testid="bind-submit"
          >
            {bind.isPending ? "Binding..." : "Bind"}
          </Button>
        </DialogActions>
      </Dialog>
    </Stack>
  );
}
