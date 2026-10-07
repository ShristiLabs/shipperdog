/**
 * Portal toast convention (issue #35, mirroring the console's): API
 * failures are rendered from the problem+json envelope — the stable
 * `code` and registry `detail` as the message, the `trace_id` beneath
 * it so a tester support report can be joined to server logs. Success
 * toasts confirm mutations. This is the single error surface every
 * portal screen uses.
 */
import Alert from "@mui/material/Alert";
import Snackbar from "@mui/material/Snackbar";
import {
  createContext,
  useCallback,
  useContext,
  useMemo,
  useState,
  type ReactNode,
} from "react";
import { ApiError } from "../api/problem";

interface Toast {
  severity: "error" | "success";
  message: string;
  traceId?: string;
}

interface ToastContextValue {
  showError: (error: unknown) => void;
  showSuccess: (message: string) => void;
}

const ToastContext = createContext<ToastContextValue | null>(null);

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toast, setToast] = useState<Toast | null>(null);

  const showError = useCallback((error: unknown) => {
    if (error instanceof ApiError) {
      setToast({
        severity: "error",
        message: error.code
          ? `${error.title ?? "Request failed"} (${error.code}): ${error.detail ?? ""}`.trim()
          : error.message,
        traceId: error.traceId,
      });
      return;
    }
    setToast({ severity: "error", message: "Unexpected error. Please retry." });
  }, []);

  const showSuccess = useCallback((message: string) => {
    setToast({ severity: "success", message });
  }, []);

  const value = useMemo(() => ({ showError, showSuccess }), [showError, showSuccess]);

  return (
    <ToastContext.Provider value={value}>
      {children}
      <Snackbar
        open={toast !== null}
        autoHideDuration={toast?.severity === "error" ? 10_000 : 4_000}
        onClose={() => setToast(null)}
        anchorOrigin={{ vertical: "bottom", horizontal: "center" }}
      >
        {toast ? (
          <Alert
            severity={toast.severity}
            variant="filled"
            onClose={() => setToast(null)}
            sx={{ maxWidth: 560 }}
          >
            {toast.message}
            {toast.traceId ? (
              <span style={{ display: "block", fontSize: "0.75rem", opacity: 0.8 }}>
                trace {toast.traceId}
              </span>
            ) : null}
          </Alert>
        ) : undefined}
      </Snackbar>
    </ToastContext.Provider>
  );
}

export function useToast(): ToastContextValue {
  const context = useContext(ToastContext);
  if (!context) {
    throw new Error("useToast must be used inside ToastProvider");
  }
  return context;
}
