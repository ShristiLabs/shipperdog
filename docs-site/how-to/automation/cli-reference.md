---
description: The shipperdog CLI reference - every command, key flags, exit codes, and credential handling, generated from the CLI's own help.
---

# The shipperdog CLI reference

The `shipperdog` CLI is the developer-side companion: script
uploads, inspections, and the release path (create, drive,
promote) without curl and JSON.

::: tip About this page
The command tables below are **generated from the CLI's own
`--help` output** — captured from the CLI as of commit `a4b1b73`.
The full help text of every command is committed at the
bottom as the source of truth. The CLI does not self-update this
page: after a CLI change, regenerate manually with
`shipperdog <command> --help`.
:::

## Before the first command

1. **Get a token.** Mint a personal access token (PAT) with
   `POST /api/v1/auth/tokens` — it is shown exactly once.

2. **Log in.** `shipperdog login` verifies the token and stores it
   in `~/.shipperdog/credentials` (permissions `0600`). The token
   is read from `--token`, then the `SHIPPERDOG_TOKEN` environment
   variable, then an interactive hidden prompt (or one piped stdin
   line for scripts).

3. **Point at your deployment** when it is not the default:
   `--api` per command, or `SHIPPERDOG_API` for the session.

| Environment variable | Meaning |
|---|---|
| `SHIPPERDOG_TOKEN` | The PAT — preferred for CI, where nothing is written to disk |
| `SHIPPERDOG_API` | The API base URL (default `http://localhost:8080`) |

`shipperdog logout` removes the stored credentials.

## Exit codes

Pipelines branch on these — they are a stable contract:

| Code | Meaning |
|---|---|
| `0` | OK — the command succeeded |
| `1` | Server — unexpected internal error or a 5xx from the API |
| `2` | Usage — bad flags, missing arguments, unresolvable or ambiguous name references, or a locally rejected request shape |
| `3` | Auth — no stored credentials, or the API answered 401 |
| `4` | Validation — the API rejected the request (a 4xx problem+json: validation, conflict, too large, unsupported type, concealed 404), or an uploaded artifact settled validation as FAILED |
| `5` | Network — connection refused, DNS failure, or timeout reaching the API |

## Command inventory

### Authentication

| Command | Purpose | Key flags |
|---|---|---|
| `shipperdog login` | Verify a PAT and store the credentials | `--token`, `--json` |
| `shipperdog logout` | Remove the stored credentials | — |

### `artifact` — upload and inspect binaries

| Command | Purpose | Key flags |
|---|---|---|
| `artifact upload <file>` | Upload an `.ipa`/`.apk`/`.aab`; polls validation (READY exits 0, FAILED exits 4) | `--app`, `--version` (required), `--platform ios\|android` (required), `--build-number`, `--git-commit`, `--git-branch`, `--pipeline`, `--ci-run-url`, `--build-timestamp`, `--multipart-limit`, `--no-wait`, `--timeout` |
| `artifact list [<app>]` | One row per artifact slot across visible versions | `--version`, `--include archived,revoked`, `--limit`, `--json` |
| `artifact view <artifactId>` | The artifact read with extraction and validation blocks; settled FAILED exits 4 | `--app`, `--version` (required), `--wait`, `--timeout` |
| `artifact revalidate <artifactId>` | Re-run validation on a FAILED or stalled artifact | `--app`, `--version` (required), `--no-wait`, `--timeout` |

### `version` — inspect versions

| Command | Purpose | Key flags |
|---|---|---|
| `version list <app>` | An application's versions, newest first; default window is the working set | `--tag`, `--include archived,revoked`, `--limit`, `--json` |

### `release` — create, inspect, drive

| Command | Purpose | Key flags |
|---|---|---|
| `release create <app> <version>` | Assemble the distribution event (born DRAFT; prints the `R-…` id) | `--tenants` (required), `--ring` (required), `--groups`, `--artifact platform=id`, `--environment`, `--json` |
| `release get <release>` | The full record with the live gate block | `--app`, `--json` |
| `release list [<app>]` | Newest first, conjunctive filters | `--status`, `--version`, `--tenant`, `--limit`, `--json` |
| `release manifest <release>` | The frozen manifest (immutable at APPROVED); 404 before that crossing | `--app`, `--yaml`, `--json` |
| `release precheck <release>` | The same evaluation promote runs, as a read: verdict + complete blocker list | `--app`, `--json` |
| `release promote <release>` | Walk the state machine and the rings to PROMOTED; stops at the first blocker | `--app`, `--ring` (assert starting ring), `--tenant` (selective staged rollout) |
| `release promotions <release>` | The append-only hop/refusal timeline | `--app`, `--json` |
| `release transition <release> <to>` | Drive one state-machine crossing; `--reason` mandatory entering FAILED or ROLLED_BACK | `--app`, `--reason`, `--json` |

