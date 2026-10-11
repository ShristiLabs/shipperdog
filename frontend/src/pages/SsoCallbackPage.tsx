/** SSO landing route (S14-01, S14-02): the API's provider callbacks
 * (the OIDC callback and the SAML ACS alike) redirect here with a
 * one-time session code; this page exchanges it for the token pair
 * and enters the console. A failure redirect carries only error=sso --
 * the distinct reason lives on the backend's audit line, never in the
 * URL. */
import { Alert, Box, Button, Card, CardContent, Stack, Typography } from "@mui/material";
import LoginIcon from "@mui/icons-material/Login";
import { useEffect, useRef, useState } from "react";
import { Link, useNavigate, useSearchParams } from "react-router-dom";
import { useAuth } from "../auth/AuthContext";
import { ssoExchangeRequest } from "../api/auth";

export default function SsoCallbackPage() {
  const { adoptTokens } = useAuth();
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const [error, setError] = useState<string | null>(() =>
    params.get("code") ? null : "Single sign-on could not be completed. Please retry from the sign-in page.",
  );
  const exchanged = useRef(false);

  useEffect(() => {
    const code = params.get("code");
    if (code && !exchanged.current) {
      exchanged.current = true;
      void ssoExchangeRequest(code)
        .then(async (pair) => {
          await adoptTokens(pair);
          navigate("/dashboard", { replace: true });
        })
        .catch(() => setError("Single sign-on could not be completed. Please retry from the sign-in page."));
    }
  }, [params, adoptTokens, navigate]);

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
          <Stack spacing={2} sx={{ alignItems: "center" }}>
            <LoginIcon color="primary" />
            <Typography variant="h6">Signing you in</Typography>
            {error ? (
              <>
                <Alert severity="error" data-testid="sso-callback-error">
                  {error}
                </Alert>
                <Button component={Link} to="/login" variant="contained" data-testid="sso-callback-retry">
                  Back to sign in
                </Button>
              </>
            ) : (
              <Typography color="text.secondary" data-testid="sso-callback-pending">
                Completing single sign-on...
              </Typography>
            )}
          </Stack>
        </CardContent>
      </Card>
    </Box>
  );
}
