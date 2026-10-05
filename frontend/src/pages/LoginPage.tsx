/** Console login (issue #12): POST /auth/login, then straight to the
 * tenant console. Failures keep the uniform opaque 401 message the API
 * returns (docs/authentication.md — no account-state hints). */
import { Alert, Box, Button, Card, CardContent, Stack, TextField, Typography } from "@mui/material";
import LockIcon from "@mui/icons-material/Lock";
import { useState, type FormEvent } from "react";
import { useLocation, useNavigate } from "react-router-dom";
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
      : "/tenants";

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
      }}
    >
      <Card sx={{ width: 380 }}>
        <CardContent>
          <Stack spacing={2} component="form" onSubmit={handleSubmit} noValidate>
            <Stack direction="row" spacing={1} sx={{ alignItems: "center" }}>
              <LockIcon color="primary" />
              <Typography variant="h6">ShipperDog Console</Typography>
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
          </Stack>
        </CardContent>
      </Card>
    </Box>
  );
}
