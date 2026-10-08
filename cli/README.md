# ShipperDog CLI

The developer-side companion (PRD 20, stories S5-06 + S9-06): upload and
inspect artifacts and drive the release path from the terminal so CI
pipelines and humans can run ShipperDog without curl and JSON.

Implemented in Go 1.26 (ADR-0004): a single static binary per platform,
two direct dependencies (cobra, x/term), exact-pinned with the committed
`go.sum`.

## Install

From source:

```bash
cd cli && go install .        # or: go build -o shipperdog .
```

From CI: the `cli-ci` workflow publishes version-stamped tarballs
(linux/darwin, amd64/arm64) as workflow-run artifacts on every run —
download from the Actions tab, extract, put `shipperdog` on `PATH`.
Homebrew distribution is future work (S12-03).

`shipperdog --version` reports the injected build stamp (`dev` for
source builds).

## Login and credentials

```bash
shipperdog login                       # prompts for a PAT (hidden input)
shipperdog login --token sdp_...       # non-interactive
SHIPPERDOG_TOKEN=sdp_... shipperdog ... # CI: no disk writes at all
shipperdog logout
```

`login` verifies the token against `GET /api/v1/auth/me` before storing
anything, then writes it to `~/.shipperdog/credentials` (mode 0600;
directory 0700) and remembers the endpoint in `~/.shipperdog/config`
(also 0600). PATs are minted via `POST /api/v1/auth/tokens` (API or
console) and shown exactly once — see
[docs/authentication.md](../docs/authentication.md).

Endpoint resolution order: `--api` flag, `SHIPPERDOG_API` env, saved
config, then `http://localhost:8080` (the docker compose stack).

Security trade-off (ADR-0004): the token lives in a 0600 file, not an
OS keychain — keychain access needs cgo/OS libraries, which would break
the single static binary. In CI, prefer `SHIPPERDOG_TOKEN` so no secret
touches disk.

## Commands

```bash
# Upload (the issue's example, resolved by version name):
shipperdog artifact upload app.ipa --version 7.12.0 --platform ios --git-commit abc123

# Explicit application (needed when the version name is ambiguous):
shipperdog artifact upload app.aab --app banking --version 7.12.0 --platform android \
    --build-number 842 --git-branch main --pipeline nightly \
    --ci-run-url https://ci.example.com/run/842 --build-timestamp 2026-10-07T09:00:00Z

# Inspect:
shipperdog artifact list              # every visible application's artifacts
shipperdog artifact list banking      # one application
shipperdog version list banking       # versions, newest first
shipperdog artifact view <artifactId> --version <id-or-name>   # full read

# Retry a failed validation:
shipperdog artifact revalidate <artifactId> --version <id-or-name>
```

`--json` on any output-bearing command emits machine-readable JSON.

## Releases (S9-06)

The scripted path from build artifact to promoted release (PRD 20,
FR-015/FR-017). Releases are addressed by their quotable id — `R-1028`,
`r1028`, or `1028` — or by uuid; `--app` (name or uuid) skips the
cross-application lookup.

```bash
# Create a DRAFT release from the version's READY artifacts (newest per
# platform; --artifact platform=id overrides one):
shipperdog release create banking 7.12.0 \
    --tenants 6a1f... --groups 6a1f...=9c0d... \
    --ring tenant-pilot --environment 4b2e...

# Read (the CI decision point):
shipperdog release get R-1028 --json
shipperdog release list banking --version 7.12.0 --status DRAFT
shipperdog release manifest R-1028 --yaml        # after APPROVED

# Drive the PRD 17 machine (one edge; reason mandatory into
# FAILED/ROLLED_BACK):
shipperdog release transition R-1028 APPROVED
shipperdog release transition R-1028 ROLLED_BACK --reason "crash spike"

# Or walk it to PROMOTED in one command — the CI showcase:
shipperdog release promote R-1028
```

- **create** targets tenants (`--tenants`, uuids) with optional
  tester-group narrowing (`--groups tenantId=g1,g2`, or bare `g1,g2`
  for every tenant; omit for tenant-at-large), on a starting ring
  (FR-017 vocabulary until the organization customizes), optionally in
  an environment (S9-04; every target tenant must sit inside its
  assignments). Validation errors mirror the API's own codes, and the
  per-tenant `422 RELEASE_TARGETS_INVALID` prints its full reason list
  so one pass fixes the whole target set.
- **promote** drives the machine forward one legal edge at a time
  (VALIDATING, READY, APPROVAL_PENDING — the APPROVED crossing freezes
  the manifest — DEPLOYING, ACTIVE) and then, since S10-05, through
  the promotion engine's ring hops: one governed ring at a time in the
  organization's effective order, the final ring's hop carrying the
  release across ACTIVE to PROMOTED. Any refusal stops the walk: the
  blocker's code, detail, and full reason list print and the
  command exits 4 — a refused hop answers the COMPLETE blocker list
  (every gate with its evidence URL, the approval round it waits on,
  eligibility exclusions). `--ring` asserts the release's starting
  ring (which never moves; mismatch refuses — usually the wrong
  release). `--tenant` (repeatable) narrows the hops to the named
  targets — the selective staged rollout; absent moves every target.
  FAILED/ROLLED_BACK releases are terminal and refuse; PROMOTED and
  COMPLETED are success no-ops.
