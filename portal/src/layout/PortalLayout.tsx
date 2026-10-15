/**
 * Authenticated portal shell (issue #35): app bar with the tester's
 * identity and logout, and the content area the S8 stories fill in —
 * the navigation placeholder is honest (My Applications arrives with
 * S8-02). Mobile-first: a single column that never exceeds a readable
 * width on desktop.
 */
import LogoutIcon from "@mui/icons-material/Logout";
import { AppBar, Box, Container, IconButton, Toolbar, Typography } from "@mui/material";
import { Outlet, useNavigate } from "react-router-dom";
import { useAuth } from "../auth/AuthContext";

export default function PortalLayout() {
  const { user, logout } = useAuth();
  const navigate = useNavigate();

  async function handleLogout() {
    await logout();
    navigate("/login", { replace: true });
  }

  return (
    <Box sx={{ minHeight: "100vh", backgroundColor: "grey.100", display: "flex", flexDirection: "column" }}>
      <AppBar position="static" color="primary">
        <Toolbar>
          <Box
            component="img"
            src="/logo-mark-192.png"
            alt=""
            aria-hidden="true"
            sx={{ width: 28, height: 28, mr: 1.5, borderRadius: 0.5 }}
          />
          <Typography variant="h6" component="div" sx={{ flexGrow: 1, fontSize: { xs: "1.05rem", sm: "1.25rem" } }}>
            ShipperDog Tester Portal
          </Typography>
          {user ? (
            <Typography
              variant="body2"
              sx={{ mr: { xs: 0.5, sm: 2 }, display: { xs: "none", sm: "block" }, opacity: 0.9 }}
              data-testid="portal-user"
            >
              {user.fullName || user.email}
            </Typography>
          ) : null}
          <IconButton
            color="inherit"
            onClick={handleLogout}
            aria-label="Log out"
            data-testid="portal-logout"
            size="large"
          >
            <LogoutIcon />
          </IconButton>
        </Toolbar>
      </AppBar>
      <Container component="main" maxWidth="sm" sx={{ py: 3, flexGrow: 1 }}>
        <Outlet />
      </Container>
    </Box>
  );
}
