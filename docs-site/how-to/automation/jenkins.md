---
description: Jenkins integration - the shared-library steps that upload builds and promote releases, plus the signed build-completed webhook.
---

# ShipperDog from Jenkins

ShipperDog's Jenkins integration is a **shared library** of pipeline
steps that wrap the [CLI](/how-to/automation/cli-reference.md):
upload with provenance auto-captured from the Jenkins environment,
then promote with the complete blocker list on refusal.

## The steps

| Step | What it does |
|---|---|
| `shipperdogUpload` | Uploads the IPA/APK/AAB with provenance from the Jenkins environment, polls validation to READY, fails the step (the CLI's exit 4) when validation rejects the binary |
| `shipperdogPromote` | Walks the release to PROMOTED; a refused hop answers the complete blocker list and fails the step |

## Set up the shared library

1. In Jenkins: **Manage Jenkins — System — Global Pipeline
   Libraries**, add a library with fetch-from-SCM pointing at the
   ShipperDog repository, **pinning the ref** — never a moving
   branch.

2. Make sure a `shipperdog` CLI is on the agent's PATH (the common
   case with a shared tool), or build it from a pinned commit/tag
   of the repository with Go in a preparatory step.

3. Store the ShipperDog PATs as Jenkins credentials —
   `shipperdog-pat-upload` and `shipperdog-pat-release` in the
   examples — minted per duty (a Developer bot for upload, a
   Release Manager bot for promotion), both expiring and rotated.

## Upload a build

```groovy
stage('Upload to ShipperDog') {
    steps {
        withCredentials([string(credentialsId: 'shipperdog-pat-upload', variable: 'SHIPPERDOG_TOKEN')]) {
            shipperdogUpload(
                file: 'app/build/outputs/apk/release/app-release.apk',
                app: 'banking',
                version: '7.12.0',
                platform: 'android'
            )
        }
    }
}
```

Provenance is automatic: `git_commit` from `GIT_COMMIT`,
`git_branch` from `BRANCH_NAME`, `pipeline` from `JOB_NAME`,
`ci_run_url` from `BUILD_URL`, `build_timestamp` from the step's
UTC time. Set `SHIPPERDOG_API` in the environment when your
deployment is not the default.

## Create and promote the release

```groovy
stage('Create + promote the release') {
    steps {
        withCredentials([string(credentialsId: 'shipperdog-pat-release', variable: 'SHIPPERDOG_TOKEN')]) {
            script {
                def releaseRef = sh(
                    returnStdout: true,
                    script: """
                        set -eu
                        shipperdog release create banking 7.12.0 \
                            --tenants \"\$TENANT_ID\" --ring engineering --json \
                            | jq -r '.releaseRef'
                    """
                ).trim()
                shipperdogPromote(release: releaseRef, app: 'banking')
            }
        }
    }
}
```

`shipperdogPromote` walks the release to PROMOTED; when a gate,
approval, or policy refuses, the complete blocker list fails the
step — read it before re-running. The full runnable shape (upload,
create, promote, and the optional receiver callback) ships as the
repository's `ci-templates/jenkins/Jenkinsfile.example`.

## The build-completed webhook (optional)

To let ShipperDog attach run URLs to artifacts built without the
provenance flags, post a signed build-completed event at the end of
the pipeline:

```bash
SIGNATURE=$(openssl dgst -sha256 -hmac "$SECRET" -hex body.json | sed 's/^.* //')
curl -X POST "$SHIPPERDOG_API/webhooks/jenkins" \
    -H "X-Shipperdog-Signature: sha256=$SIGNATURE" \
    --data-binary @body.json
```

The body is a thin documented contract — Jenkins has no standard
webhook signature, so the receiver defines one:

```json
{
  "deliveryId": "one-uuid-per-posted-event",
  "result": "SUCCESS",
  "gitCommit": "0123456789abcdef0123456789abcdef01234567",
  "gitBranch": "main",
  "pipeline": "mobile",
  "buildUrl": "https://jenkins.example.com/job/mobile/42/",
  "buildTimestamp": "1760000000"
}
```

A `SUCCESS` build whose `gitCommit` matches artifacts' frozen
`git_commit` claims backfills their `ci_run_url` and `pipeline`
(backfill-only — values your upload already asserted are never
overwritten). Your platform operator enables the receiver with
`WEBHOOKS_JENKINS_SECRETS`; the full contract — receipts, replay
window, retries — is on the
[webhooks](/how-to/automation/webhooks.md) page.

**Next:** [GitHub Actions](/how-to/automation/github-actions.md) ·
[GitLab](/how-to/automation/gitlab.md) · the
[inbound webhooks](/how-to/automation/webhooks.md) reference.

**Related guides:** [versions and tags](/how-to/console/versions-and-tags.md) ·
[quality gates](/how-to/console/quality-gates.md) ·
[developer quickstart](/roles/developer.md)
