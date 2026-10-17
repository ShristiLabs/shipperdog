---
description: Manage versions and tags - create the version, upload immutable builds with the CLI, tag them, archive, and revoke distribution.
---

# Manage versions and tags

A version is the `7.12.0` line of your application; its artifacts —
the immutable `.ipa` / `.apk` / `.aab` binaries — arrive by
[upload](/concepts/artifact-pipeline.md) and never change. Version
management lives in the CLI and API (the Console's application page
shows the registry facts), so this guide walks both surfaces.

::: tip Where the screens are
The Console's application detail page carries the registry's facts —
bundle identifiers, ownership, audit trail. The version list and
artifact inventories you drive from the
[CLI](/how-to/automation/cli-reference.md), which is also what your
CI pipeline uses.
:::

## Create a version

1. Create the version with the API (an application-scoped call):

   ```bash
   curl -X POST "$SHIPPERDOG_API/api/v1/applications/$APP_ID/versions" \
     -H "Authorization: Bearer $TOKEN" \
     -H "Content-Type: application/json" \
     -d '{"versionName": "7.12.0"}'
   ```

2. Or let your pipeline name the version directly at upload — the
   CLI's `--version` flag takes the name (for example `7.12.0`) once
   the version exists; names need `--app` when several applications
   share the name.

## Upload builds to the version

```bash
shipperdog artifact upload app-release.apk \
  --app banking --version 7.12.0 --platform android
```

- The platform is `ios` or `android`; the file may be an `.ipa`,
  `.apk`, or `.aab`.
- Provenance flags (`--git-commit`, `--git-branch`, `--pipeline`,
  `--ci-run-url`, `--build-timestamp`) are optional and never
  fabricated — the
  [GitHub Actions integration](/how-to/automation/github-actions.md)
  fills them from the build context automatically.
- The command waits for validation to settle by default: it exits
  `0` when the artifact is READY and `4` when validation rejected
  it, printing the failing stage.

Large files (above 100 MiB by default) switch to a resumable upload
session automatically.

## Inspect artifacts

- `shipperdog artifact list banking` — one row per artifact slot
  across the application's versions; `--version 7.12.0` narrows it.
- `shipperdog artifact view <artifactId> --version 7.12.0 --app banking`
  — the full extraction and validation block: per-stage status,
  format verdict, provenance.
- `shipperdog artifact revalidate <artifactId> --version 7.12.0 --app banking`
  — re-runs validation on a FAILED or stalled artifact.

## Tag versions

Tags are short labels (`rc1`, `hotfix-candidate`) that make versions
findable:

1. Set the tags on a version — the write replaces the full set:

   ```bash
   curl -X PUT "$SHIPPERDOG_API/api/v1/applications/$APP_ID/versions/$VERSION_ID/tags" \
     -H "Authorization: Bearer $TOKEN" \
     -H "Content-Type: application/json" \
     -d '{"tags": ["rc1", "qa-passed"]}'
   ```

2. List by tag from the CLI:

   ```bash
   shipperdog version list banking --tag rc1
   ```

## Archive and revoke distribution

Two distinct end-of-life moves, both through the version API:

- **Archive** (`POST .../versions/{id}/archive`) — the version leaves
  the working set. `shipperdog version list` shows DRAFT and ACTIVE
  versions by default; pass `--include archived,revoked` to widen.
- **Revoke distribution** (`POST .../versions/{id}/revoke-distribution`)
  — stronger: existing download links stop answering. A mandatory
  reason records why.

::: tip Tags are metadata, never a gate
A tag never decides who receives a build — releases and rings do.
See [rings and promotion](/how-to/console/rings-and-promotion.md).
:::

**Related guides:** [the CLI reference](/how-to/automation/cli-reference.md) ·
[artifact pipeline concepts](/concepts/artifact-pipeline.md) ·
[work with releases](/how-to/console/releases.md)
