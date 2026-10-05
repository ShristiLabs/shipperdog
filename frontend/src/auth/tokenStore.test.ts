/** Session storage behavior: atomic save/clear and listener notification. */
import { describe, expect, it, vi } from "vitest";
import { tokenStore } from "./tokenStore";

describe("tokenStore", () => {
  it("round-trips a session and notifies subscribers on save and clear", () => {
    const listener = vi.fn();
    const unsubscribe = tokenStore.subscribe(listener);

    tokenStore.save("access", "refresh", 900);
    const session = tokenStore.get();
    expect(session?.accessToken).toBe("access");
    expect(session?.refreshToken).toBe("refresh");
    expect(session?.accessExpiresAt).toBeGreaterThan(Date.now() + 890_000);

    tokenStore.clear();
    expect(tokenStore.get()).toBeNull();

    expect(listener).toHaveBeenCalledTimes(2);
    unsubscribe();
  });

  it("treats corrupt storage as no session", () => {
    localStorage.setItem("sd.console.session", "{not json");
    expect(tokenStore.get()).toBeNull();
    tokenStore.clear();
  });
});
