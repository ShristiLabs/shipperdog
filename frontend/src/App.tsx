import { Navigate, Route, Routes } from "react-router-dom";
import { AuthProvider } from "./auth/AuthContext";
import { RequireAuth } from "./auth/RequireAuth";
import { ToastProvider } from "./components/ToastContext";
import ConsoleLayout from "./layout/ConsoleLayout";
import ApprovalWorkflowsPage from "./pages/ApprovalWorkflowsPage";
import ApplicationDetailPage from "./pages/ApplicationDetailPage";
import ApplicationsPage from "./pages/ApplicationsPage";
import DashboardPage from "./pages/DashboardPage";
import LoginPage from "./pages/LoginPage";
import NotificationsPage from "./pages/NotificationsPage";
import ReleaseDetailPage from "./pages/ReleaseDetailPage";
import ReleasePoliciesPage from "./pages/ReleasePoliciesPage";
import RingsPage from "./pages/RingsPage";
import ChannelsPage from "./pages/ChannelsPage";
import ReleasesPage from "./pages/ReleasesPage";
import TenantDashboardPage from "./pages/TenantDashboardPage";
import TenantDevicesPage from "./pages/TenantDevicesPage";
import TenantMembersPage from "./pages/TenantMembersPage";
import TenantsPage from "./pages/TenantsPage";

export default function App() {
  return (
    <AuthProvider>
      <ToastProvider>
        <Routes>
          <Route path="/login" element={<LoginPage />} />
          <Route
            path="/"
            element={
              <RequireAuth>
                <ConsoleLayout />
              </RequireAuth>
            }
          >
            <Route index element={<Navigate to="/dashboard" replace />} />
            <Route path="dashboard" element={<DashboardPage />} />
            <Route path="tenants" element={<TenantsPage />} />
            <Route path="tenants/:tenantId/members" element={<TenantMembersPage />} />
            <Route path="tenants/:tenantId/devices" element={<TenantDevicesPage />} />
            <Route path="tenants/:tenantId/dashboard" element={<TenantDashboardPage />} />
            <Route path="applications" element={<ApplicationsPage />} />
            <Route path="applications/:applicationId" element={<ApplicationDetailPage />} />
            <Route path="approval-workflows" element={<ApprovalWorkflowsPage />} />
            <Route path="rings" element={<RingsPage />} />
          <Route path="channels" element={<ChannelsPage />} />
            <Route path="notifications" element={<NotificationsPage />} />
            <Route path="release-policies" element={<ReleasePoliciesPage />} />
            <Route path="releases" element={<ReleasesPage />} />
            <Route path="releases/:applicationId" element={<ReleasesPage />} />
            <Route
              path="releases/:applicationId/:releaseId"
              element={<ReleaseDetailPage />}
            />
          </Route>
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </ToastProvider>
    </AuthProvider>
  );
}
