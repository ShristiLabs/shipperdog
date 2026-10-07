import "@testing-library/jest-dom/vitest";

// happy-dom lacks crypto.randomUUID (available in every target browser).
if (typeof crypto.randomUUID !== "function") {
  Object.defineProperty(globalThis.crypto, "randomUUID", {
    value: () =>
      "00000000-0000-4000-8000-000000000000".replace(
        /[0348]/g,
        (c) => ((Number(c) * 16) | 0).toString(16),
      ) satisfies string,
  });
}
