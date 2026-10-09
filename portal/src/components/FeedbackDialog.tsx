/**
 * The "Report issue" submit surface (issue #46, S11-01, FR-027, PRD
 * 8.13): one dialog, opened from the version screens, that submits a
 * structured report with the build context attached automatically —
 * the tester types WHAT happened, the screen supplies WHERE
 * (application/version/build from the page's own context, AC2: the
 * identity and tenant link the server resolves from the session; the
 * form never carries them). Mobile-first: a phone-sized viewport gets
 * the full-screen sheet, tablets and up the centered card.
 *
 * States follow the portal's conventions (docs/portal.md): a required
 * non-empty description and a six-type vocabulary validated inline
 * before any request; attachments gated by extension and count (the
 * server's per-kind size limits are the authority and their 422
 * renders per the problem envelope's field errors); a 201 renders the
 * confirmation with the stored report's id; every failure renders the
 * problem+json envelope honestly — stable code, trace id, retry — and
 * a transport failure renders the unexpected-error retry.
 */
import BugReportIcon from "@mui/icons-material/BugReport";
import CheckCircleIcon from "@mui/icons-material/CheckCircle";
import {
  Button,
  Chip,
  CircularProgress,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Divider,
  FormControl,
  FormHelperText,
  InputLabel,
  MenuItem,
  Select,
  Stack,
  TextField,
  Typography,
  useMediaQuery,
  useTheme,
} from "@mui/material";
import { useRef, useState } from "react";
import type { ChangeEvent } from "react";
import { ApiError } from "../api/problem";
import {
  deriveOsFromUserAgent,
  FEEDBACK_ATTACHMENT_EXTENSIONS,
  FEEDBACK_MAX_ATTACHMENTS,
  FEEDBACK_TYPE_LABELS,
  FEEDBACK_TYPES,
  submitFeedback,
} from "../api/feedback";
import type { FeedbackContext, FeedbackType } from "../api/feedback";

/** One build the tester can pin the report to (the platform slots the screen already holds). */
export interface FeedbackBuildOption {
  artifactId: string;
  label: string;
}

/** Server field keys the form renders inline under their own inputs;
 * every OTHER key in a problem envelope's errors member is an
 * attachment rejection keyed by file name (the 422's shape). */
const KNOWN_FIELD_KEYS = new Set(["type", "description", "device_model", "os", "attachments"]);

