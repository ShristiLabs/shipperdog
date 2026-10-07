/** Tester login (issue #35): POST /auth/login, then straight to the
 * portal home. Failures keep the uniform opaque 401 message the API
 * returns (docs/authentication.md — suspended and deactivated testers
 * are indistinguishable from wrong passwords; no account-state hints). */
import { Alert, Box, Button, Card, CardContent, Link, Stack, TextField, Typography } from "@mui/material";
import LockIcon from "@mui/icons-material/Lock";
import { useState, type FormEvent } from "react";
import { Link as RouterLink, useLocation, useNavigate } from "react-router-dom";
import { useAuth } from "../auth/AuthContext";
import { ApiError } from "../api/problem";

export default function LoginPage() {
  const { login, status } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const from =
    typeof location.state === "object" &&
    location.state !== null &&
    "from" in location.state &&
    typeof location.state.from === "string"
      ? location.state.from
      : "/";

  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      await login(email, password);
      navigate(from, { replace: true });
    } catch (cause) {
      setError(
        cause instanceof ApiError
          ? "Sign-in failed: " + (cause.detail ?? cause.title ?? "invalid credentials")
          : "Sign-in failed. Please retry.",
      );
    } finally {
      setSubmitting(false);
    }
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
      <Card sx={{ width: "100%", maxWidth: 400 }} data-testid="portal-login-card">
        <CardContent>
          <Stack spacing={2} component="form" onSubmit={handleSubmit} noValidate>
            <Stack direction="row" spacing={1} sx={{ alignItems: "center" }}>
              <LockIcon color="primary" />
              <Typography variant="h6">ShipperDog Tester Portal</Typography>
            </Stack>
            <TextField
              label="Email"
              type="email"
              value={email}
              onChange={(event) => setEmail(event.target.value)}
              required
              autoComplete="username"
              autoFocus
              slotProps={{ htmlInput: { "data-testid": "login-email" } }}
            />
            <TextField
              label="Password"
              type="password"
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              required
              autoComplete="current-password"
              slotProps={{ htmlInput: { "data-testid": "login-password" } }}
            />
            {error ? <Alert severity="error" data-testid="login-error">{error}</Alert> : null}
            <Button
              type="submit"
              variant="contained"
              disabled={submitting || status === "loading"}
              data-testid="login-submit"
            >
              {submitting ? "Signing in..." : "Sign in"}
            </Button>
            <Typography variant="body2" color="text.secondary" align="center">
              Invited to test?{" "}
              <Link component={RouterLink} to="/accept-invitation" data-testid="login-accept-link">
                Set your password
              </Link>
            </Typography>
          </Stack>
        </CardContent>
      </Card>
    </Box>
  );
}