### `gate` — quality gates

| Command | Purpose | Key flags |
|---|---|---|
| `gate list <release>` | The release's gate block: states, thresholds, evidence, overrides | `--app`, `--json` |
| `gate result <release> <gate>` | Submit a pass/fail with the mandatory evidence URL (latest wins) | `--status pass\|fail` (required), `--evidence-url` (required), `--value`, `--app` |
| `gate override <release> <gate>` | The governed exception — once, audited, with a mandatory reason | `--reason` (required), `--app` |

### `ota` — author bundles and drive staged rollouts

The console path for the same rollouts — advance, pause, and the OTA
dashboard — is [Run OTA rollouts](/how-to/console/ota-rollouts.md).

| Command | Purpose | Key flags |
|---|---|---|
| `ota create <app> <bundleVersion>` | The bundle draft: a dedicated versions row plus the direct-to-storage upload grant (nothing is listable until finalize signs) | `--platform-tag` (required), `--file-name` (required), `--forced`, `--min-app-version`, `--size-hint`, `--json` |
| `ota upload <file>` | Stream the archive to the granted URL, then finalize: hash-back, manifest, and the Ed25519 signature (SIGNED) | `--bundle` (required), `--version` (required), `--url` (required), `--json` |
| `ota promote <release>` | Attach the cumulative basis-point ladder to a live OTA release and commence it (born RUNNING; one plan per release, ever) | `--stages` (required; strictly ascending, final 10000; notes as `bp=note`), `--mode AUTOMATIC\|CHECKPOINT` (required), `--app`, `--json` |
| `ota halt <release>` | The manual, audit-trailed stop — the device check answers no-update while halted | `--reason` (required), `--app`, `--json` |
| `ota resume <release>` | HALTED or PAUSED back to RUNNING — serving exactly what the ladder had reached | `--app`, `--reason` (optional note), `--json` |
| `ota status <release>` | The plan's mode and standing, every ladder stage with its reach moment, and the append-only operator timeline | `--app`, `--json` |
| `ota funnel <bundle>` | The bundle's device funnel: server-counted checks beside seen/download/applied/failed/rolled back | `--app` (required), `--ring`, `--json` |
| `ota token issue <app>` | Mint the application's `sdc1.` device token — the value prints exactly once (plain output is the token alone) | `--name` (required), `--json` |

### Shell completion

`shipperdog completion bash|zsh|fish|powershell` generates the
autocompletion script — see its sub-command help.

## Common shapes

```bash
# The upload-and-release path
shipperdog artifact upload app-release.apk \
  --app banking --version 7.12.0 --platform android
shipperdog release create banking 7.12.0 \
  --tenants "$TENANT_ID" --ring engineering
shipperdog release promote R-1028

# The selective staged rollout: Acme first
shipperdog release promote R-1028 --tenant "$ACME_ID"

# Why is it blocked? (same evaluation promote runs)
shipperdog release precheck R-1028

# Report a gate from CI
shipperdog gate result R-1028 coverage-threshold \
  --status pass --value 87.5 --evidence-url "$CI_RUN_URL/coverage"
```

Name references (`--app`, `--version`, `<release>`) accept names or
UUIDs; ambiguous names list their candidates and ask for `--app`.
Every command takes `--json` for pipeline-readable output.

**Next:** [GitHub Actions](/how-to/automation/github-actions.md) wraps
these verbs in composite actions · the [Jenkins](/how-to/automation/jenkins.md)
and [GitLab](/how-to/automation/gitlab.md) templates do the same ·
[CLI concepts](/get-started/concepts.md) explains the model.

## Full help output (source of truth)

Captured from the CLI's own `--help` at commit `a4b1b73`.

#### `shipperdog` (root)

```text
The ShipperDog developer-side companion (PRD 20): script uploads, inspections,
and the release path (create, drive, promote) without curl and JSON.
Credentials live in ~/.shipperdog/credentials (0600) after `shipperdog login`;
SHIPPERDOG_API overrides the endpoint.

Usage:
  shipperdog [command]

Available Commands:
  artifact    Upload and inspect artifact binaries
  completion  Generate the autocompletion script for the specified shell
  gate        Report and inspect release quality gates
  help        Help about any command
  login       Store a personal access token after verifying it
  logout      Remove the stored credentials
  ota         Author OTA bundles and drive their staged rollouts
  release     Create, inspect, and drive releases
  version     Inspect versions

Flags:
      --api string   API base URL (env SHIPPERDOG_API, default http://localhost:8080)
  -h, --help         help for shipperdog
  -v, --version      version for shipperdog

Use "shipperdog [command] --help" for more information about a command.
```

#### `shipperdog artifact`

