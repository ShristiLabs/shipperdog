/**
 * Portal home (issue #35): the authenticated landing screen. S8-01
 * delivers the scaffold only — the My Applications catalog arrives with
 * S8-02, so this page renders an honest empty state. A tester with no
 * memberships or grants is EXPECTED to see nothing here (INV-005
 * resting state, docs/authorization.md): deny-by-default means an empty
 * portal, not an error.
 */
import { Card, CardContent, Stack, Typography } from "@mui/material";
import DevicesIcon from "@mui/icons-material/Devices";
import { useAuth } from "../auth/AuthContext";

export default function HomePage() {
  const { user } = useAuth();
  const firstName = (user?.fullName ?? "").trim().split(/\s+/)[0];

  return (
    <Stack spacing={2}>
      <Typography variant="h5" component="h1" data-testid="portal-home-title">
        {firstName ? `Welcome, ${firstName}` : "Welcome"}
      </Typography>
      <Card data-testid="portal-home-empty">
        <CardContent>
          <Stack spacing={1.5} sx={{ alignItems: "center", textAlign: "center", py: 3 }}>
            <DevicesIcon color="primary" sx={{ fontSize: 40 }} />
            <Typography variant="h6">My Applications</Typography>
            <Typography variant="body2" color="text.secondary" sx={{ maxWidth: 360 }}>
              Apps you are authorized to test will appear here. Nothing is
              shared with you yet — ask the team to add you to a tenant or a
              tester group.
            </Typography>
          </Stack>
        </CardContent>
      </Card>
    </Stack>
  );
}
