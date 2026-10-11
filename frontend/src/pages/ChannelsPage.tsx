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
  deleteAppStoreMetadata,
  deletePlayReleaseNotes,
  deleteStoreAccount,
  linkPlayGroup,
  listAppStoreMetadata,
  listChannels,
  listPlayGroups,
  listPlayReleaseNotes,
  listPlayTracks,
  listStoreAccounts,
  putAppStoreMetadata,
  putPlayReleaseNotes,
  syncPlayGroup,
  unlinkPlayGroup,
  verifyStoreAccount,
  type AppStoreMetadata,
  type PlayGroupLink,
  type PlayReleaseNotes,
  type StoreAccount,
} from "../api/distribution";
import { listEnvironments } from "../api/environments";
import { listApplications } from "../api/applications";
import { listTenants } from "../api/tenants";
import { listTenantTesterGroups } from "../api/rings";
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


/** One authored locale's draft (S13-03, AC1): the minimal field set
 * the story scopes — description, keywords, screenshot references
 * (manifest pins; ASC-side assets upload through ASC's own surfaces),
 * and the release notes the submission sends. */
interface MetadataDraft {
  applicationId: string;
  locale: string;
  description: string;
  keywords: string;
  screenshotRefs: string;
  releaseNotes: string;
}

function emptyMetadataDraft(): MetadataDraft {
  return { applicationId: "", locale: "en-US", description: "", keywords: "", screenshotRefs: "", releaseNotes: "" };
}

/** The App Store listing metadata section (S13-03, AC1): the
 * per-locale authoring surface the release manifest freezes at
 * APPROVED — what a production submission sends is what the manifest
 * froze, so author before approval; a later edit cannot change a
 * frozen release's submission. */
