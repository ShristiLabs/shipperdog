# CLI (S5-06 issue #25; releases S9-06 issue #60; promotion S10-05 issue #44)

The developer-side companion (PRD 20): `shipperdog login`, `artifact
upload/list/view/revalidate`, `version list`, and the release verbs —
`release create/list/get/transition/promote`, `release
precheck/promotions`, `release manifest` — the
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
| `release promote` | state edges through `POST …/transitions` to ACTIVE, then ring hops through `POST /api/v1/releases/{id}/promote` (S10-05) until the final ring carries the release to PROMOTED; `--ring` asserts the release's starting ring (mismatch refuses — the wrong-release guard), `--tenant` (repeatable) narrows the hops to the selective staged rollout; the first refusal stops the walk with the reason list and exit 4 |
| `release precheck` | `GET /api/v1/releases/{id}/promotion-precheck?applicationId=…` (S10-05: "why can't I promote" in one screen — the same evaluation promote runs, nothing written) |
| `release promotions` | `GET /api/v1/releases/{id}/promotions?applicationId=…` (S10-05: the append-only timeline — every COMPLETED hop and every BLOCKED attempt with its evidence) |
| `release manifest` | `GET /api/v1/releases/{id}/manifest?applicationId=…` (+`format=yaml` for the compliance export) |
| `gate result` | `POST /api/v1/releases/{id}/gates/{gate}/result` (S10-03's CI ingestion — the command S9-06 deferred; `--status pass\|fail`, mandatory `--evidence-url`, `--value` for threshold/ceiling gates; latest wins with history) |
| `gate list` | `GET /api/v1/releases/{id}/gates?applicationId=…` (the §32 block: states, thresholds, evidence, override records) |
| `gate override` | `POST /api/v1/releases/{id}/gates/{gate}/override` (mandatory `--reason`; requires the `app:releases:gate-override` authority) |

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

`release promote` semantics: the walk is deterministic and never a
bypass — PRD 17 offers exactly one forward edge from every
pre-PROMOTED state, and since S10-05 the walk continues through the
rings: at ACTIVE, each `POST …/promote` performs ONE governed hop (the
target ring's gates, approvals, eligibility, and policies evaluated
live; the final ring's hop carries the release across ACTIVE to
PROMOTED with the state machine's own ceremony). Refusals render the
problem+json `errors` member as a reason list (a refused state edge
carries `current`/`attempted`; the approval policies refuse through
the same member, keyed by contributor, since S10-01, and the gate
policies since S10-03; a refused HOP answers the COMPLETE blocker
list — every gate, approval, eligibility, and policy refusal named
with its evidence) and the command exits 4. `--ring` asserts the
release's STARTING ring, which never moves — a mismatch refuses (the
wrong-release guard), and `--tenant` (repeatable) narrows the ring
hops to the named targets, the selective staged rollout (Acme first,
Contoso later; absent moves every target). A release already PROMOTED
or COMPLETED is a success no-op; FAILED and ROLLED_BACK are terminal
and refuse. `release get`
renders the live gate block since S10-03 (per-gate states, thresholds,
evidence, override records; `gate list` is the dedicated read);
approvals are live since S10-02 — the refusal names pending approvals
(the group-shaped waiting-on list), and the console renders the rounds
and takes decisions (the CLI approval command surface remains a later
story). `release precheck` answers the same evaluation the hop runs as
a read (the approval leg reports NOT_OPEN before the first promote
attempt opens the round); `release promotions` renders the timeline.

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

- Manifest diff and environment commands, CI action packaging
  (S12-03), and browser-flow login (PATs are the M1 credential;
  `SHIPPERDOG_TOKEN` already covers the scripted, no-disk case). The
  gate-result submission S9-06 deferred (`shipperdog gate result`,
  with `gate list` and `gate override`) LANDED with S10-03.
