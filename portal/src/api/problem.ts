/**
 * RFC 7807 problem+json decoding (docs/api-conventions.md), shared by
 * the raw auth calls and the general client so every failure surface
 * in the portal carries the same stable `code`, registry `detail`,
 * `traceId` (wire key `trace_id`), and 400 `errors` field map.
 */
export class ApiError extends Error {
  readonly status: number;
  readonly code?: string;
  readonly title?: string;
  readonly detail?: string;
  readonly traceId?: string;
  readonly fieldErrors?: Record<string, string>;

  constructor(problem: {
    status: number;
    code?: string;
    title?: string;
    detail?: string;
    traceId?: string;
    fieldErrors?: Record<string, string>;
  }) {
    super(
      problem.detail
        ? `${problem.title ?? "Request failed"}: ${problem.detail}`
        : problem.title ?? `Request failed (${problem.status})`,
    );
    this.name = "ApiError";
    this.status = problem.status;
    this.code = problem.code;
    this.title = problem.title;
    this.detail = problem.detail;
    this.traceId = problem.traceId;
    this.fieldErrors = problem.fieldErrors;
  }
}

interface ProblemBody {
  title?: unknown;
  status?: unknown;
  code?: unknown;
  detail?: unknown;
  trace_id?: unknown;
  errors?: unknown;
}

export async function parseProblem(response: Response): Promise<ApiError> {
  const fallbackTitle = response.statusText || "Request failed";
  try {
    const body = (await response.json()) as ProblemBody;
    return new ApiError({
      status: response.status,
      code: asString(body.code),
      title: asString(body.title) ?? fallbackTitle,
      detail: asString(body.detail),
      traceId: asString(body.trace_id),
      fieldErrors: asFieldErrors(body.errors),
    });
  } catch {
    return new ApiError({ status: response.status, title: fallbackTitle });
  }
}

function asString(value: unknown): string | undefined {
  return typeof value === "string" ? value : undefined;
}

function asFieldErrors(value: unknown): Record<string, string> | undefined {
  if (typeof value !== "object" || value === null) return undefined;
  const out: Record<string, string> = {};
  for (const [key, message] of Object.entries(value)) {
    if (typeof message === "string") out[key] = message;
  }
  return Object.keys(out).length > 0 ? out : undefined;
}
