# CI integration: Jenkins (S12-04)

The Jenkins leg of the CI story (FR-022, PRD 20): a shared library of
pipeline steps that wrap the CLI — upload with provenance
auto-captured from the Jenkins environment, poll to READY, promote
with the blocker list on refusal — plus the inbound
`POST /webhooks/jenkins` receiver that closes the loop from build to
release. GitHub Actions is [ci.md](ci.md); GitLab is
[ci-gitlab.md](ci-gitlab.md); all receivers are
[webhooks.md](webhooks.md).

## The shared library

| Step | Path | What it does |
|---|---|---|
| `shipperdogUpload` | `ci-templates/jenkins/vars/shipperdogUpload.groovy` | Uploads the IPA/APK/AAB, provenance from `GIT_COMMIT`/`BRANCH_NAME`/`BUILD_URL`/`JOB_NAME`, polls validation to READY (`--wait`), fails the step (exit 4) when validation fails |
| `shipperdogPromote` | `ci-templates/jenkins/vars/shipperdogPromote.groovy` | Walks the release to PROMOTED; a refused hop answers the COMPLETE blocker list and fails the step |

Both wrap the CLI (never re-implementing the API) and share its
[exit-code contract](../cli/README.md#exit-codes). Point your
pipeline's Shared Library at this repository (fetch from SCM,
**pin the ref**), then:

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

The full runnable shape (upload, create, promote, and the optional
receiver callback) is
[ci-templates/jenkins/Jenkinsfile.example](../ci-templates/jenkins/Jenkinsfile.example).

Provenance is automatic: `git_commit` from `GIT_COMMIT`, `git_branch`
from `BRANCH_NAME`, `pipeline` from `JOB_NAME`, `ci_run_url` from
`BUILD_URL`, `build_timestamp` from the step's UTC time — the frozen
S5-04 contract ([artifacts.md](artifacts.md)), no flags to remember.

## Credentials: PAT now, per duty

Expose a ShipperDog PAT as `SHIPPERDOG_TOKEN` for the step
(`withCredentials`), minted via `POST /api/v1/auth/tokens` — the same
least-privilege table as [ci.md](ci.md): a dedicated DEVELOPER bot for
upload (`app:artifacts:upload`), a RELEASE_MANAGER bot for promotion
(`app:releases:manage`). Splitting the two tokens is the cheap duty
separation: the build job cannot promote, the promote job cannot
upload. Expire and rotate both.

## CLI acquisition

`path` (a `shipperdog` on the agent's PATH — the common case with a
shared tool), or build from a **pinned commit/tag** of this
repository with Go. The same convention as the composite actions:
never a moving branch. No `cli-v*` release tarballs exist yet — when
they do, prefer the release download **pinned by checksum** (the
deferred convention recorded in #53: the acquisition story switches
to checksum-pinned tarballs and this page links them).

## The inbound receiver

`POST /webhooks/jenkins` — Jenkins has no standard webhook signature,
so the receiver defines a thin documented contract: a build-completed
JSON body signed with the same HMAC-SHA256 shape GitHub uses:

```text
X-Shipperdog-Signature: sha256=hex(HMAC-SHA256(secret, exact-body-bytes))
```

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
(backfill-only — asserted values never overwritten); every other
result is acknowledged and ignored. The `Jenkinsfile.example` notify
stage carries the openssl one-liner:

```bash
SIGNATURE=$(openssl dgst -sha256 -hmac "$SECRET" -hex body.json | sed 's/^.* //')
curl -X POST "$SHIPPERDOG_API/webhooks/jenkins" \
    -H "X-Shipperdog-Signature: sha256=$SIGNATURE" \
    --data-binary @body.json
```

Enable it by setting `WEBHOOKS_JENKINS_SECRETS` on the deployment
(empty = off; every delivery answers the uniform 401). The full
contract — receipts, replay window, metrics, audit, rate limits — is
[webhooks.md](webhooks.md).
