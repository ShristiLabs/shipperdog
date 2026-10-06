# ShipperDog CLI

The developer-side companion (PRD 20, story S5-06): upload and inspect
artifacts from the terminal so CI pipelines and humans can drive
ShipperDog without curl and JSON.

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
