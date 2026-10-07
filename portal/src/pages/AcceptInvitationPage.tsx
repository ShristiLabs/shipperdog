/**
 * Invitation accept (issue #35): the entry point of every invitation
 * link — the backend mints `{portalBaseUrl}/accept-invitation?token=`
 * (docs/testers.md). The tester sets a password (POST /auth/register
 * with the invitation token, S6-01), then is signed in through the
 * standard login flow and lands on the portal home. Expired, used, or
 * unknown tokens surface the API's problem+json envelope honestly.
 */
import { Alert, Box, Button, Card, CardContent, Stack, TextField, Typography } from "@mui/material";
import MarkEmailReadIcon from "@mui/icons-material/MarkEmailRead";
import { useState, type FormEvent } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { useAuth } from "../auth/AuthContext";
import { registerRequest } from "../api/auth";
import { ApiError } from "../api/problem";

/** Mirrors RegistrationService's bounds (docs/authentication.md): the
 * server is the authority; the client only fails fast. */
const MIN_PASSWORD = 12;
const MAX_PASSWORD = 128;

export default function AcceptInvitationPage() {
  const [params] = useSearchParams();
  const token = params.get("token") ?? "";
  const navigate = useNavigate();
  const { login } = useAuth();

  const [email, setEmail] = useState("");
  const [fullName, setFullName] = useState("");
  const [password, setPassword] = useState("");
  const [confirm, setConfirm] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [submitting, setSubmitting] = useState(false);

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    setFieldErrors({});
    const local: Record<string, string> = {};
    if (password.length < MIN_PASSWORD || password.length > MAX_PASSWORD) {
      local.password = `Password must be ${MIN_PASSWORD}-${MAX_PASSWORD} characters.`;
    }
    if (confirm !== password) {
      local.confirm = "Passwords do not match.";
    }
    if (Object.keys(local).length > 0) {
      setFieldErrors(local);
      return;
    }
    setSubmitting(true);
    try {
      await registerRequest({ invitationToken: token, email, password, fullName });
      await login(email, password);
      navigate("/", { replace: true });
    } catch (cause) {
      if (cause instanceof ApiError) {
        if (cause.fieldErrors) setFieldErrors(cause.fieldErrors);
        setError("Could not accept the invitation: " + (cause.detail ?? cause.title ?? "request failed"));
      } else {
        setError("Could not accept the invitation. Please retry.");
      }
    } finally {
      setSubmitting(false);
    }
  }

  if (!token) {
    return (
      <MissingTokenCard />
    );
  }

  return (
    <Box
      sx={{
        minHeight: "100vh",
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
        backgroundColor: "grey.100",
        p: 2,
      }}
    >
      <Card sx={{ width: "100%", maxWidth: 440 }} data-testid="accept-card">
        <CardContent>
          <Stack spacing={2} component="form" onSubmit={handleSubmit} noValidate>
            <Stack direction="row" spacing={1} sx={{ alignItems: "center" }}>
              <MarkEmailReadIcon color="primary" />
              <Typography variant="h6">Join ShipperDog testing</Typography>
            </Stack>
            <Typography variant="body2" color="text.secondary">
              Choose your name and a password to activate your tester account.
            </Typography>
            <TextField
              label="Full name"
              value={fullName}
              onChange={(event) => setFullName(event.target.value)}
              required
              autoComplete="name"
              error={Boolean(fieldErrors.fullName)}
              helperText={fieldErrors.fullName}
              slotProps={{ htmlInput: { "data-testid": "accept-name" } }}
            />
            <TextField
              label="Email (as invited)"
              type="email"
              value={email}
              onChange={(event) => setEmail(event.target.value)}
              required
              autoComplete="username"
              error={Boolean(fieldErrors.email)}
              helperText={fieldErrors.email}
              slotProps={{ htmlInput: { "data-testid": "accept-email" } }}
            />
            <TextField
              label={`Password (${MIN_PASSWORD}+ characters)`}
              type="password"
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              required
              autoComplete="new-password"
              error={Boolean(fieldErrors.password)}
              helperText={fieldErrors.password}
              slotProps={{ htmlInput: { "data-testid": "accept-password" } }}
            />
            <TextField
              label="Confirm password"
              type="password"
              value={confirm}
              onChange={(event) => setConfirm(event.target.value)}
              required
              autoComplete="new-password"
              error={Boolean(fieldErrors.confirm)}
              helperText={fieldErrors.confirm}
              slotProps={{ htmlInput: { "data-testid": "accept-confirm" } }}
            />
            {error ? <Alert severity="error" data-testid="accept-error">{error}</Alert> : null}
            <Button
              type="submit"
              variant="contained"
              disabled={submitting}
              data-testid="accept-submit"
            >
              {submitting ? "Activating..." : "Activate and sign in"}
            </Button>
          </Stack>
        </CardContent>
      </Card>
    </Box>
  );
}

function MissingTokenCard() {
  // SPA navigation, not a full document reload (issue #39's sweep of
  // the #36 review finding): the portal keeps its in-memory router
  // state and loads no second copy of the bundle.
  const navigate = useNavigate();
  return (
    <Box
      sx={{
        minHeight: "100vh",
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
        backgroundColor: "grey.100",
        p: 2,
      }}
    >
      <Card sx={{ width: "100%", maxWidth: 440 }} data-testid="accept-missing-token">
        <CardContent>
          <Stack spacing={2}>
            <Stack direction="row" spacing={1} sx={{ alignItems: "center" }}>
              <MarkEmailReadIcon color="action" />
              <Typography variant="h6">Invitation link required</Typography>
            </Stack>
            <Typography variant="body2" color="text.secondary">
              Open the invitation link from your email to set a password and
              join testing. Links are single-use and expire after seven days.
            </Typography>
            <Button
              variant="contained"
              onClick={() => navigate("/login")}
              data-testid="accept-go-login"
            >
              Go to sign in
            </Button>
          </Stack>
        </CardContent>
      </Card>
    </Box>
  );
}