```text
Upload and inspect artifact binaries

Usage:
  shipperdog artifact [command]

Available Commands:
  list        List artifacts (tables or JSON), scoped to the caller's context
  revalidate  Re-run validation on a FAILED or stalled artifact (202 async; polls to settle)
  upload      Upload an artifact binary (multipart, or resumable session above the threshold)
  view        Read an artifact with its extraction and validation blocks

Flags:
  -h, --help   help for artifact

Global Flags:
      --api string   API base URL (env SHIPPERDOG_API, default http://localhost:8080)

Use "shipperdog artifact [command] --help" for more information about a command.
```

#### `shipperdog gate`

```text
The quality-gate surface (FR-026, PRD 32): submit a gate result from CI with
evidence (latest wins, history kept), read a release's gate block (passed,
failed, no-data fail-closed, or not-reported per gate, with thresholds and
evidence links), or override a blocking gate with a mandatory audited
reason. Gate types are an open vocabulary: the FR-026 built-ins above or
your integration's own kind. A failed or data-less required gate blocks the
release's READY crossing until passed or overridden.

Usage:
  shipperdog gate [command]

Available Commands:
  list        Read a release's gate block (states, thresholds, evidence)
  override    Override a blocking gate with a mandatory audited reason
  result      Submit a gate result from CI (pass/fail + evidence URL)

Flags:
  -h, --help   help for gate

Global Flags:
      --api string   API base URL (env SHIPPERDOG_API, default http://localhost:8080)

Use "shipperdog gate [command] --help" for more information about a command.
```

#### `shipperdog login`

```text
Verifies a personal access token (PAT) against GET /api/v1/auth/me and
stores it in ~/.shipperdog/credentials with 0600 permissions. The token is
read from --token, then SHIPPERDOG_TOKEN, then an interactive hidden
prompt (or one piped stdin line for scripts). PATs are minted via
POST /api/v1/auth/tokens and shown exactly once.

Usage:
  shipperdog login [flags]

Flags:
  -h, --help           help for login
      --json           machine-readable output
      --token string   personal access token (prefer the prompt or SHIPPERDOG_TOKEN)

Global Flags:
      --api string   API base URL (env SHIPPERDOG_API, default http://localhost:8080)
```

#### `shipperdog logout`

```text
Remove the stored credentials

Usage:
  shipperdog logout [flags]

Flags:
  -h, --help   help for logout

Global Flags:
      --api string   API base URL (env SHIPPERDOG_API, default http://localhost:8080)
```

#### `shipperdog ota`

```text
The OTA surface (ADR-0010): create a bundle draft and stream its bytes to
the granted storage URL, finalize into the signed manifest, attach a
cumulative basis-point rollout ladder to a live OTA release (promote),
halt/resume the rollout as audited operator actions, read the plan's
standing and timeline (status), the device funnel (funnel), and mint
the application's sdc1. device token (token issue - the value prints
exactly once). JSON output everywhere for pipelines.

Usage:
  shipperdog ota [command]

Available Commands:
  create      Create an OTA bundle draft with its upload grant (UPLOADING)
  funnel      Read the bundle's device funnel (checks, seen, downloads, applied)
  halt        Halt the rollout (the manual, audit-trailed stop; reason mandatory)
  promote     Attach and commence the release's staged rollout plan (born RUNNING)
  resume      Resume a HALTED or PAUSED rollout (serving what it had reached)
  status      Read the rollout plan: standing, ladder, and append-only timeline
  token       Mint and manage the application's sdc1. device tokens
  upload      Stream the bundle's bytes to the granted URL, then finalize and sign

Flags:
  -h, --help   help for ota

Global Flags:
      --api string   API base URL (env SHIPPERDOG_API, default http://localhost:8080)

Use "shipperdog ota [command] --help" for more information about a command.
```

#### `shipperdog release`

```text
The release surface (PRD 17/20): create a release from a version's READY
artifacts targeting tenants/groups on a ring, read it back by quotable id
(R-1028), drive the state machine edge by edge with transition, or walk it
to PROMOTED with promote - precheck answers "why can't I promote" in one
screen and promotions reads the append-only hop timeline. JSON output
everywhere for pipelines.

Usage:
  shipperdog release [command]

Available Commands:
  create      Create a release from a version's READY artifacts (DRAFT)
  get         Read a release (status, artifacts, targets, ring, gates)
  list        List releases (tables or JSON), newest first per application
  manifest    Read the frozen release manifest (JSON, or YAML with --yaml)
  precheck    Answer "why can't I promote" in one screen (the same evaluation promote runs)
  promote     Walk the state machine and the rings to PROMOTED, stopping at the first blocker
  promotions  Read the append-only promotion timeline (every hop and every refusal)
  transition  Drive one PRD 17 state-machine crossing

Flags:
  -h, --help   help for release

Global Flags:
      --api string   API base URL (env SHIPPERDOG_API, default http://localhost:8080)

Use "shipperdog release [command] --help" for more information about a command.
```

