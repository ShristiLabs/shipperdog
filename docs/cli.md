# CLI (S5-06 issue #25; releases S9-06 issue #60)

The developer-side companion (PRD 20): `shipperdog login`, `artifact
upload/list/view/revalidate`, `version list`, and the release verbs —
`release create/list/get/transition/promote`, `release manifest` — the
surface CI pipelines drive. Implementation lives in `cli/` (Go 1.26,
ADR-0004); user-facing
usage, install, credentials handling, and the exit-code table live in
[cli/README.md](../cli/README.md). This page records the developer-side
design.

## Architecture

- `cli/internal/api` — the thin HTTP client. `endpoints.go` holds every
  path template the CLI composes (one file, the single place a URL is
  built); `endpoints_test.go` is the contract test that fails the build
  if any template is not a path in `docs/openapi/baseline.yaml` — the
  structural answer to "never hand-roll endpoint URLs" (the
  generated-client trade-off is recorded in ADR-0004).
- `cli/internal/config` — the credential/config store under
  `~/.shipperdog` (credentials 0600, config 0600, directory 0700).
  Precedence: flag, then `SHIPPERDOG_API`/`SHIPPERDOG_TOKEN`, then the
  on-disk store, then the local default `http://localhost:8080`.
- `cli/internal/commands` — the cobra tree and the single exit-decision
  point (`ExitCode`): every failure maps onto the documented exit-code
  contract (auth 3, validation 4, network 5 — CI branches on them).
- `cli/internal/output` — tables, `--json` mode, and upload progress
  (stdout stays clean data; progress and diagnostics go to stderr).

## API mapping

| CLI | API |
|---|---|
| `login` (verify) | `GET /api/v1/auth/me` |
| `artifact upload` (<= threshold) | `POST /api/v1/artifacts` multipart, claims as query parameters (`version_id`, `platform`, `build_number`, `git_commit`, `git_branch`, `pipeline`, `ci_run_url`, `build_timestamp`), an `Idempotency-Key` generated per invocation |
| `artifact upload` (> threshold) | `POST /api/v1/artifacts/upload-sessions`, direct `PUT` to the granted `uploadUrl` (no bearer header — the URL carries its own grant), `POST /api/v1/artifacts/{id}/finalize` |
| `artifact view` / settle polling | `GET /api/v1/artifacts/{id}?version_id=` until the status leaves VALIDATING/UPLOADING |
| `artifact revalidate` | `POST /api/v1/artifacts/{id}/revalidate?version_id=` (202 async; 409 `ARTIFACT_NOT_VALIDATABLE` maps to exit 4) |
| `artifact list` | `GET /api/v1/applications?organizationId=` + `GET /api/v1/applications/{id}/versions` walks; one row per artifact slot |
| `version list` | `GET /api/v1/applications/{id}/versions` (`tag`/`include` pass through) |
| `release create` | `POST /api/v1/releases` (one `Idempotency-Key` per invocation; artifacts auto-selected from the version's READY slots — newest per platform, `--artifact` overrides — targets from `--tenants`/`--groups`, ring, optional environment) |
| `release list` | `GET /api/v1/releases?applicationId=…` (`versionId` — resolved from name — `status`, `tenantId` filters; cursor-walked) |
| `release get` | `GET /api/v1/releases/{id}?applicationId=…` |
| `release transition` | `POST /api/v1/releases/{id}/transitions` (client mirrors the PRD 17 vocabulary and the FAILED/ROLLED_BACK reason rule; the server is the authority) |
| `release promote` | successive `POST …/transitions` walking PRD 17's forward path to PROMOTED; the first refusal (409 current/attempted, 422 policy seam) stops the walk with the reason list and exit 4 |
| `release manifest` | `GET /api/v1/releases/{id}/manifest?applicationId=…` (+`format=yaml` for the compliance export) |

Name resolution: application and version arguments accept a UUID
(direct read) or a name (resolved through the caller's organization
listing — visibility follows the caller's context, the CLI adds no
client-side authorization). An unqualified version name in `artifact
upload` (`--version 7.12.0`, the issue's example) resolves across every
visible application and must be unambiguous, else the CLI names the
candidates and asks for `--app`. Org-less accounts (no `org_id` on
`/me`) must use UUIDs. The two listings differ deliberately in their
default version window: `artifact list` is an inventory and defaults to
the full window (`--include archived,revoked`), while `version list`
defaults to the server's working set (DRAFT/ACTIVE) like the API's own
default.

Release references resolve the same way (`R-1028`, `r1028`, `1028`, or
a uuid): every release route is application-addressed for permissions,
so `--app` reads directly while an unqualified reference walks the
caller's visible applications for exactly one match — the quotable-id
form walks each application's listing (the release API exposes no
quotable-id filter); a uuid probes each application's detail read,
where a foreign id conceals as the standard 404.

`release promote` semantics: PRD 17 offers exactly one forward edge
from every pre-PROMOTED state, so the walk is deterministic — the
command is a sequence of ordinary transition calls, never a bypass.
Refusals render the problem+json `errors` member as a reason list
(the 409 carries `current`/`attempted`; the approval policies refuse
through the same member, keyed by contributor, since S10-01 — the
gate policies join at S10-03) and the command exits 4. `release get`
renders gates and approvals as not-yet-reported: the approval API
lives since S10-01 but the CLI's approval wiring is a later story
(S10-02/03); gates land with S10-03.

The CLI mirrors the server's upload routing rule locally
(`--multipart-limit`, default 100 MiB =
`ARTIFACT_RESUMABLE_THRESHOLD_BYTES`) so a large binary never wastes a
413 round trip, and rejects extension/platform mismatches locally with
the server's own vocabulary (`ARTIFACT_FILE_TYPE_REJECTED`).

## Conventions honored

- problem+json envelopes are parsed and their `code`/`detail` surfaced
  verbatim; the response `trace_id` is printed for log correlation.
- Every request carries a fresh `X-Correlation-Id`.
- Pagination: the CLI walks `next_cursor` pages (bounded) rather than
  assuming a single page; `items`/`next_cursor` are the platform's one
  snake_case exception, kept as-is.
- Provenance field names are the frozen S5-04 contract.
- An artifact that settles validation FAILED exits 4 with the failing
  stage's code and reason — the CI-friendly branch point.

## CI

`cli-ci` (`.github/workflows/cli-ci.yml`): lint (golangci-lint, version
pinned) + `go test ./...` + build on every PR, then a cross-compile
matrix (linux/darwin × amd64/arm64, `CGO_ENABLED=0`) publishing
version-stamped tarballs as workflow-run artifacts.

## Deferred

- Gate-result submission (`shipperdog gate result`, S10-03's ingestion
  contract — S9-06's issue anticipated the API; it lands with E10, whose
  story owns both the endpoint and the CLI wiring), manifest diff and
  environment commands, CI action packaging (S12-03), and browser-flow
  login (PATs are the M1 credential; `SHIPPERDOG_TOKEN` already covers
  the scripted, no-disk case).
