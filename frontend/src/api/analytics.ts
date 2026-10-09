/** Release-analytics series API (docs/analytics.md, S11-04) and the
 * series-folding rule its points demand (S11-05): the platform serves
 * points per (bucket, release, ring) row and refuses to guess an
 * aggregation across releases — the CONSUMER folds. Counts sum; rates
 * never do (they average here — the recomputable-from-points rule;
 * summing rates is the classic dashboard bug). */
import { pageFetch, type Page } from "./client";

export type MetricGrain = "hour" | "day";

/** The dictionary metrics whose unit is a COUNT: folding sums them. */
export const COUNT_METRICS: readonly string[] = ["DOWNLOADS", "INSTALLATIONS"];

export function isCountMetric(metric: string): boolean {
  return COUNT_METRICS.includes(metric);
}

/** One point of a series: the bucket, the value, and the row's
 * release/ring dimensions (the platform serves one row per
 * bucket x release x ring). */
export interface MetricPoint {
  bucketStart: string;
  value: number;
  releaseRef: string;
  ringCode: string | null;
}

export interface SeriesParams {
  tenantId?: string;
  applicationId?: string;
  metric: string;
  from: string;
  to: string;
  grain?: MetricGrain;
  ring?: string;
  releaseId?: string;
  limit?: number;
  cursor?: string;
}

export function listMetricsSeries(params: SeriesParams): Promise<Page<MetricPoint>> {
  const { tenantId, applicationId, ...rest } = params;
  const path = tenantId ? `/tenants/${tenantId}/metrics` : `/applications/${applicationId}/metrics`;
  return pageFetch<MetricPoint>(path, rest);
}

/** One folded point: the bucket and the value every contributing row
 * folds into. */
export interface FoldedPoint {
  bucketStart: string;
  value: number;
}

/**
 * The folding rule (the S11-04 follow-up the dashboards own): group
 * the served points by bucket; COUNT metrics SUM across the
 * contributing (release, ring) rows; RATE metrics AVERAGE — never
 * sum. Buckets sort oldest-first so any chart renders them as
 * received.
 */
export function foldSeriesPoints(points: readonly MetricPoint[], metric: string): FoldedPoint[] {
  const buckets = new Map<string, { sum: number; count: number }>();
  for (const point of points) {
    if (!Number.isFinite(point.value)) {
      continue;
    }
    const bucket = buckets.get(point.bucketStart) ?? { sum: 0, count: 0 };
    bucket.sum += point.value;
    bucket.count += 1;
    buckets.set(point.bucketStart, bucket);
  }
  const folded: FoldedPoint[] = [];
  for (const bucketStart of [...buckets.keys()].sort()) {
    const bucket = buckets.get(bucketStart)!;
    folded.push({
      bucketStart,
      value: isCountMetric(metric) ? bucket.sum : bucket.count === 0 ? 0 : bucket.sum / bucket.count,
    });
  }
  return folded;
}
