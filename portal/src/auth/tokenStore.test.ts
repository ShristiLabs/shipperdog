/** Session storage tests (issue #35): atomic single-key writes under the
 * portal's own storage key — a tester and a console operator on the
 * same browser never clobber each other's sessions. */
import { afterEach, describe, expect, it } from "vitest";
import { tokenStore, type StoredSession } from "./tokenStore";

const CONSOLE_KEY = "sd.console.session";

afterEach(() => {
  localStorage.clear();
});

describe("tokenStore", () => {
  it("stores and reloads a session under the portal key", () => {
    tokenStore.save("a", "r", 900);
    const session: StoredSession | null = tokenStore.get();
    expect(session?.accessToken).toBe("a");
    expect(session?.refreshToken).toBe("r");
    expect(session?.accessExpiresAt).toBeGreaterThan(Date.now());
    expect(localStorage.getItem("sd.portal.session")).toContain("a");
  });

  it("never touches the console's session key", () => {
    localStorage.setItem(CONSOLE_KEY, JSON.stringify({ operator: true }));
    tokenStore.save("a", "r", 900);
    tokenStore.clear();
    expect(localStorage.getItem(CONSOLE_KEY)).toBe(JSON.stringify({ operator: true }));
    expect(localStorage.getItem("sd.portal.session")).toBeNull();
  });

  it("treats corrupt storage as no session and notifies subscribers on writes", () => {
    const seen: number[] = [];
    const unsubscribe = tokenStore.subscribe(() => seen.push(seen.length));
    localStorage.setItem("sd.portal.session", "{not json");
    expect(tokenStore.get()).toBeNull();
    tokenStore.save("a", "r", 1);
    expect(seen).toHaveLength(1);
    tokenStore.clear();
    expect(seen).toHaveLength(2);
    unsubscribe();
  });
});
