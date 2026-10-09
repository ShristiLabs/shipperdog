/** The series-folding rule (issue #50, the S11-04 follow-up): points
 * arrive per (bucket, release, ring); COUNT metrics sum across the
 * contributing rows, RATE metrics average — never sum. */
import { describe, expect, it } from "vitest";
import { foldSeriesPoints, isCountMetric, type MetricPoint } from "./analytics";

function point(bucket: string, value: number, release: string, ring: string | null): MetricPoint {
  return { bucketStart: bucket, value, releaseRef: release, ringCode: ring };
}

describe("foldSeriesPoints", () => {
  it("sums count metrics across releases and rings in the same bucket", () => {
    const folded = foldSeriesPoints(
      [
        point("2026-10-01T00:00:00Z", 4, "R-1", "qa"),
        point("2026-10-01T00:00:00Z", 2, "R-1", "production"),
        point("2026-10-01T00:00:00Z", 6, "R-2", "qa"),
        point("2026-10-02T00:00:00Z", 3, "R-1", "qa"),
      ],
      "DOWNLOADS",
    );
    expect(folded).toEqual([
      { bucketStart: "2026-10-01T00:00:00Z", value: 12 },
      { bucketStart: "2026-10-02T00:00:00Z", value: 3 },
    ]);
  });

  it("averages rate metrics — never sums them", () => {
    const folded = foldSeriesPoints(
      [
        point("2026-10-01T00:00:00Z", 0.98, "R-1", "qa"),
        point("2026-10-01T00:00:00Z", 1.0, "R-2", "qa"),
        point("2026-10-02T00:00:00Z", 0.9, "R-1", "qa"),
      ],
      "CRASH_FREE_SESSIONS",
    );
    // Summing would answer 1.98 (and 1.9) — the classic dashboard bug.
    expect(folded).toHaveLength(2);
    expect(folded[0]!.bucketStart).toBe("2026-10-01T00:00:00Z");
    expect(folded[0]!.value).toBeCloseTo(0.99, 10);
    expect(folded[1]!.bucketStart).toBe("2026-10-02T00:00:00Z");
    expect(folded[1]!.value).toBeCloseTo(0.9, 10);
  });

  it("orders buckets oldest-first regardless of arrival order", () => {
    const folded = foldSeriesPoints(
      [point("2026-10-03T00:00:00Z", 1, "R-1", null), point("2026-10-01T00:00:00Z", 2, "R-1", null)],
      "DOWNLOADS",
    );
    expect(folded.map((entry) => entry.bucketStart)).toEqual([
      "2026-10-01T00:00:00Z",
      "2026-10-03T00:00:00Z",
    ]);
  });

  it("answers an empty series empty (the honest no-data shape)", () => {
    expect(foldSeriesPoints([], "DOWNLOADS")).toEqual([]);
  });
});

describe("isCountMetric", () => {
  it("classifies the dictionary's count metrics", () => {
    expect(isCountMetric("DOWNLOADS")).toBe(true);
    expect(isCountMetric("INSTALLATIONS")).toBe(true);
    expect(isCountMetric("CRASH_FREE_SESSIONS")).toBe(false);
    expect(isCountMetric("TESTER_ENGAGEMENT")).toBe(false);
  });
});