export default function FeedbackDialog({
  context,
  versionName,
  builds = [],
  open,
  onClose,
}: {
  context: FeedbackContext;
  versionName: string;
  builds?: FeedbackBuildOption[];
  open: boolean;
  onClose: () => void;
}) {
  const theme = useTheme();
  const fullScreen = useMediaQuery(theme.breakpoints.down("md"));
  const fileInput = useRef<HTMLInputElement>(null);

  const [type, setType] = useState<FeedbackType>("BUG");
  const [description, setDescription] = useState("");
  const [deviceModel, setDeviceModel] = useState("");
  const [os, setOs] = useState(() => deriveOsFromUserAgent(navigator.userAgent));
  const [artifactId, setArtifactId] = useState("");
  const [files, setFiles] = useState<File[]>([]);
  const [submitting, setSubmitting] = useState(false);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [failure, setFailure] = useState<ApiError | "transport" | null>(null);
  const [submittedId, setSubmittedId] = useState<string | null>(null);

  function reset() {
    setType("BUG");
    setDescription("");
    setDeviceModel("");
    setOs(deriveOsFromUserAgent(navigator.userAgent));
    setArtifactId("");
    setFiles([]);
    setSubmitting(false);
    setFieldErrors({});
    setFailure(null);
    setSubmittedId(null);
    if (fileInput.current) {
      fileInput.current.value = "";
    }
  }

  function handleClose() {
    if (submitting) return;
    reset();
    onClose();
  }

  /** The server's attachment rejections keyed by FILE NAME (the 422's
   * errors member) roll up into the attachments helper line beside
   * any count/extension refusal the client raised. */
  function attachmentErrorText(): string {
    const inline = fieldErrors.attachments ?? "";
    const byFile = Object.entries(fieldErrors)
      .filter(([key]) => !KNOWN_FIELD_KEYS.has(key))
      .map(([file, message]) => `${file}: ${message}`);
    return [inline, ...byFile].filter(Boolean).join(" ");
  }

  function selectFiles(event: ChangeEvent<HTMLInputElement>) {
    const chosen = event.target.files ? Array.from(event.target.files) : [];
    setFiles(chosen.slice(0, FEEDBACK_MAX_ATTACHMENTS));
    setFieldErrors((previous) => ({ ...previous, attachments: "" }));
  }

  function removeFile(name: string) {
    setFiles((previous) => previous.filter((file) => file.name !== name));
    if (fileInput.current) {
      fileInput.current.value = "";
    }
  }

  async function handleSubmit() {
    // Client-side validation mirrors the server's syntactic gate
    // (AC1): the description is required; the extension and count
    // gates are honest fast-fails, the server's 422 stays the
    // authority on limits.
    const errors: Record<string, string> = {};
    const trimmed = description.trim();
    if (!trimmed) {
      errors.description = "Describe what happened so the report can be acted on.";
    }
    if (files.length > FEEDBACK_MAX_ATTACHMENTS) {
      errors.attachments = `At most ${FEEDBACK_MAX_ATTACHMENTS} attachments per report.`;
    }
    for (const file of files) {
      const extension = file.name.substring(file.name.lastIndexOf(".")).toLowerCase();
      if (!(FEEDBACK_ATTACHMENT_EXTENSIONS as readonly string[]).includes(extension)) {
        errors.attachments = `${file.name}: allowed types are png, jpg, mp4, txt, log.`;
      }
    }
    setFieldErrors(errors);
    if (Object.keys(errors).length > 0) {
      return;
    }

    setSubmitting(true);
    setFailure(null);
    try {
      const report = await submitFeedback(
        { ...context, artifactId: artifactId || undefined },
        {
          type,
          description: trimmed,
          deviceModel: deviceModel.trim() || undefined,
          os: os.trim() || undefined,
        },
        files,
      );
      setSubmittedId(report.id);
    } catch (error) {
      // The problem envelope's field errors (400 INVALID_REQUEST,
      // 422 ATTACHMENT_REJECTED) render inline under their fields —
      // the fix is in the form, not a retry; every other failure
      // renders the envelope card with a retry.
      if (error instanceof ApiError && error.fieldErrors) {
        setFieldErrors(error.fieldErrors);
        setFailure(null);
      } else {
        setFailure(error instanceof ApiError ? error : "transport");
      }
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Dialog
      open={open}
      onClose={handleClose}
      fullScreen={fullScreen}
      maxWidth="sm"
      fullWidth
      data-testid="feedback-dialog"
    >
      {submittedId ? (
        <Stack spacing={2} sx={{ p: 3, alignItems: "center", textAlign: "center", py: 6 }} data-testid="feedback-success">
          <CheckCircleIcon color="success" sx={{ fontSize: 48 }} />
          <Typography variant="h6">Report submitted</Typography>
          <Typography variant="body2" color="text.secondary" sx={{ maxWidth: 380 }}>
            Thank you. Your {FEEDBACK_TYPE_LABELS[type].toLowerCase()} report on {versionName} was received with this
            build's context attached — the team triages it from here.
          </Typography>
          <Typography variant="caption" color="text.secondary" data-testid="feedback-success-id">
            {submittedId}
          </Typography>
          <Button variant="contained" onClick={handleClose} data-testid="feedback-done">
            Done
          </Button>
        </Stack>
      ) : (
        <>
          <DialogTitle sx={{ display: "flex", alignItems: "center", gap: 1 }} data-testid="feedback-title">
            <BugReportIcon color="primary" fontSize="small" />
            Report an issue on {versionName}
          </DialogTitle>
          <DialogContent dividers>
            <Stack spacing={2.5} sx={{ pt: 0.5 }}>
              <FormControl fullWidth error={Boolean(fieldErrors.type)}>
                <InputLabel id="feedback-type-label" data-testid="feedback-type-label">
                  Type
                </InputLabel>
                <Select<FeedbackType>
                  labelId="feedback-type-label"
                  label="Type"
                  value={type}
                  onChange={(event) => setType(event.target.value)}
                  disabled={submitting}
                  data-testid="feedback-type"
                >
                  {FEEDBACK_TYPES.map((option) => (
                    <MenuItem key={option} value={option} data-testid={`feedback-type-${option}`}>
                      {FEEDBACK_TYPE_LABELS[option]}
                    </MenuItem>
                  ))}
                </Select>
                <FormHelperText>{fieldErrors.type ?? "What kind of report is this?"}</FormHelperText>
              </FormControl>

              <TextField
                label="What happened?"
                multiline
                minRows={4}
                maxRows={10}
                value={description}
                onChange={(event) => setDescription(event.target.value)}
                error={Boolean(fieldErrors.description)}
                helperText={fieldErrors.description ?? "Required. Describe what you did and what went wrong."}
                disabled={submitting}
                fullWidth
                slotProps={{ htmlInput: { "data-testid": "feedback-description", maxLength: 8000 } }}
              />

              {builds.length > 0 ? (
                <FormControl fullWidth>
                  <InputLabel id="feedback-build-label">Build</InputLabel>
                  <Select
                    labelId="feedback-build-label"
                    label="Build"
                    value={artifactId}
                    onChange={(event) => setArtifactId(event.target.value)}
                    disabled={submitting}
                    data-testid="feedback-build"
                  >
                    <MenuItem value="" data-testid="feedback-build-none">
                      Not build-specific
                    </MenuItem>
                    {builds.map((build) => (
                      <MenuItem key={build.artifactId} value={build.artifactId} data-testid={`feedback-build-${build.artifactId}`}>
                        {build.label}
                      </MenuItem>
                    ))}
                  </Select>
                  <FormHelperText>Attached automatically so the team gets your exact build.</FormHelperText>
                </FormControl>
              ) : null}

              <Stack direction={{ xs: "column", sm: "row" }} spacing={2}>
                <TextField
                  label="Device (optional)"
                  value={deviceModel}
                  onChange={(event) => setDeviceModel(event.target.value)}
                  disabled={submitting}
                  fullWidth
                  slotProps={{ htmlInput: { "data-testid": "feedback-device", maxLength: 256 } }}
                />
                <TextField
                  label="OS (optional)"
                  value={os}
                  onChange={(event) => setOs(event.target.value)}
                  disabled={submitting}
                  fullWidth
                  slotProps={{ htmlInput: { "data-testid": "feedback-os", maxLength: 128 } }}
                />
              </Stack>

              <Divider />

              <Stack spacing={1}>
                <Typography variant="body2" color="text.secondary">
                  Attachments help: screenshots, screen recordings, or logs.
                </Typography>
                <input
                  ref={fileInput}
                  type="file"
                  multiple
                  accept={FEEDBACK_ATTACHMENT_EXTENSIONS.join(",")}
                  onChange={selectFiles}
                  style={{ display: "none" }}
                  data-testid="feedback-file-input"
                />
                <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: "wrap", alignItems: "center" }}>
                  <Button
                    size="small"
                    variant="outlined"
                    onClick={() => fileInput.current?.click()}
                    disabled={submitting || files.length >= FEEDBACK_MAX_ATTACHMENTS}
                    data-testid="feedback-attachments-button"
                  >
                    Add file
                  </Button>
                  <Typography variant="caption" color="text.secondary">
                    png, jpg, mp4, txt, log — up to {FEEDBACK_MAX_ATTACHMENTS} files
                  </Typography>
                </Stack>
                {files.map((file) => (
                  <Chip
                    key={file.name}
                    size="small"
                    label={`${file.name} (${Math.max(1, Math.round(file.size / 1024))} KB)`}
                    onDelete={submitting ? undefined : () => removeFile(file.name)}
                    data-testid={`feedback-attachment-${file.name}`}
                  />
                ))}
                {attachmentErrorText() ? (
                  <FormHelperText error data-testid="feedback-attachments-error">
                    {attachmentErrorText()}
                  </FormHelperText>
                ) : null}
              </Stack>

              {failure ? (
                <Stack spacing={1} sx={{ alignItems: "center", textAlign: "center" }} data-testid="feedback-error">
                  <Typography variant="subtitle2" color="error">
                    The report was not submitted
                  </Typography>
                  <Typography variant="body2" color="text.secondary" data-testid="feedback-error-detail">
                    {failure === "transport"
                      ? "Unexpected error. Please retry."
                      : `${failure.title ?? "Request failed"}${failure.code ? ` (${failure.code})` : ""}`}
                  </Typography>
                  {failure !== "transport" && failure.traceId ? (
                    <Typography variant="caption" color="text.secondary">
                      trace {failure.traceId}
                    </Typography>
                  ) : null}
                  <Button
                    variant="contained"
                    size="small"
                    onClick={handleSubmit}
                    disabled={submitting}
                    data-testid="feedback-error-retry"
                  >
                    Retry
                  </Button>
                </Stack>
              ) : null}
            </Stack>
          </DialogContent>
          <DialogActions sx={{ px: 3, py: 2 }}>
            <Button onClick={handleClose} disabled={submitting} data-testid="feedback-cancel">
              Cancel
            </Button>
            <Button
              variant="contained"
              onClick={handleSubmit}
              disabled={submitting}
              startIcon={submitting ? <CircularProgress size={18} color="inherit" /> : null}
              data-testid="feedback-submit"
            >
              {submitting ? "Submitting" : "Submit report"}
            </Button>
          </DialogActions>
        </>
      )}
    </Dialog>
  );
}
