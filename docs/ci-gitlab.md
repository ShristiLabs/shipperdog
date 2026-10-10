# CI integration: GitLab CI (S12-04)

The GitLab leg of the CI story (FR-022, PRD 20): include-able job
templates that wrap the CLI — upload with provenance auto-captured
from the GitLab CI environment, then create-and-promote — plus the
inbound `POST /webhooks/gitlab` receiver that closes the loop.
GitHub Actions is [ci.md](ci.md); Jenkins is [ci-jenkins.md](ci-jenkins.md);
all receivers are [webhooks.md](webhooks.md).

## The templates

[ci-templates/gitlab/shipperdog.gitlab-ci.yml](../ci-templates/gitlab/shipperdog.gitlab-ci.yml)
ships two hidden jobs you extend with your app's facts:

```yaml
include:
  - project: 'ShristiLabs/shipperdog'
    ref: <pinned-ref>        # never a moving branch
    file: 'ci-templates/gitlab/shipperdog.gitlab-ci.yml'

shipperdog:upload:
  extends: .shipperdog-upload
  variables:
    SHIPPERDOG_APP: 'banking'
    SHIPPERDOG_VERSION: '7.12.0'
    SHIPPERDOG_PLATFORM: 'android'
    SHIPPERDOG_FILE: 'app/build/outputs/apk/release/app-release.apk'

shipperdog:release:
  extends: .shipperdog-promote
  variables:
    SHIPPERDOG_APP: 'banking'
    SHIPPERDOG_VERSION: '7.12.0'
    SHIPPERDOG_TENANTS: '<tenant-uuid>'
```

The upload template polls validation to READY (`--wait`) and fails
the job (the CLI's exit 4) with the failing stage when validation
fails; the promote template creates the release and walks it to
PROMOTED, the COMPLETE blocker list on refusal.

Provenance is automatic, from the GitLab CI environment: `git_commit`
from `CI_COMMIT_SHA`, `git_branch` from `CI_COMMIT_REF_NAME`,
`ci_run_url` from `CI_PIPELINE_URL`, `pipeline` from
`gitlab:$CI_PROJECT_PATH`, `build_timestamp` from the job's UTC time
— the frozen S5-04 contract ([artifacts.md](artifacts.md)). This is
what lets the receiver match a finished pipeline back to the
artifacts its commit built.

## Credentials

Expose the PAT as the masked CI/CD variable `SHIPPERDOG_TOKEN`, and
`SHIPPERDOG_API` as your deployment's base URL. The same
least-privilege table as [ci.md](ci.md): a dedicated DEVELOPER bot
for upload (`app:artifacts:upload`), a RELEASE_MANAGER bot for
promotion (`app:releases:manage`), both expiring and rotated.

## CLI acquisition

The templates run on a plain base image; your pipeline provides the
`shipperdog` CLI — either your own image with a **pinned** build
baked in, or a preparatory job that builds it from a **pinned
commit/tag** of this repository with Go — never a moving branch. No
`cli-v*` release tarballs exist yet; when they do, prefer the release
download **pinned by checksum** (the deferred convention recorded in
#53).

## The inbound receiver

`POST /webhooks/gitlab` — GitLab's webhook protocol sends the
configured secret verbatim in `X-Gitlab-Token` (it signs nothing), so
the receiver compares the token constant-time over a rotating list
and **the transport must be TLS**. Configure the webhook in GitLab
(URL, the same token as `WEBHOOKS_GITLAB_SECRETS` on the deployment,
`application/json` content type) and subscribe it to the Pipeline
event.

**Event consumed:** `pipeline` with
`object_attributes.status == "success"`. The pipeline's `sha`
backfills `ci_run_url` (the pipeline URL) and `pipeline` (the
project path) onto commit-matched artifacts, backfill-only; every
other event (push, merge request, a failed pipeline) is acknowledged
and ignored. GitLab stamps its deliveries (`finished_at` /
`created_at` inside the event, `X-Gitlab-Webhook-UUID` as the
delivery id) — the replay window and the ledger both apply. The full
contract is [webhooks.md](webhooks.md).
