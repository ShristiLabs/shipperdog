/**
 * Console shell (issue #12): persistent app bar with the signed-in
 * actor and a navigation drawer whose sections render only what the
 * backend permits for this user (usePermission probes — PRD §26: the
 * backend enforces every call regardless; the drawer only decides what
 * to show). Users without an active organization see a notice instead
 * of admin sections they could never load.
 */
import AppsIcon from "@mui/icons-material/Apps";
import BusinessIcon from "@mui/icons-material/Business";
import LogoutIcon from "@mui/icons-material/Logout";
import RocketLaunchIcon from "@mui/icons-material/RocketLaunch";
import {
  Alert,
  AppBar,
  Box,
  Divider,
  Drawer,
  IconButton,
  List,
  ListItemButton,
  ListItemIcon,
  ListItemText,
  Toolbar,
  Tooltip,
  Typography,
} from "@mui/material";
import { NavLink, Outlet, useNavigate } from "react-router-dom";
import { useAuth } from "../auth/AuthContext";
import { usePermission } from "../hooks/usePermission";
import { listApplications } from "../api/applications";
import { listTenants } from "../api/tenants";

const DRAWER_WIDTH = 240;

export default function ConsoleLayout() {
  const { user, logout } = useAuth();
  const navigate = useNavigate();
  const orgId = user?.orgId ?? null;

  const tenantsNav = usePermission(
    `tenants:${orgId ?? "none"}`,
    () => listTenants({ organizationId: orgId as string, limit: 1 }),
    orgId !== null,
  );

  // The Applications section probes the organization-wide registry
  // listing it loads first (issue #16): organization roles with
  // app:applications:read see it; tenant-only actors are conceal-denied
  // and the section hides. Per-application reads and every write stay
  // server-enforced regardless.
  const applicationsNav = usePermission(
    `applications:${orgId ?? "none"}`,
    () => listApplications({ organizationId: orgId as string, limit: 1 }),
    orgId !== null,
  );

  async function handleLogout() {
    await logout();
    navigate("/login", { replace: true });
  }

  return (
    <Box sx={{ display: "flex", minHeight: "100vh" }}>
      <AppBar position="fixed" sx={{ zIndex: (theme) => theme.zIndex.drawer + 1 }}>
        <Toolbar>
          <Typography variant="h6" component="div" sx={{ flexGrow: 1 }}>
            ShipperDog Console
          </Typography>
          <Typography variant="body2" sx={{ marginRight: 2 }} data-testid="console-user">
            {user?.email}
          </Typography>
          <Tooltip title="Sign out">
            <IconButton color="inherit" onClick={handleLogout} aria-label="Sign out" data-testid="logout-button">
              <LogoutIcon />
            </IconButton>
          </Tooltip>
        </Toolbar>
      </AppBar>
      <Drawer
        variant="permanent"
        sx={{
          width: DRAWER_WIDTH,
          flexShrink: 0,
          "& .MuiDrawer-paper": { width: DRAWER_WIDTH, boxSizing: "border-box" },
        }}
      >
        <Toolbar />
        <Box sx={{ overflow: "auto", flexGrow: 1 }}>
          {orgId === null ? (
            <Alert severity="info" sx={{ margin: 2 }}>
              Your account has no active organization membership, so administration
              sections are unavailable.
            </Alert>
          ) : tenantsNav.allowed ? (
            <List>
              <ListItemButton
                component={NavLink}
                to="/tenants"
                data-testid="nav-tenants"
                sx={{ "&.active": { backgroundColor: "action.selected" } }}
              >
                <ListItemIcon>
                  <BusinessIcon />
                </ListItemIcon>
                <ListItemText primary="Tenants" />
              </ListItemButton>
            </List>
          ) : (
            <Alert severity="info" sx={{ margin: 2 }}>
              You do not have permission to administer tenants.
            </Alert>
          )}
          {orgId !== null && applicationsNav.allowed ? (
            <List>
              <ListItemButton
                component={NavLink}
                to="/applications"
                data-testid="nav-applications"
                sx={{ "&.active": { backgroundColor: "action.selected" } }}
              >
                <ListItemIcon>
                  <AppsIcon />
                </ListItemIcon>
                <ListItemText primary="Applications" />
              </ListItemButton>
            </List>
          ) : null}
          {/* The Releases dashboard (S9-05) renders under the same
              applications probe: every role holding app:applications:read
              also holds app:releases:read (the authorization matrix), and
              the screen is unusable without the registry picker anyway.
              The per-application release listing stays server-enforced. */}
          {orgId !== null && applicationsNav.allowed ? (
            <List>
              <ListItemButton
                component={NavLink}
                to="/releases"
                data-testid="nav-releases"
                sx={{ "&.active": { backgroundColor: "action.selected" } }}
              >
                <ListItemIcon>
                  <RocketLaunchIcon />
                </ListItemIcon>
                <ListItemText primary="Releases" />
              </ListItemButton>
            </List>
          ) : null}
          <Divider />
        </Box>
      </Drawer>
      <Box component="main" sx={{ flexGrow: 1, padding: 3, marginTop: 8 }}>
        <Outlet />
      </Box>
    </Box>
  );
}
