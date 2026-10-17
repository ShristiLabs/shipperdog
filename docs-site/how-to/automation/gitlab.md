---
description: GitLab CI integration - include the job templates, extend them with your app's facts, and close the loop with the pipeline webhook.
---

# ShipperDog from GitLab CI

ShipperDog's GitLab integration is an **include-able template** with
two hidden jobs you extend with your application's facts — upload
with provenance auto-captured from the GitLab CI environment, then
create-and-promote.

## Include the templates

In your `.gitlab-ci.yml`:

```yaml
include:
  - project: 'ShristiLabs/shipperdog'
    ref: <pinned-ref>        # never a moving branch
    file: 'ci-templates/gitlab/shipperdog.gitlab-ci.yml'
```

## Prepare the runners and credentials

1. The templates run on a plain base image; provide the
   `shipperdog` [CLI](/how-to/automation/cli-reference.md) — either
   your own image with a pinned build baked in, or a preparatory
   job that builds it from a pinned commit/tag of the repository
   with Go. Never a moving branch.

2. Expose the PAT as the masked CI/CD variable `SHIPPERDOG_TOKEN`,
   and `SHIPPERDOG_API` as your deployment's base URL.

3. One bot per duty, as everywhere: a Developer bot's token for the
   upload job, a Release Manager bot's for the promote job — both
   expiring and rotated.

## Upload your build

```yaml
shipperdog:upload:
  extends: .shipperdog-upload
  variables:
    SHIPPERDOG_APP: 'banking'
    SHIPPERDOG_VERSION: '7.12.0'
    SHIPPERDOG_PLATFORM: 'android'
    SHIPPERDOG_FILE: 'app/build/outputs/apk/release/app-release.apk'
```

The template polls validation to READY and fails the job with the
failing stage when validation rejects the binary (the CLI's exit
4). Provenance is automatic from the GitLab CI environment:
`git_commit` from `CI_COMMIT_SHA`, `git_branch` from
`CI_COMMIT_REF_NAME`, `ci_run_url` from `CI_PIPELINE_URL`,
`pipeline` from the project path, `build_timestamp` from the job's
UTC time.

## Create and promote the release

```yaml
shipperdog:release:
  extends: .shipperdog-promote
  variables:
    SHIPPERDOG_APP: 'banking'
    SHIPPERDOG_VERSION: '7.12.0'
    SHIPPERDOG_TENANTS: '<tenant-uuid>'
```

The promote template creates the release and walks it to PROMOTED;
on refusal the complete blocker list fails the job.

## Close the loop with the pipeline webhook (optional)

To let ShipperDog attach pipeline URLs to artifacts whose uploads
did not carry them, configure a webhook in GitLab:

1. **Settings — Webhooks** in your project: URL
   `https://<your-deployment>/webhooks/gitlab`, content type
   `application/json`, and a secret token — the same token your
   platform operator sets as `WEBHOOKS_GITLAB_SECRETS`.

2. Subscribe the webhook to the **Pipeline** event.

GitLab sends the token verbatim in `X-Gitlab-Token` (it signs
nothing), so the transport must be TLS. A successful pipeline whose
commit matches artifacts' frozen `git_commit` claims backfills
their run URL and pipeline name — backfill-only; every other event
is acknowledged and ignored. The full contract is on the
[webhooks](/how-to/automation/webhooks.md) page.

**Next:** [GitHub Actions](/how-to/automation/github-actions.md) ·
[Jenkins](/how-to/automation/jenkins.md) · the
[inbound webhooks](/how-to/automation/webhooks.md) reference.

**Related guides:** [versions and tags](/how-to/console/versions-and-tags.md) ·
[work with releases](/how-to/console/releases.md) ·
[developer quickstart](/roles/developer.md)
