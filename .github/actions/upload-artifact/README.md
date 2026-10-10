# ShipperDog Upload Artifact

Upload an IPA/APK/AAB build artifact to [ShipperDog](https://github.com/ShristiLabs/shipperdog)
from a GitHub Actions workflow, with build provenance auto-captured
from the GitHub Actions context. One step replaces the hand-rolled
curl-and-poll script: the action uploads, attaches provenance, polls
validation to `READY`, and fails the job when the binary is rejected.

## Usage

```yaml
- name: Upload to ShipperDog
  uses: ShristiLabs/shipperdog/.github/actions/upload-artifact@<pinned-ref>
  with:
    file: app/build/outputs/apk/release/app-release.apk
    app: banking
    version: 7.12.0
    platform: android
    api-url: https://shipperdog.example.com
  env:
    SHIPPERDOG_TOKEN: ${{ secrets.SHIPPERDOG_PAT }}
```

The step succeeds when the artifact settles `READY`; the uploaded
artifact id is available as the `artifact-id` output (and the terminal
status as `status`). When validation rejects the binary the step exits
4 and the job summary carries the failing stage, code, and reason.

## Provenance (automatic)

The frozen S5-04 provenance contract is filled from the workflow
context -- no flags to remember, no busywork:

| ShipperDog field | Captured from |
|---|---|
| `git_commit` | `GITHUB_SHA` |
| `git_branch` | `GITHUB_REF_NAME` |
| `pipeline` | `GITHUB_WORKFLOW` |
| `ci_run_url` | `$GITHUB_SERVER_URL/$GITHUB_REPOSITORY/actions/runs/$GITHUB_RUN_ID` |
| `build_timestamp` | the step's UTC time (ISO-8601) |

Every captured value can be overridden with `extra-args` (the CLI
takes the last occurrence of a flag), e.g.
`extra-args: --pipeline nightly`.

## Inputs

| Input | Required | Default | Notes |
|---|---|---|---|
| `file` | yes | - | Path to the built binary (IPA, APK, or AAB) |
| `version` | yes | - | Version name (like `7.12.0`) or uuid; names need `app` unless unambiguous |
| `platform` | yes | - | `ios` or `android` |
| `app` | no | `''` | Application name or uuid |
| `build-number` | no | `''` | CI-pinned build number |
| `api-url` | no | `''` | API origin; falls back to `SHIPPERDOG_API` |
| `token` | no | `''` | PAT; prefer the `SHIPPERDOG_TOKEN` env from a repo secret |
| `cli-source` | no | `path` | `path`, `source`, or `release` (below) |
| `cli-version` | no | pinned commit | The CLI pin for `source`/`release` modes |
| `timeout` | no | `10m` | Max wait for validation to settle |
| `extra-args` | no | `''` | Appended verbatim to `shipperdog artifact upload` |

## Outputs

| Output | Notes |
|---|---|
| `artifact-id` | The uploaded artifact id (success only) |
| `status` | Terminal status (`READY`, or `FAILED` when validation rejected) |

## Getting the CLI onto the runner

The action wraps the `shipperdog` CLI (it never re-implements the
API); `cli-source` decides how the binary arrives:

- **`path`** (default) -- a `shipperdog` already on `PATH`. Build it
  yourself (`actions/checkout` + `cd cli && go install .`), or fetch a
  `cli-ci` workflow-run artifact.
- **`source`** -- clone this repository at the pinned `cli-version`
  (a commit or tag, never a moving branch) and `go build` the CLI.
  Needs Go on the runner (`actions/setup-go`).
- **`release`** -- download the `shipperdog-<os>-<arch>.tar.gz`
  attached to this repository's `cli-v*` releases (the distribution
  channel; available once CLI releases are published).

The default `cli-version` is the CLI revision this action release was
tested against; pin your workflows to a tagged ref of this repository
and override `cli-version` deliberately.

## Credentials

Store a ShipperDog PAT as a repository/organization secret
(`SHIPPERDOG_PAT` in the examples) and pass it through the
`SHIPPERDOG_TOKEN` env -- the CLI then never writes a credential to
disk. Least privilege: a bot account whose organization role is
`DEVELOPER` (the `app:artifacts:upload` authority) is enough for this
step; see [docs/ci.md](../../../../docs/ci.md) for the token strategy
and the OIDC/GitHub-App upgrade path.

## Exit codes (the CI branch points)

| Code | Meaning |
|---|---|
| 0 | Artifact settled `READY` |
| 2 | Usage: bad flags, unresolvable names, wrong file/platform |
| 3 | No credential, or the API answered 401 |
| 4 | Validation rejected the binary (reason in the job summary) |
| 5 | Network: refused, DNS, timeout |
