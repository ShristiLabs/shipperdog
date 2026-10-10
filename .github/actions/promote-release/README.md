# ShipperDog Promote Release

Drive a [ShipperDog](https://github.com/ShristiLabs/shipperdog)
release to `PROMOTED` from a GitHub Actions workflow: the action walks
the release state machine and the promotion rings one governed step at
a time (every gate, approval, eligibility check, and policy evaluated
live) and, when something refuses the walk, fails the job with the
COMPLETE blocker list rendered into the job summary.

## Usage

```yaml
- name: Promote the release
  uses: ShristiLabs/shipperdog/.github/actions/promote-release@<pinned-ref>
  with:
    release: ${{ steps.create.outputs.release-ref }}
    app: banking
    api-url: https://shipperdog.example.com
  env:
    SHIPPERDOG_TOKEN: ${{ secrets.SHIPPERDOG_PAT }}
```

On success the release is `PROMOTED` and the summary says so. When a
gate, approval, or policy refuses, the step exits 4, the job summary
carries the complete blocker list (every blocking gate with its
evidence, the approval round it waits on, eligibility exclusions), and
the `blockers` output carries it for downstream steps. Add
`continue-on-error: true` when a blocked promotion should not fail the
job (a manual-approval release train, for example).

## Inputs

| Input | Required | Default | Notes |
|---|---|---|---|
| `release` | yes | - | `R-1028`, `r1028`, `1028`, or uuid |
| `app` | no | `''` | Owning application (name or uuid); skips the cross-application lookup |
| `ring` | no | `''` | Asserts the starting ring; a mismatch refuses (usually the wrong release) |
| `tenant` | no | `''` | Target tenant uuids, one per line (the selective staged rollout); absent moves every target |
| `api-url` | no | `''` | API origin; falls back to `SHIPPERDOG_API` |
| `token` | no | `''` | PAT; prefer the `SHIPPERDOG_TOKEN` env from a repo secret |
| `cli-source` | no | `path` | `path`, `source`, or `release` (same contract as the upload action) |
| `cli-version` | no | pinned commit | The CLI pin for `source`/`release` modes |
| `extra-args` | no | `''` | Appended verbatim to `shipperdog release promote` |

## Outputs

| Output | Notes |
|---|---|
| `promoted` | `true` when the release reached `PROMOTED` (or already was), `false` when refused |
| `blockers` | The refusal reason list (empty on success) |
| `release-ref` | The release quotable id the walk stopped at |

## Getting the CLI onto the runner

Identical to the upload action's contract: `cli-source` `path`
(default), `source` (pinned `cli-version`, Go on the runner), or
`release` (`cli-v*` release tarballs once published). See the
[upload action's README](../upload-artifact/README.md#getting-the-cli-onto-the-runner).

## Credentials

Same as the upload action: `SHIPPERDOG_TOKEN` from a repo secret.
Least privilege differs by design -- promotion needs the
`app:releases:manage` authority, so run this step with a
`RELEASE_MANAGER` (or `APP_OWNER`) persona bot token, distinct from
the upload step's `DEVELOPER` token when you separate duties. See
[docs/ci.md](../../../../docs/ci.md).

## Exit codes (the CI branch point)

| Code | Meaning |
|---|---|
| 0 | Release is `PROMOTED` (or was already `PROMOTED`/`COMPLETED`) |
| 2 | Usage: wrong `--ring`, unresolvable reference |
| 3 | No credential, or the API answered 401 |
| 4 | Blocked: a gate, approval, eligibility, or policy refused the walk (blocker list in the job summary and the `blockers` output) |
| 5 | Network: refused, DNS, timeout |