#### `shipperdog version`

```text
Inspect versions

Usage:
  shipperdog version [command]

Available Commands:
  list        List an application's versions (tables or JSON)

Flags:
  -h, --help   help for version

Global Flags:
      --api string   API base URL (env SHIPPERDOG_API, default http://localhost:8080)

Use "shipperdog version [command] --help" for more information about a command.
```

#### `shipperdog artifact list`

```text
Walks the visible applications and their versions and renders one row per
artifact slot (the listing the API serves carries artifact status, format
verdict, and validation summary). An optional <app> argument (name or uuid)
narrows the walk; --version narrows to one version. Server-side visibility
decides what the caller sees - the CLI adds no client-side filtering.

Usage:
  shipperdog artifact list [<app>] [flags]

Flags:
  -h, --help             help for list
      --include string   version window to include (default all: archived,revoked)
      --json             machine-readable output
      --limit int        cap rows (0 = uncapped within pagination bounds)
      --version string   narrow to one version (name or uuid)

Global Flags:
      --api string   API base URL (env SHIPPERDOG_API, default http://localhost:8080)
```

#### `shipperdog artifact revalidate`

```text
POST /api/v1/artifacts/{id}/revalidate: a FAILED artifact re-enters through
FAILED -> VALIDATING, a blocked validation re-drives, a blocked extraction
re-arms. 409 ARTIFACT_NOT_VALIDATABLE answers for anything the state machine
cannot move back (READY/DISTRIBUTED/ARCHIVED). With --wait (default), polls
the read until settled.

Usage:
  shipperdog artifact revalidate <artifactId> [flags]

Flags:
      --app string         owning application (name or uuid; needed when --version is a name)
  -h, --help               help for revalidate
      --json               machine-readable output
      --no-wait            skip the settle poll; return right after the 202
      --timeout duration   max wait with --wait (default 2m0s)
      --version string     owning version (name or uuid) (required)
      --wait               poll until validation settles (default true)

Global Flags:
      --api string   API base URL (env SHIPPERDOG_API, default http://localhost:8080)
```

#### `shipperdog artifact upload`

```text
Uploads a .ipa/.apk/.aab binary to a version. Provenance flags map to the frozen
S5-04 fields (gitCommit, gitBranch, pipeline, ciRunUrl, buildTimestamp); all are
optional and never fabricated. Files larger than the resumable threshold (default
100 MiB, mirroring the server rule) stream direct to storage via an upload session.
By default the command then polls the artifact read until validation settles
(READY exits 0; FAILED exits 4 with the failing stage).

Usage:
  shipperdog artifact upload <file> [flags]

Flags:
      --app string               target application (name or uuid)
      --build-number int         CI-pinned build number
      --build-timestamp string   build timestamp (ISO-8601 instant)
      --ci-run-url string        CI run URL (absolute http/https)
      --git-branch string        git branch (1-255 chars)
      --git-commit string        git commit (hex SHA-1/SHA-256)
  -h, --help                     help for upload
      --json                     machine-readable output (the artifact view JSON)
      --multipart-limit int      bytes above which the resumable session path is used (default 104857600)
      --no-wait                  skip the settle poll; return right after registration
      --pipeline string          pipeline name (1-255 chars)
      --platform string          ios or android (required)
      --timeout duration         max wait for validation to settle (default 2m0s)
      --version string           target version (name like 7.12.0, or uuid; names need --app unless unambiguous) (required)
      --wait                     poll until validation settles (READY/FAILED) (default true)

Global Flags:
      --api string   API base URL (env SHIPPERDOG_API, default http://localhost:8080)
```

#### `shipperdog artifact view`

```text
GET /api/v1/artifacts/{id}: the artifact read where the extraction outcome and
the validation block (per-stage status/code/reason in FR-006 order, attempts,
duration, format verdict) settle. With --wait, polls until settled; a settled
FAILED artifact exits 4 so scripts can branch.

Usage:
  shipperdog artifact view <artifactId> [flags]

Flags:
      --app string         owning application (name or uuid; needed when --version is a name)
  -h, --help               help for view
      --json               machine-readable output
      --timeout duration   max wait with --wait (default 2m0s)
      --version string     owning version (name or uuid) (required)
      --wait               poll until validation settles

Global Flags:
      --api string   API base URL (env SHIPPERDOG_API, default http://localhost:8080)
```

#### `shipperdog gate list`