- **precheck** answers "why can't I promote" in one screen — the same
  evaluation promote runs, as a read (nothing written, no approval
  round opened); **promotions** renders the append-only timeline:
  every completed hop and every recorded refusal with its evidence
  and actor.
- **transition** is the direct edge-by-edge drive for humans and
  scripts that want one crossing at a time.
- **gates** are live since S10-03 (see the next section): `release
  get` renders the gate block, and a blocked READY crossing names its
  blocking gates; approvals are live since S10-02 — a blocked crossing
  names its pending approvals, and the console renders the rounds and
  takes decisions (the CLI approval command surface remains a later
  story).

## Quality gates (S10-03)

The CI-facing quality-gate surface (FR-026, PRD 32): submit results
from your pipeline with evidence, read the release's gate block, and
override a blocking gate when the exception is warranted. Gate types
are an open vocabulary — the FR-026 built-ins (`unit-tests`,
`integration-tests`, `sast`, `dependency-scan`, `mobile-security-scan`,
`coverage-threshold`, `crash-free-threshold`, `open-critical-bugs`) or
your integration's own kind.

```
# Submit from CI: pass/fail + the evidence report URL (mandatory)
shipperdog gate result R-1028 unit-tests \
    --status pass --evidence-url https://ci.internal/run/9/tests
shipperdog gate result R-1028 coverage-threshold \
    --status pass --value 87.5 --evidence-url https://ci.internal/run/9/cov

# Read the block: per-gate states, thresholds, evidence, overrides
shipperdog gate list R-1028

# The governed exception (requires the gate-override authority)
shipperdog gate override R-1028 coverage-threshold \
    --reason "coverage dip is a known flaky-suite artifact"
```

- Re-submitting a gate is the re-run path: latest wins, history kept
  (the append-only trail answers what was reported when).
- Threshold/ceiling gates require `--value` (the observed number
  rendered beside the organization's threshold); pass/fail gates
  refuse it.
- `crash-free-threshold` and `open-critical-bugs` are metric-backed:
  CI cannot submit them (422 `GATE_METRIC_BACKED`) — analytics
  resolves their values at evaluation time, fail-closed NO_DATA until
  the S11-04 feeds land.
- A failed or data-less REQUIRED gate blocks the release's READY
  crossing (the 422 names every blocking gate and its reason); an
  override — exactly once, with the mandatory reason — stops the gate
  blocking and is audited with actor and reason.

### GitHub Actions showcase

The promote command is the M2 CI showcase (reused by S12-03's action
packaging):

```yaml
- name: Upload and release
  env:
    SHIPPERDOG_API: https://shipperdog.example.com
    SHIPPERDOG_TOKEN: ${{ secrets.SHIPPERDOG_PAT }}
  run: |
    shipperdog artifact upload app.apk --app banking --version 7.12.0 \
        --platform android --git-commit "$GITHUB_SHA" --pipeline "$GITHUB_WORKFLOW"
    shipperdog release create banking 7.12.0 \
        --tenants "$TENANT_ID" --ring engineering > release.json
    shipperdog release promote "$(jq -r .releaseRef release.json)"
```

Exit codes are the branch points: 0 promoted, 4 blocked or rejected
(read the reason list), 3 bad token, 5 network.

### Upload behavior

- Files at or below 100 MiB (the server's resumable threshold,
  `--multipart-limit` to override) stream via `POST /api/v1/artifacts`
  multipart with byte progress on stderr; larger files use the
  direct-to-storage session flow (create session, PUT bytes to storage,
  finalize) — mirroring the server's own routing rule.
- Provenance flags map one-to-one onto the frozen S5-04 fields
  (`git_commit`, `git_branch`, `pipeline`, `ci_run_url`,
  `build_timestamp`); every flag is optional, and the server never
  fabricates a missing claim.
- The command then polls the artifact read until validation settles
  (extraction and validation are async): `READY` exits 0, `FAILED`
  exits 4 printing the failing stage, code, and reason.
  `--no-wait` returns immediately after registration.

## Exit codes

| Code | Class | Meaning |
|---|---|---|
| 0 | success | command completed (upload settled READY) |
| 1 | server | unexpected error, API 5xx, or validation did not settle within `--timeout` |
| 2 | usage | bad flags/arguments, unresolvable or ambiguous names, locally rejected file/platform mismatch |
| 3 | auth | no stored credentials, or the API answered 401 |
| 4 | validation | API rejected the request (4xx problem+json), or the artifact settled validation FAILED |
| 5 | network | connection refused, DNS failure, timeout reaching the API |

On API errors the server's `trace_id` is printed to stderr — the join
key for server-side logs and the audit trail.

## Development

```bash
cd cli
golangci-lint run     # lint gate (config: .golangci.yml)
go test ./...         # unit tests, incl. the OpenAPI endpoint contract test
go build ./...
```

The endpoint contract test (`internal/api/endpoints_test.go`) pins
every URL the CLI composes to `docs/openapi/baseline.yaml`: if the
served contract drops or renames a path, `go test` fails. See
[docs/cli.md](../docs/cli.md) for the architecture notes.