function AppStoreMetadataSection() {
  const { user } = useAuth();
  const orgId = user?.orgId ?? null;
  const toast = useToast();
  const queryClient = useQueryClient();
  const [open, setOpen] = useState(false);
  const [draft, setDraft] = useState<MetadataDraft>(emptyMetadataDraft());

  const metadata = useQuery({
    queryKey: ["appstore-metadata", orgId],
    queryFn: () => listAppStoreMetadata(orgId as string),
    enabled: orgId !== null,
  });

  const applications = useQuery({
    queryKey: ["distribution-applications", orgId],
    queryFn: () => listApplications({ organizationId: orgId as string, limit: 100 }),
    enabled: orgId !== null,
  });

  const applicationNameOf = (applicationId: string): string =>
    (applications.data?.items ?? []).find((application) => application.id === applicationId)?.name ?? applicationId;

  const save = useMutation({
    mutationFn: () =>
      putAppStoreMetadata({
        organizationId: orgId as string,
        applicationId: draft.applicationId,
        locale: draft.locale.trim(),
        description: draft.description.trim(),
        keywords: draft.keywords.trim().length > 0 ? draft.keywords.trim() : null,
        screenshotRefs:
          draft.screenshotRefs
            .split("\n")
            .map((ref) => ref.trim())
            .filter((ref) => ref.length > 0),
        releaseNotes: draft.releaseNotes.trim().length > 0 ? draft.releaseNotes.trim() : null,
      }),
    onSuccess: (row: AppStoreMetadata) => {
      toast.showSuccess(`Saved the ${row.locale} listing metadata`);
      setOpen(false);
      setDraft(emptyMetadataDraft());
      queryClient.invalidateQueries({ queryKey: ["appstore-metadata", orgId] });
    },
    onError: (error: Error) => toast.showError(error.message),
  });

  const remove = useMutation({
    mutationFn: (row: AppStoreMetadata) => deleteAppStoreMetadata(orgId as string, row.id),
    onSuccess: (_result, row: AppStoreMetadata) => {
      toast.showSuccess(`Removed the ${row.locale} listing metadata`);
      queryClient.invalidateQueries({ queryKey: ["appstore-metadata", orgId] });
    },
    onError: (error: Error) => toast.showError(error.message),
  });

  if (orgId === null) {
    return null;
  }
  const draftIncomplete =
    draft.applicationId === "" || draft.locale.trim().length === 0 || draft.description.trim().length === 0;

  return (
    <>
      <Stack direction="row" sx={{ justifyContent: "space-between", alignItems: "center" }}>
        <Box>
          <Typography variant="h6">App Store listing metadata</Typography>
          <Typography variant="body2" color="text.secondary">
            The per-locale listing facts a production submission sends; the release manifest freezes them at
            approval, so author before promoting.
          </Typography>
        </Box>
        <Button
          variant="outlined"
          startIcon={<AddIcon />}
          onClick={() => setOpen(true)}
          data-testid="add-metadata-button"
        >
          Add locale
        </Button>
      </Stack>

      {metadata.isPending ? <LinearProgress /> : null}
      {metadata.isError ? <Alert severity="error">The listing metadata could not be loaded.</Alert> : null}
      {metadata.data ? (
        <Paper elevation={2} data-testid="appstore-metadata">
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>Application</TableCell>
                <TableCell>Locale</TableCell>
                <TableCell>Description</TableCell>
                <TableCell>Keywords</TableCell>
                <TableCell>Release notes</TableCell>
                <TableCell align="right">Actions</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {metadata.data.items.length === 0 ? (
                <TableRow>
                  <TableCell colSpan={6}>
                    <Typography variant="body2" color="text.secondary">
                      No listing metadata authored. A production promotion without a frozen locale fails its
                      submission with the actionable reason.
                    </Typography>
                  </TableCell>
                </TableRow>
              ) : null}
              {metadata.data.items.map((row) => (
                <TableRow key={row.id} data-testid={`metadata-${row.locale}`}>
                  <TableCell>
                    <Typography variant="body2">{applicationNameOf(row.applicationId)}</Typography>
                  </TableCell>
                  <TableCell>
                    <Chip label={row.locale} size="small" variant="outlined" />
                  </TableCell>
                  <TableCell>
                    <Typography variant="body2" sx={{ maxWidth: 360 }}>
                      {row.description}
                    </Typography>
                  </TableCell>
                  <TableCell>{row.keywords ?? "-"}</TableCell>
                  <TableCell>{row.releaseNotes ?? "-"}</TableCell>
                  <TableCell align="right">
                    <Button
                      size="small"
                      color="error"
                      startIcon={<DeleteIcon />}
                      onClick={() => remove.mutate(row)}
                      disabled={remove.isPending}
                      aria-label={`remove ${row.locale} metadata`}
                    >
                      Remove
                    </Button>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </Paper>
      ) : null}

      <Dialog open={open} onClose={() => setOpen(false)} fullWidth maxWidth="sm">
        <DialogTitle>App Store listing metadata</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ marginTop: 1 }}>
            <TextField
              select
              label="Application"
              value={draft.applicationId}
              onChange={(event) => setDraft({ ...draft, applicationId: event.target.value })}
              fullWidth
            >
              {(applications.data?.items ?? []).map((application) => (
                <MenuItem key={application.id} value={application.id}>
                  {application.name}
                </MenuItem>
              ))}
            </TextField>
            <TextField
              label="Locale"
              value={draft.locale}
              onChange={(event) => setDraft({ ...draft, locale: event.target.value })}
              helperText="The store locale, e.g. en-US or de-DE"
              fullWidth
            />
            <TextField
              label="Description"
              value={draft.description}
              onChange={(event) => setDraft({ ...draft, description: event.target.value })}
              multiline
              minRows={3}
              fullWidth
            />
            <TextField
              label="Keywords"
              value={draft.keywords}
              onChange={(event) => setDraft({ ...draft, keywords: event.target.value })}
              helperText="Comma-separated; Apple caps at 100 characters"
              fullWidth
            />
            <TextField
              label="Screenshot references"
              value={draft.screenshotRefs}
              onChange={(event) => setDraft({ ...draft, screenshotRefs: event.target.value })}
              multiline
              minRows={2}
              helperText="One reference per line; the manifest pins them, ASC-side assets upload through ASC"
              fullWidth
            />
            <TextField
              label="Release notes (what's new)"
              value={draft.releaseNotes}
              onChange={(event) => setDraft({ ...draft, releaseNotes: event.target.value })}
              multiline
              minRows={2}
              fullWidth
            />
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setOpen(false)}>Cancel</Button>
          <Button
            variant="contained"
            disabled={draftIncomplete || save.isPending}
            onClick={() => save.mutate()}
            data-testid="save-metadata-button"
          >
            Save
          </Button>
        </DialogActions>
      </Dialog>
    </>
  );
}