```text
GET /api/v1/releases/{id}/gates (PRD 32): the release's quality-gate
block - every gate the release's ring expects (required first-class,
advisory marked) beside any reported results: PASSED with evidence,
FAILED (blocking), NO_DATA (metric-backed, fail-closed: analytics has
no data yet - distinct from a failure), or not reported yet. Numeric
gates render the observed value beside the definition's threshold
(coverage 87.5, threshold 80); overridden gates render their record.

Usage:
  shipperdog gate list <release> [flags]

Flags:
      --app string   owning application (name or uuid; skips the lookup walk)
  -h, --help         help for list
      --json         machine-readable output (the gates view JSON)

Global Flags:
      --api string   API base URL (env SHIPPERDOG_API, default http://localhost:8080)
```

#### `shipperdog gate override`

```text
POST /api/v1/releases/{id}/gates/{gate}/override (FR-026): the governed
exception - a FAILED or NO_DATA gate stops blocking, exactly once, with
actor, mandatory reason, and moment recorded on the release and
surfaced on the dashboard. The gate keeps its recorded state; only the
override governs whether it still blocks. Requires the
app:releases:gate-override authority (release managers and org admins).

Usage:
  shipperdog gate override <release> <gate> [flags]

Flags:
      --app string      owning application (name or uuid; skips the lookup walk)
  -h, --help            help for override
      --json            machine-readable output (the gate view JSON)
      --reason string   the mandatory, audited override reason (required)

Global Flags:
      --api string   API base URL (env SHIPPERDOG_API, default http://localhost:8080)
```

#### `shipperdog gate result`

```text
POST /api/v1/releases/{id}/gates/{gate}/result (FR-022/FR-026): records a
pass or fail with the mandatory evidence URL (the report link a human can
follow) and the submitting caller's identity. Idempotent per gate+release
as latest-wins: re-running a gate updates the current state and appends
the history. Threshold/ceiling gates (coverage-threshold, and any custom
numeric kind) also require --value, the observed number rendered beside
the definition's threshold. Metric-backed gates (crash-free-threshold,
open-critical-bugs) refuse submission: their values resolve from
analytics at evaluation time, fail-closed until the feeds land (S11-04).

Usage:
  shipperdog gate result <release> <gate> [flags]

Flags:
      --app string            owning application (name or uuid; skips the lookup walk)
      --evidence-url string   evidence/report URL, http(s) (required)
  -h, --help                  help for result
      --json                  machine-readable output (the gate view JSON)
      --status string         reported outcome: pass or fail (required)
      --value float           observed value of a threshold/ceiling gate (e.g. coverage 87.5; a 0 is legitimate)

Global Flags:
      --api string   API base URL (env SHIPPERDOG_API, default http://localhost:8080)
```

#### `shipperdog ota create`

```text
POST /api/v1/ota/bundles (ADR-0010 D2/D5/D7): creates the bundle's
dedicated versions row (label = the semver-triple bundleVersion) and
grants the direct-to-storage session for the bundle's bytes. The answer
carries the bundle id (the pre-allocated artifact id - the bytes ARE the
bundle), the version id, and the upload URL with its expiry: copy the
uploadUrl and the ids into `ota upload` next. --forced and
--min-app-version are the per-bundle defaults devices compare against
(overridable per release target at release time). Nothing is listable
until finalize lands the SIGNED row.

Usage:
  shipperdog ota create <app> <bundleVersion> [flags]

Flags:
      --file-name string         bare file name of the bundle archive (required)
      --forced                   force-install default for devices (D7 per-bundle default)
  -h, --help                     help for create
      --json                     machine-readable output (the draft view JSON)
      --min-app-version string   minimum app version that may apply this bundle
      --platform-tag string      open-vocabulary platform tag, 1-32 chars (e.g. rn, flutter, electron) (required)
      --size-hint int            expected bundle size in bytes (optional)

Global Flags:
      --api string   API base URL (env SHIPPERDOG_API, default http://localhost:8080)
```

#### `shipperdog ota funnel`

```text
GET /api/v1/tenants/{tenantId}/dashboard/ota/bundles/{bundleId}/funnel
(ADR-0010 D9): the dashboard funnel of one bundle - the server-counted
checks beside the device-reported lifecycle (seen, download, applied,
failed, rolled back), the failure-reason breakdown, and the adoption
curve over time. --app names the owning application (the dashboard
route is tenant-addressed; the application carries the tenant and the
permission target). <bundle> is the bundle uuid or its bundleVersion
label. --ring narrows to one ring's devices.

Usage:
  shipperdog ota funnel <bundle> [flags]

Flags:
      --app string    owning application (name or uuid) (required)
  -h, --help          help for funnel
      --json          machine-readable output (the funnel view JSON)
      --ring string   narrow the funnel to one ring's devices

Global Flags:
      --api string   API base URL (env SHIPPERDOG_API, default http://localhost:8080)
```

#### `shipperdog ota halt`

