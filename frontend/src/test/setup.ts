import "@testing-library/jest-dom/vitest";

// x-data-grid measures its viewport with ResizeObserver, which happy-dom
// does not implement; a inert stand-in is enough for rendering.
if (typeof globalThis.ResizeObserver !== "function") {
  class ResizeObserverStub implements ResizeObserver {
    observe(): void {}
    unobserve(): void {}
    disconnect(): void {}
  }
  globalThis.ResizeObserver = ResizeObserverStub as unknown as typeof ResizeObserver;
}

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
