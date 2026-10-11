/**
 * Authentication context (issue #12). Owns the session lifecycle:
 * login/logout, the current actor (GET /auth/me), and the silent
 * refresh timer that rotates the access token one minute before expiry
 * (docs/authentication.md — refresh rotation is single-use, so every
 * refresh stores the new pair atomically via the token store).
 */
import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from "react";
import { apiFetch, ensureFreshTokens, SESSION_EXPIRED_EVENT } from "../api/client";
import { loginRequest, logoutRequest, type CurrentUser } from "../api/auth";
import { tokenStore } from "./tokenStore";

/** Refresh this many milliseconds before actual expiry. */
const REFRESH_LEAD_MS = 60_000;

type AuthStatus = "loading" | "authenticated" | "anonymous";

interface AuthContextValue {
  status: AuthStatus;
  user: CurrentUser | null;
  login: (email: string, password: string) => Promise<void>;
  /** Adopts an already-minted pair (the SSO exchange, S14-01). */
  adoptTokens: (pair: import("../api/auth").TokenPair) => Promise<void>;
  logout: () => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  // "loading" only when a stored session exists to validate; otherwise
  // the console starts anonymous without a render flash.
  const [status, setStatus] = useState<AuthStatus>(() =>
    tokenStore.get() ? "loading" : "anonymous",
  );
  const [user, setUser] = useState<CurrentUser | null>(null);
  const refreshTimer = useRef<number | null>(null);

  const armSilentRefresh = useCallback(() => {
    if (refreshTimer.current !== null) {
      window.clearTimeout(refreshTimer.current);
      refreshTimer.current = null;
    }
    const session = tokenStore.get();
    if (!session) return;
    const delay = session.accessExpiresAt - REFRESH_LEAD_MS - Date.now();
    if (delay <= 0) {
      void ensureFreshTokens();
      return;
    }
    refreshTimer.current = window.setTimeout(() => {
      void ensureFreshTokens();
    }, delay);
  }, []);

  const loadMe = useCallback(async () => {
    const me = await apiFetch<CurrentUser>("/auth/me");
    setUser(me);
    setStatus("authenticated");
  }, []);

  // Validate a stored session exactly once on mount: load /me, then arm
  // the silent refresh timer. State changes happen only after the await,
  // never synchronously inside the effect.
  useEffect(() => {
    let cancelled = false;
    if (!tokenStore.get()) return;
    void (async () => {
      try {
        await loadMe();
        if (!cancelled) armSilentRefresh();
      } catch {
        if (!cancelled) {
          setUser(null);
          setStatus("anonymous");
        }
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [loadMe, armSilentRefresh]);

  // Re-arm the timer whenever the stored pair changes (login, silent
  // refresh, retry-driven refresh) and drop to anonymous when the
  // session dies mid-flight.
  useEffect(
    () =>
      tokenStore.subscribe(() => {
        if (!tokenStore.get()) {
          setUser(null);
          setStatus("anonymous");
          return;
        }
        armSilentRefresh();
      }),
    [armSilentRefresh],
  );

  useEffect(() => {
    const onExpired = () => {
      setUser(null);
      setStatus("anonymous");
    };
    window.addEventListener(SESSION_EXPIRED_EVENT, onExpired);
    return () => window.removeEventListener(SESSION_EXPIRED_EVENT, onExpired);
  }, []);

  const login = useCallback(
    async (email: string, password: string) => {
      const pair = await loginRequest(email, password);
      tokenStore.save(pair.accessToken, pair.refreshToken, pair.expiresInSeconds);
      await loadMe();
      armSilentRefresh();
    },
    [loadMe, armSilentRefresh],
  );

  const adoptTokens = useCallback(
    async (pair: import("../api/auth").TokenPair) => {
      tokenStore.save(pair.accessToken, pair.refreshToken, pair.expiresInSeconds);
      await loadMe();
      armSilentRefresh();
    },
    [loadMe, armSilentRefresh],
  );

  const logout = useCallback(async () => {
    const session = tokenStore.get();
    if (session) {
      await logoutRequest(session.refreshToken);
    }
    tokenStore.clear();
  }, []);

  const value = useMemo(
    () => ({ status, user, login, adoptTokens, logout }),
    [status, user, login, adoptTokens, logout],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error("useAuth must be used inside AuthProvider");
  }
  return context;
}