```text
POST /api/v1/ota/rollouts/{releaseId}/halt (ADR-0010 D10): the manual,
audit-trailed stop - the reason is mandatory, the plan stands HALTED,
and the device check answers no-update while it does. A paused plan is
still stoppable; nothing halts on its own, ever. Resume with
`ota resume`. Requires app:releases:manage.

Usage:
  shipperdog ota halt <release> [flags]

Flags:
      --app string      owning application (name or uuid; skips the lookup walk)
  -h, --help            help for halt
      --json            machine-readable output (the rollout view JSON)
      --reason string   why the rollout stops (mandatory; lands on the audit trail) (required)

Global Flags:
      --api string   API base URL (env SHIPPERDOG_API, default http://localhost:8080)
```

#### `shipperdog ota promote`

```text
POST /api/v1/ota/rollouts (ADR-0010 D6): attaches the cumulative
basis-point ladder to a live (ACTIVE or PROMOTED) OTA release and
COMMENCES it - stage 1 reached, the plan RUNNING, a COMMENCED timeline
row. One plan per release, ever. --stages is the strictly ascending
comma-separated ladder whose final stage is 10000 (the full fleet);
each entry may carry an operator note as bp=note. --mode picks the
advancement discipline: AUTOMATIC consults the policy engine between
stages, CHECKPOINT makes every advance an operator's explicit
approval (advance/pause live in the console; halt/resume ride this
CLI). Devices resolve membership deterministically on the fly - the
bucket never retreats. Requires app:releases:manage.

Usage:
  shipperdog ota promote <release> [flags]

Flags:
      --app string      owning application (name or uuid; skips the lookup walk)
  -h, --help            help for promote
      --json            machine-readable output (the rollout view JSON)
      --mode string     advancement discipline: AUTOMATIC or CHECKPOINT (required)
      --stages string   cumulative basis-point ladder, strictly ascending, final 10000 (e.g. 1000,5000,10000; notes as bp=note) (required)

Global Flags:
      --api string   API base URL (env SHIPPERDOG_API, default http://localhost:8080)
```

#### `shipperdog ota resume`

```text
POST /api/v1/ota/rollouts/{releaseId}/resume: from HALTED or PAUSED
back to RUNNING - serving exactly what the plan had reached, because
the ladder never retreats and resuming widens nothing by itself.
Requires app:releases:manage.

Usage:
  shipperdog ota resume <release> [flags]

Flags:
      --app string      owning application (name or uuid; skips the lookup walk)
  -h, --help            help for resume
      --json            machine-readable output (the rollout view JSON)
      --reason string   resume note (optional; lands on the audit trail)

Global Flags:
      --api string   API base URL (env SHIPPERDOG_API, default http://localhost:8080)
```

#### `shipperdog ota status`

```text
GET /api/v1/ota/rollouts/{releaseId}: the plan's mode and standing
(RUNNING, HALTED, PAUSED, COMPLETE), every stage of the ladder with its
cumulative basis-point threshold and reach moment, and the append-only
operator timeline (commenced/halted/resumed/advanced). Requires
app:releases:read.

Usage:
  shipperdog ota status <release> [flags]

Flags:
      --app string   owning application (name or uuid; skips the lookup walk)
  -h, --help         help for status
      --json         machine-readable output (the rollout view JSON)

Global Flags:
      --api string   API base URL (env SHIPPERDOG_API, default http://localhost:8080)
```

#### `shipperdog ota token`

```text
The sdc1. app-token surface (ADR-0010 D3): the identifier the shipped
binary presents at /api/v1/ota/check. The value prints EXACTLY ONCE -
the registry stores only its SHA-256 - so capture it in the issue
moment.

Usage:
  shipperdog ota token [command]

Available Commands:
  issue       Issue a sdc1. app token (the value prints exactly once)

Flags:
  -h, --help   help for token

Global Flags:
      --api string   API base URL (env SHIPPERDOG_API, default http://localhost:8080)

Use "shipperdog ota token [command] --help" for more information about a command.
```

#### `shipperdog ota token issue`

```text
POST /api/v1/ota/app-tokens (ADR-0010 D3): mints the application's
sdc1. device identifier - the value the shipped binary embeds for
/api/v1/ota/check. The plain output is the token VALUE ALONE on stdout
(capture it: TOKEN=$(shipperdog ota token issue App --name prod); the
registry stores only its SHA-256 and the value never leaves the server
again). --name is the human label the registry distinguishes tokens by
(1-200 chars). Revocation lives in the console.

Usage:
  shipperdog ota token issue <app> [flags]

Flags:
  -h, --help          help for issue
      --json          machine-readable output (the issued view JSON - carries the value once)
      --name string   human label for the registry (1-200 chars) (required)

Global Flags:
      --api string   API base URL (env SHIPPERDOG_API, default http://localhost:8080)
```

#### `shipperdog ota upload`

