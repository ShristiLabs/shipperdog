---
description: GitHub Actions integration - upload artifacts and promote releases with ShipperDog's two composite actions.
---

# ShipperDog from GitHub Actions

ShipperDog ships two composite actions that wrap the
[CLI](/how-to/automation/cli-reference.md): one uploads your build
with provenance attached, the other drives a release to PROMOTED —
no hand-rolled curl steps.

| Action | What it does |
|---|---|
| `ShristiLabs/shipperdog/.github/actions/upload-artifact` | Uploads the IPA/APK/AAB with provenance auto-captured from the GitHub Actions context, polls validation to READY, fails the job (exit 4) with the reason in the job summary |
| `ShristiLabs/shipperdog/.github/actions/promote-release` | Walks the release to PROMOTED; on refusal fails the job with the COMPLETE blocker list in the job summary and the `blockers` output |

Pin your workflows to a tagged ref of the repository — never a
moving branch.

## Prepare the credentials

1. Create **dedicated CI bot users** in ShipperDog, one per duty —
   the cheap separation where the build job cannot promote and the
   promote job cannot upload:

   | Duty | Bot's org role | What it exercises |
   |---|---|---|
   | Upload artifacts | Developer | artifact uploads |
   | Create / promote releases | Release Manager | release management, gate reporting |

2. Mint each bot a personal access token (`POST /api/v1/auth/tokens`),
   with an expiry, and store them as repository (or organization)
   secrets — `SHIPPERDOG_PAT_UPLOAD` and `SHIPPERDOG_PAT_RELEASE`
   in the examples below.

3. Surface the token to the action as the `SHIPPERDOG_TOKEN` env —
   the CLI then never writes a credential to disk:

   ```yaml
   env:
     SHIPPERDOG_API: https://shipperdog.example.com
     SHIPPERDOG_TOKEN: ${{ secrets.SHIPPERDOG_PAT_UPLOAD }}
   ```

## Upload Artifact

```yaml
- name: Upload to ShipperDog
  id: upload
  uses: ShristiLabs/shipperdog/.github/actions/upload-artifact@<pinned-ref>
  with:
    file: app/build/outputs/apk/release/app-release.apk
    app: banking
    version: 7.12.0
    platform: android
  env:
    SHIPPERDOG_TOKEN: ${{ secrets.SHIPPERDOG_PAT_UPLOAD }}
```

Inputs: `file`, `version`, `platform` (required); `app` (needed when
the version name is ambiguous), `build-number`, `api-url`, `token`
(prefer the env), `cli-source` / `cli-version`, `timeout`,
`extra-args`.

Outputs: `artifact-id` and `status` (`READY`, or `FAILED` when
validation rejected the binary — the job fails with the reason
rendered into the summary).

Provenance is automatic: `git-commit` from `GITHUB_SHA`, `git-branch`
from `GITHUB_REF_NAME`, `pipeline` from `GITHUB_WORKFLOW`,
`ci-run-url` from the run's URL, `build-timestamp` from the step's
UTC time. No flags to remember, nothing to forget.

## Promote Release

```yaml
- name: Promote
  uses: ShristiLabs/shipperdog/.github/actions/promote-release@<pinned-ref>
  with:
    release: ${{ steps.release.outputs.release-ref }}
    app: banking
  env:
    SHIPPERDOG_TOKEN: ${{ secrets.SHIPPERDOG_PAT_RELEASE }}
```

Inputs: `release` (the `R-…` id; required); `app`, `ring` (assert
the starting ring — a mismatch usually means the wrong release),
`tenant` (one target tenant uuid per line, the selective staged
rollout), `api-url`, `token`, `cli-source` / `cli-version`,
`extra-args`.

Outputs: `promoted` (true/false), `blockers` (the refusal reason
list when blocked), `release-ref`.

## Getting the CLI onto the runner

Both actions share the `cli-source` acquisition contract:

- `path` (default) — a `shipperdog` already on PATH.
- `source` — built from the pinned commit/tag in `cli-version`
  (needs Go on the runner, for example via `actions/setup-go`).
- `release` — downloaded from the repository's `cli-v*` release
  tarballs, once published.

The default pins the CLI revision the actions were tested against.

## The end-to-end shape

```yaml
name: shipperdog-release
on:
  push:
    tags: ['v*']
permissions:
  contents: read
jobs:
  release:
    runs-on: ubuntu-latest
    env:
      SHIPPERDOG_API: https://shipperdog.example.com
    steps:
      - uses: actions/checkout@v4
      - name: Build
        run: ./gradlew assembleRelease bundleRelease

      - name: Upload to ShipperDog
        id: upload
        uses: ShristiLabs/shipperdog/.github/actions/upload-artifact@<pinned-ref>
        with:
          file: app/build/outputs/apk/release/app-release.apk
          app: banking
          version: 7.12.0
          platform: android

      - name: Create the release
        id: release
        run: |
          ref=$(shipperdog release create banking 7.12.0 \
              --tenants "$TENANT_ID" --ring engineering --json | jq -r .releaseRef)
          echo "release-ref=$ref" >> "$GITHUB_OUTPUT"
        env:
          SHIPPERDOG_TOKEN: ${{ secrets.SHIPPERDOG_PAT_RELEASE }}

      - name: Promote
        uses: ShristiLabs/shipperdog/.github/actions/promote-release@<pinned-ref>
        with:
          release: ${{ steps.release.outputs.release-ref }}
          app: banking
        env:
          SHIPPERDOG_TOKEN: ${{ secrets.SHIPPERDOG_PAT_RELEASE }}
```

On a bare runner, set `cli-source: source` (with
`actions/setup-go`) or `release` on **both** actions and they
acquire the CLI themselves.

::: tip Why jobs fail loudly
Both actions carry the CLI's
[exit codes](/how-to/automation/cli-reference.md#exit-codes):
`2` usage, `3` bad token, `4` validation rejected the binary or a
gate/approval/policy refused the walk, `5` network. The blocker
list lands in the job summary — read it before re-running.
:::

**Next:** [Jenkins](/how-to/automation/jenkins.md) and
[GitLab](/how-to/automation/gitlab.md) run the same shapes ·
[inbound webhooks](/how-to/automation/webhooks.md) close the loop.

**Related guides:** [versions and tags](/how-to/console/versions-and-tags.md) ·
[work with releases](/how-to/console/releases.md) ·
[developer quickstart](/roles/developer.md)
