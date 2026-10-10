# CI integration: GitHub Actions (S12-03)

The GitHub Actions page of the CI story (FR-022, PRD 20): two
composite actions that wrap the CLI so a workflow uploads its build
with provenance attached and drives a release to PROMOTED without
hand-rolled curl steps, and the inbound `POST /webhooks/github`
receiver that closes the loop from build to release. Jenkins is
[ci-jenkins.md](ci-jenkins.md) and GitLab is [ci-gitlab.md](ci-gitlab.md)
(S12-04, issue #54), reusing the same CLI and the same receiver
framework; the reference for all five inbound receivers is
[webhooks.md](webhooks.md).

## The actions

| Action | Path | What it does |
|---|---|---|
| Upload Artifact | `.github/actions/upload-artifact` | Uploads IPA/APK/AAB with provenance auto-captured from the GHA context, polls validation to READY, fails the job (exit 4) with the reason in the job summary |
| Promote Release | `.github/actions/promote-release` | Walks the release to PROMOTED; on refusal fails the job with the COMPLETE blocker list in the job summary and the `blockers` output |

Both wrap the CLI (never re-implementing the API) and share its
[exit-code contract](../cli/README.md#exit-codes) — the branch points
CI reasons over. Marketplace-style READMEs with the full input/output
tables live beside each action; pin your workflows to a tagged ref of
this repository (`ShristiLabs/shipperdog/.github/actions/<name>@<ref>`).

The CLI acquisition contract (`cli-source` input): `path` (a
`shipperdog` already on PATH — default), `source` (built from a
pinned commit/tag via Go on the runner), or `release` (the
`cli-v*` release tarballs, once published). The default pin is the
CLI revision the action was tested against — never a moving branch.

## Credentials: PAT now, OIDC/GitHub App later

Store a ShipperDog PAT as a GitHub repository (or organization)
secret — `SHIPPERDOG_PAT` in every example here — and surface it as
the `SHIPPERDOG_TOKEN` env so the CLI never writes a credential to
disk:

```yaml
env:
  SHIPPERDOG_TOKEN: ${{ secrets.SHIPPERDOG_PAT }}
```

Mint PATs via `POST /api/v1/auth/tokens` (console or API; see
[authentication.md](authentication.md)). PATs carry no role of their
own — the token authenticates as its user, and permissions come from
that user's memberships. Least privilege is therefore a
**dedicated CI bot user per duty**:

| Duty | Org role of the bot user | The authority exercised |
|---|---|---|
| Upload artifacts | `DEVELOPER` | `app:artifacts:upload` |
| Create releases | `APP_OWNER` or `RELEASE_MANAGER` | `app:releases:manage` |
| Promote / gate results | `RELEASE_MANAGER` | `app:releases:manage`, gate reporting |

Splitting the upload token from the promote token is the cheap duty
separation: the build job cannot promote, the promote job cannot
upload. Expire both (`expiresInSeconds`) and rotate on a schedule.

**The upgrade path (out of scope here, by design):** OIDC federation
(a workflow exchanging its GitHub-issued OIDC token for a ShipperDog
session — no static secret at all) and the GitHub App installation
flow are the M3 replacements for the PAT; the actions' `token` input
surface is the only seam they change.

## The end-to-end workflow

The showcase the cli/README snippet seeded, now with the actions
(the runnable self-test this repository runs on demand is
`.github/workflows/ci-integration-smoke.yml`):

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
          api-url: ${{ env.SHIPPERDOG_API }}
        env:
          SHIPPERDOG_TOKEN: ${{ secrets.SHIPPERDOG_PAT_UPLOAD }}

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
          api-url: ${{ env.SHIPPERDOG_API }}
        env:
          SHIPPERDOG_TOKEN: ${{ secrets.SHIPPERDOG_PAT_RELEASE }}
```

The example assumes the `shipperdog` CLI on PATH (the actions'
`cli-source: path` default, the shape the smoke workflow above runs);
on a bare runner set `cli-source: source` (needs `actions/setup-go`)
or `release` on BOTH actions and they acquire it themselves.

Provenance is automatic on the upload step: `git_commit` from
`GITHUB_SHA`, `git_branch` from `GITHUB_REF_NAME`, `pipeline` from
`GITHUB_WORKFLOW`, `ci_run_url` from the run's URL, `build_timestamp`
from the step's UTC time — the frozen S5-04 contract
([artifacts.md](artifacts.md)), no flags to remember.

## The inbound webhook receiver

`POST /webhooks/github` (PRD 19) — outside `/api/v1` by design: the
delivery's HMAC-SHA256 signature (`X-Hub-Signature-256`,
`sha256=hex(HMAC-SHA256(secret, body))`, compared constant-time) IS
the authentication. Configure the GitHub webhook with content type
`application/json`.

**Events consumed:** `workflow_run` with `action=completed`. The
completed run's `head_sha` matches artifacts' frozen `git_commit`
claims; matched, SETTLED artifacts (validation no longer in flight)
missing `ci_run_url` or `pipeline` get them backfilled from the run's
URL and workflow name — backfill-only, so asserted values are never
overwritten (the one deliberate, audited exception to S5-04's
write-once provenance contract). Teams not using the upload action
still get run URLs attached; teams using it get a no-op (their
uploads already carry them). Every other event is acknowledged 200
and ignored — subscribe the webhook to what you use.

**The contract:**

| Delivery | Answer |
|---|---|
| Valid signature, processed | `200 {"status":"ACCEPTED"\|"IGNORED", "deliveryId":...}` |
| Same `X-GitHub-Delivery` redelivered | `200 {"status":"DUPLICATE",...}` — the ledger's idempotent no-op |
| Any authenticity failure (absent/malformed/wrong key/none configured) | `401 WEBHOOK_SIGNATURE_REJECTED` — uniform, never a hint which leg refused |
| Verified sender, unreadable JSON or missing delivery-id header | `400 WEBHOOK_PAYLOAD_MALFORMED` |
| Body beyond the ceiling | `413 WEBHOOK_PAYLOAD_TOO_LARGE` |

Every outcome is audited (`INBOUND_WEBHOOK_ACCEPTED/IGNORED/
REJECTED/DUPLICATE`, and `ARTIFACT_PROVENANCE_ENRICHED` with the
enriched ids) — never the secret, never the raw payload.

**Configuration** (environment only; no committed secrets — the empty
default keeps the receiver OFF):

| Property | Env | Default | Meaning |
|---|---|---|---|
| `shipperdog.webhooks.github-secrets` | `WEBHOOKS_GITHUB_SECRETS` | empty | Comma-separated HMAC secrets — a LIST, so rotation is overlap (add the new, remove the old) |
| `shipperdog.webhooks.max-timestamp-skew` | `WEBHOOKS_MAX_TIMESTAMP_SKEW` | PT5M | Replay window for providers that timestamp deliveries (S12-04's store receivers; GitHub sends none, so it never applies here) |
| `shipperdog.webhooks.max-body-bytes` | `WEBHOOKS_MAX_BODY_BYTES` | 26214400 | Inbound payload ceiling (GitHub's own 25 MB cap) |

The generic receiver framework underneath — constant-time
verification over a rotating credential list, the timestamp-window
replay defense, and the delivery-id ledger
(`inbound_webhook_deliveries`, V41) — is the surface the S12-04
receivers reuse: [Jenkins](ci-jenkins.md) (the receiver-defined HMAC
contract), [GitLab](ci-gitlab.md) (the provider's token protocol),
the [App Store and Google Play store receivers](webhooks.md), and
E13's connectors after them. The S12-04 hardening applies here too:
per-client rate limits on `POST /webhooks/**`, the bounded-read body
filter, and the `shipperdog_webhooks_deliveries` metric — all
documented in the [webhook reference](webhooks.md).
