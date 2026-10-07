import { Navigate, Route, Routes } from "react-router-dom";
import { AuthProvider } from "./auth/AuthContext";
import { RequireAuth } from "./auth/RequireAuth";
import { ToastProvider } from "./components/ToastContext";
import ConsoleLayout from "./layout/ConsoleLayout";
import ApplicationDetailPage from "./pages/ApplicationDetailPage";
import ApplicationsPage from "./pages/ApplicationsPage";
import LoginPage from "./pages/LoginPage";
import ReleaseDetailPage from "./pages/ReleaseDetailPage";
import ReleasesPage from "./pages/ReleasesPage";
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
            <Route index element={<Navigate to="/tenants" replace />} />
            <Route path="tenants" element={<TenantsPage />} />
            <Route path="tenants/:tenantId/members" element={<TenantMembersPage />} />
            <Route path="applications" element={<ApplicationsPage />} />
            <Route path="applications/:applicationId" element={<ApplicationDetailPage />} />
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
