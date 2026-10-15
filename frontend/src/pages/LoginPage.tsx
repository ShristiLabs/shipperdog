/** Console login (issue #12): POST /auth/login, then straight to the
 * tenant console. Failures keep the uniform opaque 401 message the API
 * returns (docs/authentication.md — no account-state hints). */
import { Alert, Box, Button, Card, CardContent, Divider, Stack, TextField, Typography } from "@mui/material";
import { useState, type FormEvent } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import { useAuth } from "../auth/AuthContext";
import { ApiError } from "../api/problem";
import { ssoOptionsRequest } from "../api/auth";

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
  const [ssoOrganizationId, setSsoOrganizationId] = useState("");
  const [ssoBusy, setSsoBusy] = useState(false);

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

  /** SSO (S14-01): probe the organization, then hand the browser to
   * the backend-driven authorize redirect (full-page navigation -- the
   * flow continues at the identity provider and lands on /sso/callback). */
  async function handleSso(event: FormEvent) {
    event.preventDefault();
    setError(null);
    setSsoBusy(true);
    try {
      await ssoOptionsRequest(ssoOrganizationId.trim());
      window.location.href =
        "/api/v1/auth/sso/authorize?organizationId=" + encodeURIComponent(ssoOrganizationId.trim());
    } catch (cause) {
      setError(
        cause instanceof ApiError && cause.status === 404
          ? "Single sign-on is not available for this organization."
          : "Single sign-on could not be started. Please retry.",
      );
      setSsoBusy(false);
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
              <Box
                component="img"
                src="/logo-mark-192.png"
                alt=""
                aria-hidden="true"
                sx={{ width: 32, height: 32, borderRadius: 0.5 }}
              />
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
          <Divider sx={{ my: 2 }}>or</Divider>
          <Stack spacing={2} component="form" onSubmit={handleSso} noValidate data-testid="sso-panel">
            <TextField
              label="Organization ID (single sign-on)"
              value={ssoOrganizationId}
              onChange={(event) => setSsoOrganizationId(event.target.value)}
              required
              slotProps={{ htmlInput: { "data-testid": "sso-organization-input" } }}
            />
            <Button
              type="submit"
              variant="outlined"
              disabled={ssoBusy || status === "loading"}
              data-testid="sso-submit"
            >
              {ssoBusy ? "Redirecting..." : "Continue with SSO"}
            </Button>
          </Stack>
        </CardContent>
      </Card>
    </Box>
  );
}