/** The Play group mapping and tester-list sync section (S13-04,
 * AC2/AC4): the tester-group to Play track links — the track name is
 * the provider-side id — and the sync whose adds AND removes both
 * reflect on the track's email list. The track model read below the
 * links answers the listing console (Google's own status words). */
function PlayGroupsSection() {
  const { user } = useAuth();
  const orgId = user?.orgId ?? null;
  const toast = useToast();
  const queryClient = useQueryClient();
  const [open, setOpen] = useState(false);
  const [applicationId, setApplicationId] = useState("");
  const [tenantId, setTenantId] = useState("");
  const [groupId, setGroupId] = useState("");
  const [track, setTrack] = useState("internal");
  const [tracksApplication, setTracksApplication] = useState("");

  const links = useQuery({
    queryKey: ["play-groups", orgId],
    queryFn: () => listPlayGroups(orgId as string),
    enabled: orgId !== null,
  });

  const applications = useQuery({
    queryKey: ["distribution-applications", orgId],
    queryFn: () => listApplications({ organizationId: orgId as string, limit: 100 }),
    enabled: orgId !== null,
  });

  const tenants = useQuery({
    queryKey: ["play-link-tenants", orgId],
    queryFn: () => listTenants({ organizationId: orgId as string, limit: 100 }),
    enabled: orgId !== null && open,
  });

  const groups = useQuery({
    queryKey: ["play-link-groups", tenantId],
    queryFn: () => listTenantTesterGroups(tenantId),
    enabled: tenantId !== "",
  });

  const tracks = useQuery({
    queryKey: ["play-tracks", orgId, tracksApplication],
    queryFn: () => listPlayTracks(orgId as string, tracksApplication),
    enabled: orgId !== null && tracksApplication !== "",
  });

  const invalidate = () => {
    queryClient.invalidateQueries({ queryKey: ["play-groups", orgId] });
  };

  const link = useMutation({
    mutationFn: () =>
      linkPlayGroup({
        organizationId: orgId as string,
        applicationId,
        groupId,
        track: track.trim(),
      }),
    onSuccess: (row: PlayGroupLink) => {
      toast.showSuccess(`Linked ${row.groupName} to the ${row.externalGroupName} track`);
      setOpen(false);
      setApplicationId("");
      setTenantId("");
      setGroupId("");
      setTrack("internal");
      invalidate();
    },
    onError: (error: Error) => toast.showError(error.message),
  });

  const sync = useMutation({
    mutationFn: (row: PlayGroupLink) => syncPlayGroup(orgId as string, row.id),
    onSuccess: (view: { addedEmails: string[]; removedEmails: string[] }, row: PlayGroupLink) => {
      toast.showSuccess(
        `Synced the ${row.externalGroupName} track: ${view.addedEmails.length} added, ${view.removedEmails.length} removed`,
      );
      invalidate();
    },
    onError: (error: Error) => toast.showError(error.message),
  });

  const remove = useMutation({
    mutationFn: (row: PlayGroupLink) => unlinkPlayGroup(orgId as string, row.id),
    onSuccess: (_result, row: PlayGroupLink) => {
      toast.showSuccess(`Removed the ${row.groupName} link (the Play track stays)`);
      invalidate();
    },
    onError: (error: Error) => toast.showError(error.message),
  });

  if (orgId === null) {
    return null;
  }
  return (
    <>
      <Stack direction="row" sx={{ justifyContent: "space-between", alignItems: "center" }}>
        <Box>
          <Typography variant="h6">Google Play tester tracks</Typography>
          <Typography variant="body2" color="text.secondary">
            Tester groups linked to Play testing tracks; the sync makes the track&apos;s email list exactly the linked
            groups&apos; members — adds and removes both reflect.
          </Typography>
        </Box>
        <Button
          variant="outlined"
          startIcon={<AddIcon />}
          onClick={() => setOpen(true)}
          data-testid="link-play-group-button"
        >
          Link group
        </Button>
      </Stack>

      {links.isPending ? <LinearProgress /> : null}
      {links.isError ? <Alert severity="error">The Play group links could not be loaded.</Alert> : null}
      {links.data ? (
        <Paper elevation={2} data-testid="play-groups">
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>Tester group</TableCell>
                <TableCell>Play track</TableCell>
                <TableCell>Last synced</TableCell>
                <TableCell align="right">Actions</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {links.data.items.length === 0 ? (
                <TableRow>
                  <TableCell colSpan={4}>
                    <Typography variant="body2" color="text.secondary">
                      No tester groups linked. A release targeting groups on an unlinked track fails its records with
                      the mapping gap.
                    </Typography>
                  </TableCell>
                </TableRow>
              ) : null}
              {links.data.items.map((row) => (
                <TableRow key={row.id} data-testid={`play-link-${row.groupName}`}>
                  <TableCell>{row.groupName}</TableCell>
                  <TableCell>
                    <Chip label={row.externalGroupName} size="small" color="primary" variant="outlined" />
                  </TableCell>
                  <TableCell>{row.lastSyncedAt ?? "-"}</TableCell>
                  <TableCell align="right">
                    <Stack direction="row" spacing={1} sx={{ justifyContent: "flex-end" }}>
                      <Button
                        size="small"
                        startIcon={<TaskAltIcon />}
                        onClick={() => sync.mutate(row)}
                        disabled={sync.isPending}
                        aria-label={`sync ${row.groupName}`}
                      >
                        Sync
                      </Button>
                      <Button
                        size="small"
                        color="error"
                        startIcon={<DeleteIcon />}
                        onClick={() => remove.mutate(row)}
                        disabled={remove.isPending}
                        aria-label={`unlink ${row.groupName}`}
                      >
                        Unlink
                      </Button>
                    </Stack>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </Paper>
      ) : null}

      <Stack direction="row" spacing={1} sx={{ alignItems: "center" }}>
        <TextField
          select
          label="Application tracks"
          value={tracksApplication}
          onChange={(event) => setTracksApplication(event.target.value)}
          sx={{ minWidth: 280 }}
          helperText="The app's Play tracks with their releases (Google's own status words)"
        >
          {(applications.data?.items ?? []).map((application) => (
            <MenuItem key={application.id} value={application.id}>
              {application.name}
            </MenuItem>
          ))}
        </TextField>
      </Stack>
      {tracks.data ? (
        <Paper elevation={2} data-testid="play-tracks">
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>Track</TableCell>
                <TableCell>Releases</TableCell>
                <TableCell>Status</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {tracks.data.tracks.map((trackRow) => (
                <TableRow key={trackRow.track}>
                  <TableCell>{trackRow.track}</TableCell>
                  <TableCell>
                    {trackRow.releases.length === 0
                      ? "-"
                      : trackRow.releases
                          .map(
                            (release) =>
                              `${release.name ?? ""} (${release.versionCodes.join(", ")})`,
                          )
                          .join("; ")}
                  </TableCell>
                  <TableCell>
                    {trackRow.releases.length === 0 ? (
                      "-"
                    ) : (
                      <Stack direction="row" spacing={0.5} sx={{ flexWrap: "wrap", gap: 0.5 }}>
                        {trackRow.releases.map((release, index) => (
                          <Chip
                            key={index}
                            label={release.status ?? "unknown"}
                            size="small"
                            variant="outlined"
                          />
                        ))}
                      </Stack>
                    )}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </Paper>
      ) : null}

      <Dialog open={open} onClose={() => setOpen(false)} fullWidth maxWidth="sm">
        <DialogTitle>Link a tester group to a Play track</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ marginTop: 1 }}>
            <TextField
              select
              label="Application"
              value={applicationId}
              onChange={(event) => setApplicationId(event.target.value)}
              fullWidth
            >
              {(applications.data?.items ?? []).map((application) => (
                <MenuItem key={application.id} value={application.id}>
                  {application.name}
                </MenuItem>
              ))}
            </TextField>
            <TextField
              select
              label="Tenant"
              value={tenantId}
              onChange={(event) => {
                setTenantId(event.target.value);
                setGroupId("");
              }}
              fullWidth
            >
              {(tenants.data?.items ?? []).map((tenant) => (
                <MenuItem key={tenant.id} value={tenant.id}>
                  {tenant.name}
                </MenuItem>
              ))}
            </TextField>
            <TextField
              select
              label="Tester group"
              value={groupId}
              onChange={(event) => setGroupId(event.target.value)}
              disabled={tenantId === ""}
              fullWidth
            >
              {(groups.data ?? []).map((group) => (
                <MenuItem key={group.id} value={group.id}>
                  {group.name}
                </MenuItem>
              ))}
            </TextField>
            <TextField
              label="Track"
              value={track}
              onChange={(event) => setTrack(event.target.value)}
              helperText="A testing track: internal, or the app's closed track name (production is refused)"
              fullWidth
            />
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setOpen(false)}>Cancel</Button>
          <Button
            variant="contained"
            disabled={applicationId === "" || groupId === "" || track.trim().length === 0 || link.isPending}
            onClick={() => link.mutate()}
            data-testid="link-play-group-submit"
          >
            Link
          </Button>
        </DialogActions>
      </Dialog>
    </>
  );
}

/** The Play release-notes authoring section (S13-04, AC2): the
 * per-locale what's-new the release manifest freezes at APPROVED and
 * the track release sends — author before promoting; a later edit
 * cannot change a frozen release's notes. */
function PlayReleaseNotesSection() {
  const { user } = useAuth();
  const orgId = user?.orgId ?? null;
  const toast = useToast();
  const queryClient = useQueryClient();
  const [open, setOpen] = useState(false);
  const [applicationId, setApplicationId] = useState("");
  const [locale, setLocale] = useState("en-US");
  const [notes, setNotes] = useState("");

  const releaseNotes = useQuery({
    queryKey: ["play-release-notes", orgId],
    queryFn: () => listPlayReleaseNotes(orgId as string),
    enabled: orgId !== null,
  });

  const applications = useQuery({
    queryKey: ["distribution-applications", orgId],
    queryFn: () => listApplications({ organizationId: orgId as string, limit: 100 }),
    enabled: orgId !== null,
  });

  const applicationNameOf = (id: string): string =>
    (applications.data?.items ?? []).find((application) => application.id === id)?.name ?? id;

  const save = useMutation({
    mutationFn: () =>
      putPlayReleaseNotes({
        organizationId: orgId as string,
        applicationId,
        locale: locale.trim(),
        notes: notes.trim(),
      }),
    onSuccess: (row: PlayReleaseNotes) => {
      toast.showSuccess(`Saved the ${row.locale} release notes`);
      setOpen(false);
      setApplicationId("");
      setLocale("en-US");
      setNotes("");
      queryClient.invalidateQueries({ queryKey: ["play-release-notes", orgId] });
    },
    onError: (error: Error) => toast.showError(error.message),
  });

  const remove = useMutation({
    mutationFn: (row: PlayReleaseNotes) => deletePlayReleaseNotes(orgId as string, row.id),
    onSuccess: (_result, row: PlayReleaseNotes) => {
      toast.showSuccess(`Removed the ${row.locale} release notes`);
      queryClient.invalidateQueries({ queryKey: ["play-release-notes", orgId] });
    },
    onError: (error: Error) => toast.showError(error.message),
  });

  if (orgId === null) {
    return null;
  }
  return (
    <>
      <Stack direction="row" sx={{ justifyContent: "space-between", alignItems: "center" }}>
        <Box>
          <Typography variant="h6">Google Play release notes</Typography>
          <Typography variant="body2" color="text.secondary">
            The per-locale what&apos;s-new a Play track release sends; the release manifest freezes them at approval,
            so author before promoting.
          </Typography>
        </Box>
        <Button
          variant="outlined"
          startIcon={<AddIcon />}
          onClick={() => setOpen(true)}
          data-testid="add-release-notes-button"
        >
          Add locale
        </Button>
      </Stack>

      {releaseNotes.isPending ? <LinearProgress /> : null}
      {releaseNotes.isError ? <Alert severity="error">The release notes could not be loaded.</Alert> : null}
      {releaseNotes.data ? (
        <Paper elevation={2} data-testid="play-release-notes">
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>Application</TableCell>
                <TableCell>Locale</TableCell>
                <TableCell>Release notes</TableCell>
                <TableCell align="right">Actions</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {releaseNotes.data.items.length === 0 ? (
                <TableRow>
                  <TableCell colSpan={4}>
                    <Typography variant="body2" color="text.secondary">
                      No release notes authored. A Play release without a frozen locale submits no notes.
                    </Typography>
                  </TableCell>
                </TableRow>
              ) : null}
              {releaseNotes.data.items.map((row) => (
                <TableRow key={row.id} data-testid={`release-notes-${row.locale}`}>
                  <TableCell>{applicationNameOf(row.applicationId)}</TableCell>
                  <TableCell>
                    <Chip label={row.locale} size="small" variant="outlined" />
                  </TableCell>
                  <TableCell>
                    <Typography variant="body2" sx={{ maxWidth: 420 }}>
                      {row.notes}
                    </Typography>
                  </TableCell>
                  <TableCell align="right">
                    <Button
                      size="small"
                      color="error"
                      startIcon={<DeleteIcon />}
                      onClick={() => remove.mutate(row)}
                      disabled={remove.isPending}
                      aria-label={`remove ${row.locale} release notes`}
                    >
                      Remove
                    </Button>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </Paper>
      ) : null}

      <Dialog open={open} onClose={() => setOpen(false)} fullWidth maxWidth="sm">
        <DialogTitle>Google Play release notes</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ marginTop: 1 }}>
            <TextField
              select
              label="Application"
              value={applicationId}
              onChange={(event) => setApplicationId(event.target.value)}
              fullWidth
            >
              {(applications.data?.items ?? []).map((application) => (
                <MenuItem key={application.id} value={application.id}>
                  {application.name}
                </MenuItem>
              ))}
            </TextField>
            <TextField
              label="Locale"
              value={locale}
              onChange={(event) => setLocale(event.target.value)}
              helperText="The store locale, e.g. en-US or de-DE"
              fullWidth
            />
            <TextField
              label="Release notes"
              value={notes}
              onChange={(event) => setNotes(event.target.value)}
              multiline
              minRows={3}
              helperText="Google caps the text at 500 characters"
              fullWidth
            />
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setOpen(false)}>Cancel</Button>
          <Button
            variant="contained"
            disabled={applicationId === "" || locale.trim().length === 0 || notes.trim().length === 0 || save.isPending}
            onClick={() => save.mutate()}
            data-testid="save-release-notes-button"
          >
            Save
          </Button>
        </DialogActions>
      </Dialog>
    </>
  );
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

      <AppStoreMetadataSection />

      <PlayReleaseNotesSection />

      <PlayGroupsSection />

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