```text
The second half of authoring (ADR-0010 D5/D8): streams the archive
direct to the storage URL `ota create` granted (as-is, without the API
bearer header - the grant embeds its own authority), then POST
/api/v1/ota/bundles/{id}/finalize: the artifact port hashes the stored
bytes back, the manifest lands (sha256, sizeBytes, platformTag), and
the checksum is signed under the app's active Ed25519 key (generated
now if this is the app's first bundle). The answer is the SIGNED
bundle; a replay answers created=false with the same row.

Usage:
  shipperdog ota upload <file> [flags]

Flags:
      --bundle ota create    bundle uuid from ota create (required)
  -h, --help                 help for upload
      --json                 machine-readable output (the finalize view JSON)
      --url ota create       the uploadUrl of ota create's grant (required)
      --version ota create   the draft's dedicated version uuid from ota create (required)

Global Flags:
      --api string   API base URL (env SHIPPERDOG_API, default http://localhost:8080)
```

#### `shipperdog release create`

```text
POST /api/v1/releases (FR-015): assembles the distribution event in one
governed act - the version, one READY artifact per platform, the target
tenants with optional tester-group narrowing, and the starting ring
(FR-017: engineering, qa, tenant-pilot, uat, production until the org
customizes). Artifacts are selected automatically (newest READY per
platform); --artifact platform=id overrides one platform. The release is
born DRAFT; drive it with transition/promote. The quotable id (R-…) is
printed on every later view. Validation errors mirror the API's own
codes - including the per-tenant 422 RELEASE_TARGETS_INVALID reason list.

Usage:
  shipperdog release create <app> <version> [flags]

Flags:
      --artifact strings     explicit artifact per platform (platform=artifactId, repeatable); default: newest READY per platform
      --environment string   target environment uuid (optional, S9-04; every target tenant must sit inside its assignments)
      --groups strings       tester-group uuids: tenantId=group[,group...] to scope, or bare group[,group...] applying to every tenant (optional; omit to target tenants at large)
  -h, --help                 help for create
      --json                 machine-readable output (the release detail JSON)
      --ring string          starting ring code (FR-017 system rings: engineering, qa, tenant-pilot, uat, production) (required)
      --tenants strings      target tenant uuids (comma-separated, repeatable; distinct) (required)

Global Flags:
      --api string   API base URL (env SHIPPERDOG_API, default http://localhost:8080)
```

#### `shipperdog release get`

```text
GET /api/v1/releases/{id}: the full creation record - status, per-platform
artifact selection, the complete target set, ring and environment - with
the live quality-gate block (S10-03, issue #42) appended: per-gate
states (passed, failed, no-data fail-closed, not-reported), thresholds,
evidence links, and override records. Approvals render in the console
(the CLI approval surface stays with its own stories); a blocked READY
crossing's refusal names the blocking gates either way.
<release> is the quotable id (R-1028, r1028, 1028) or a
uuid; --app (name or uuid) skips the cross-application lookup. --json emits
the release detail verbatim for pipeline decisions.

Usage:
  shipperdog release get <release> [flags]

Flags:
      --app string   owning application (name or uuid; skips the lookup walk)
  -h, --help         help for get
      --json         machine-readable output (the release detail JSON)

Global Flags:
      --api string   API base URL (env SHIPPERDOG_API, default http://localhost:8080)
```

#### `shipperdog release list`

```text
GET /api/v1/releases?applicationId=…: newest (quotable number) first with
the server's conjunctive filters - --version (name or uuid, resolved to the
server's versionId filter), --status, --tenant. Without an <app> argument
the walk covers every application visible to the caller.

Usage:
  shipperdog release list [<app>] [flags]

Flags:
  -h, --help             help for list
      --json             machine-readable output
      --limit int        cap rows (0 = uncapped within pagination bounds)
      --status string    narrow to one PRD 17 status
      --tenant string    narrow to releases targeting this tenant uuid
      --version string   narrow to one version (name or uuid)

Global Flags:
      --api string   API base URL (env SHIPPERDOG_API, default http://localhost:8080)
```

#### `shipperdog release manifest`

```text
GET /api/v1/releases/{id}/manifest (PRD 16): the immutable record of what
the release ships, frozen at the APPROVED crossing and never recomputed -
artifact pins by content (sha256, build, size, filename), targets, ring,
environment configuration snapshot, and the canonical sha256 digest.
--yaml emits the compliance export (application/yaml) verbatim. A release
that has not crossed into APPROVED answers the honest 404
RELEASE_MANIFEST_NOT_FOUND.

Usage:
  shipperdog release manifest <release> [flags]

Flags:
      --app string   owning application (name or uuid; skips the lookup walk)
  -h, --help         help for manifest
      --json         machine-readable output (the manifest view JSON)
      --yaml         emit the manifest as YAML (the compliance export)

Global Flags:
      --api string   API base URL (env SHIPPERDOG_API, default http://localhost:8080)
```

