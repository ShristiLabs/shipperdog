import { Navigate, Route, Routes } from "react-router-dom";
import { AuthProvider } from "./auth/AuthContext";
import { RequireAuth } from "./auth/RequireAuth";
import { ToastProvider } from "./components/ToastContext";
import PortalLayout from "./layout/PortalLayout";
import AcceptInvitationPage from "./pages/AcceptInvitationPage";
import HomePage from "./pages/HomePage";
import LoginPage from "./pages/LoginPage";

/** Portal routes (issue #35). Two public entries — tester login and the
 * invitation-accept link target — and the authenticated shell. There
 * are intentionally NO admin routes here: the portal is a separate
 * build/deploy target from the console (issue AC1/AC5). */
export default function App() {
  return (
    <AuthProvider>
      <ToastProvider>
        <Routes>
          <Route path="/login" element={<LoginPage />} />
          <Route path="/accept-invitation" element={<AcceptInvitationPage />} />
          <Route
            path="/"
            element={
              <RequireAuth>
                <PortalLayout />
              </RequireAuth>
            }
          >
            <Route index element={<HomePage />} />
          </Route>
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </ToastProvider>
    </AuthProvider>
  );
}
