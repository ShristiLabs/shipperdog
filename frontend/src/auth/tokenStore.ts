/**
 * Token storage (issue #12). Access and refresh tokens live in
 * localStorage under a single key so login/logout/session-expiry all
 * reduce to one atomic write. M1 accepts the localStorage XSS trade-off
 * documented in docs/frontend.md; the API never trusts the client for
 * authorization, so a stolen token grants only what its holder could do.
 */
const STORAGE_KEY = "sd.console.session";

export interface StoredSession {
  accessToken: string;
  refreshToken: string;
  /** Epoch milliseconds when the access token expires. */
  accessExpiresAt: number;
}

type Listener = () => void;
const listeners = new Set<Listener>();

function read(): StoredSession | null {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as Partial<StoredSession>;
    if (
      typeof parsed.accessToken === "string" &&
      typeof parsed.refreshToken === "string" &&
      typeof parsed.accessExpiresAt === "number" &&
      parsed.accessToken &&
      parsed.refreshToken
    ) {
      return {
        accessToken: parsed.accessToken,
        refreshToken: parsed.refreshToken,
        accessExpiresAt: parsed.accessExpiresAt,
      };
    }
  } catch {
    // Corrupt storage is equivalent to no session.
  }
  return null;
}

function write(session: StoredSession | null): void {
  if (session) {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(session));
  } else {
    localStorage.removeItem(STORAGE_KEY);
  }
  for (const listener of listeners) listener();
}

export const tokenStore = {
  get(): StoredSession | null {
    return read();
  },
  save(accessToken: string, refreshToken: string, expiresInSeconds: number): void {
    write({
      accessToken,
      refreshToken,
      accessExpiresAt: Date.now() + expiresInSeconds * 1000,
    });
  },
  clear(): void {
    write(null);
  },
  subscribe(listener: Listener): () => void {
    listeners.add(listener);
    return () => listeners.delete(listener);
  },
};