#### `shipperdog release precheck`

```text
GET /api/v1/releases/{id}/promotion-precheck (S10-05, issue #44's technical
note: one evaluation function, three surfaces): the SAME evaluation the
promote act runs, answered as a read - the verdict, the complete blocker
list (byte-for-byte the 409's errors member, every blocker named with its
evidence), the target ring's gate states, the approval leg's standing, and
the widened eligibility rows. Nothing is written and no approval round is
opened; the first PROMOTE attempt opens the round a policy demands.
Requires app:releases:read.

Usage:
  shipperdog release precheck <release> [flags]

Flags:
      --app string   owning application (name or uuid; skips the lookup walk)
  -h, --help         help for precheck
      --json         machine-readable output (the precheck JSON)

Global Flags:
      --api string   API base URL (env SHIPPERDOG_API, default http://localhost:8080)
```

#### `shipperdog release promote`

```text
The CI showcase (PRD 20/32): drives the release forward through PRD 17's
edges - VALIDATING, READY, APPROVAL_PENDING, APPROVED (the manifest freeze),
DEPLOYING, ACTIVE - and then through the promotion engine's ring hops (S10-05:
one ring at a time in the organization's effective order, the final ring
carrying the release across ACTIVE to PROMOTED). Any refusal stops the walk:
the blocker's code, detail, and full reason list print - a refused edge names
the current and attempted states, a refused hop answers the COMPLETE blocker
list (every gate, approval, eligibility, and policy refusal named with its
evidence) - and the command exits non-zero. --ring asserts the release's
STARTING ring (the creation ring never moves; mismatch refuses - the wrong-
release guard). --tenant narrows the ring hops to the named targets (the
selective staged rollout: Acme first, Contoso later); absent moves every
target. A release already PROMOTED or COMPLETED is a success no-op; FAILED
and ROLLED_BACK are terminal and refuse.

Usage:
  shipperdog release promote <release> [flags]

Flags:
      --app string       owning application (name or uuid; skips the lookup walk)
  -h, --help             help for promote
      --json             machine-readable output (the final detail JSON)
      --ring string      assert the release's starting ring code (the PRD 20 example's flag; mismatch refuses)
      --tenant strings   promote only these target tenants, repeatable (the selective staged rollout; absent moves every target)

Global Flags:
      --api string   API base URL (env SHIPPERDOG_API, default http://localhost:8080)
```

#### `shipperdog release promotions`

```text
GET /api/v1/releases/{id}/promotions (S10-05, PRD 18: promotion preserves
release history): the append-only timeline - every COMPLETED hop with its
two rings, the tenants moved, and the actor, and every BLOCKED attempt
with its recorded evidence (the complete blocker list the 409 answered).
The record the release dashboard renders beside the state timeline.
Requires app:releases:read.

Usage:
  shipperdog release promotions <release> [flags]

Flags:
      --app string   owning application (name or uuid; skips the lookup walk)
  -h, --help         help for promotions
      --json         machine-readable output (the timeline JSON)

Global Flags:
      --api string   API base URL (env SHIPPERDOG_API, default http://localhost:8080)
```

#### `shipperdog release transition`

```text
POST /api/v1/releases/{id}/transitions (PRD 17): the machine's edges only -
DRAFT->VALIDATING->READY->APPROVAL_PENDING->APPROVED->DEPLOYING->ACTIVE,
ACTIVE->PROMOTED->COMPLETED, the failure edges to FAILED, and
ACTIVE->ROLLED_BACK. --reason is mandatory entering FAILED or ROLLED_BACK
(the release explains itself). Illegal crossings answer 409 with the
current and attempted states; policy refusals (E10) answer 422 with the
contributor's reason - both render their full reason list.

Usage:
  shipperdog release transition <release> <to> [flags]

Flags:
      --app string      owning application (name or uuid; skips the lookup walk)
  -h, --help            help for transition
      --json            machine-readable output (the refreshed detail)
      --reason string   crossing reason (mandatory entering FAILED or ROLLED_BACK)

Global Flags:
      --api string   API base URL (env SHIPPERDOG_API, default http://localhost:8080)
```

#### `shipperdog version list`

```text
GET /api/v1/applications/{id}/versions: newest-first by the normalized semver
sort key. The default window is the working set (DRAFT and ACTIVE); --include
archived,revoked widens it; --tag narrows to versions carrying a tag.

Usage:
  shipperdog version list <app> [flags]

Flags:
  -h, --help             help for list
      --include string   comma-separated archived and/or revoked to widen the window
      --json             machine-readable output
      --limit int        cap rows (0 = uncapped within pagination bounds)
      --tag string       narrow to versions carrying this tag

Global Flags:
      --api string   API base URL (env SHIPPERDOG_API, default http://localhost:8080)
```

