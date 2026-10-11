# Deployment Runbook (Cloud Run, M1)

The repeatable production path for the ShipperDog M1 stack (issue
#39, S8-05): api + portal on Cloud Run with managed TLS, a private
Cloud SQL/Memorystore data tier, and a post-deploy smoke test.
Topology decisions and their reasoning: [ADR-0005](adr/ADR-0005-cloud-run-deployment.md).

## What a deploy consists of

```text
images (api, portal) -> Artifact Registry
        |
terraform apply (infrastructure/terraform)
        |  provisions: VPC + connector, Cloud SQL (private IP),
        |  Memorystore, GCS artifact bucket, Artifact Registry,
        |  Secret Manager secrets, Cloud Run services (managed TLS),
        |  uptime checks
        v
smoke test (infrastructure/smoke/smoke.sh): health, login, catalog
```

The `deploy` workflow (`.github/workflows/deploy.yml`) runs the whole
sequence on manual dispatch. PRs never deploy; they gate on
`terraform fmt/validate` (backend-ci), the security-header check and
bundle budgets (frontend-ci), and the dependency scans.

## One-time setup

1. **Project + billing.** A GCP project with Cloud Run, Cloud SQL,
   Memorystore, Secret Manager, Artifact Registry, VPC Access, and
   Cloud Monitoring APIs enabled.
2. **Deploy identity.** A service account with enough scope to create
   the resources (Project Editor for the demo environment; for
   production, split per-resource roles) and a Workload Identity
   Federation provider for GitHub Actions:
   [auth docs](https://github.com/google-github-actions/auth#workload-identity-federation-through-a-service-account).
3. **Repository secrets** (Settings -> Secrets and variables ->
   Actions):

   | Secret | Used as | Notes |
   |---|---|---|
   | `GCP_PROJECT_ID` | `TF_VAR_project_id` | target project |
   | `GCP_WIF_PROVIDER` | WIF auth | `projects/.../providers/...` |
   | `GCP_SERVICE_ACCOUNT` | WIF auth | deploy SA email |
   | `TF_AUTH_JWT_SECRET` | `TF_VAR_auth_jwt_secret` | >= 32 bytes, high entropy (`openssl rand -base64 48`) |
   | `TF_DOWNLOAD_SIGNING_KEY` | `TF_VAR_artifact_download_proxy_signing_key` | any high-entropy HMAC key |
   | `TF_DB_PASSWORD` | `TF_VAR_db_password` | optional — terraform generates one when absent |

4. **First apply** creates the stack (Cloud SQL provisioning takes
   ~10 minutes). Subsequent applies are incremental.

## Deploying

Actions tab -> **deploy** -> Run workflow. The run builds both images
from the commit's trees, pushes, applies terraform, and runs the
smoke test. Outputs land in the run log:

- `api_url` — the api service's managed-TLS URL
- `portal_url` — the portal's URL (the demo entry point)
- `artifact_registry` — image coordinates for rollback pinning

### Deploying by hand

```bash
gcloud auth application-default login
cd infrastructure/terraform
export TF_VAR_project_id=YOUR_PROJECT
export TF_VAR_api_image="us-central1-docker.pkg.dev/YOUR_PROJECT/shipperdog/api:SHA"
export TF_VAR_portal_image="us-central1-docker.pkg.dev/YOUR_PROJECT/shipperdog/portal:SHA"
export TF_VAR_auth_jwt_secret="$(openssl rand -base64 48)"
export TF_VAR_artifact_download_proxy_signing_key="$(openssl rand -base64 32)"
docker build -t "$TF_VAR_api_image" ../../backend && docker push "$TF_VAR_api_image"
docker build -t "$TF_VAR_portal_image" ../../portal && docker push "$TF_VAR_portal_image"
terraform init && terraform apply
```

Then the smoke test:

```bash
API_BASE_URL="$(terraform output -raw api_url)" \
PORTAL_BASE_URL="$(terraform output -raw portal_url)" \
../../infrastructure/smoke/smoke.sh
```

## Demo data and production data

The M1 demo environment runs with `seed_demo_data = true`: the api
starts with the `dev` profile, whose only effect is loading the
demo-org seed (`docs/database-migrations.md`) — org, tenants, the
Banking application, a working-set version, and the demo tester
(`tester@shipperdog.local` / `shipperdog-dev-tester`) with an
application-wide grant. The smoke test authenticates as that tester
and asserts a non-empty catalog: three legs that prove the identity
tier, the wiring, and the authorization engine end to end.

A production deployment sets `seed_demo_data = false` and provisions
its own organization/testers through the API. The platform has no
production first-admin bootstrap yet (invitation-based registration
needs an admin to start the chain) — the org-bootstrap story is an
M2 item recorded in ADR-0005; until then a production first admin is
created by operator SQL against the same schema the seed uses.

## Configuration surface (Cloud Run env)

| Variable | Cloud Run value | Compose default |
|---|---|---|
| `DATABASE_URL` | private-IP Cloud SQL, `sslmode=require` | `postgres:5432` |
| `REDIS_HOST` | Memorystore private IP | `redis` |
| `AUTH_JWT_SECRET` | Secret Manager | local-only default |
| `ARTIFACT_STORAGE_BUCKET` | terraform-created bucket | `shipperdog-artifacts-local` |
| `ARTIFACT_STORAGE_EMULATOR_HOST` | blank (real GCS, ADC) | `http://localhost:4443` |
| `MANAGEMENT_SERVER_PORT` | `8080` (health,info only) | `8081` (separate, loopback) |
| `FORWARD_HEADERS_STRATEGY` | `framework` | `framework` |
| `RATE_LIMIT_LOGIN_CAPACITY` / `_REFILL` | set your posture | generous local values |
| `RATE_LIMIT_DOWNLOADS_CAPACITY` / `_REFILL` | set your posture | generous local values |
| `RATE_LIMIT_WEBHOOKS_CAPACITY` / `_REFILL` | set your posture (burst 60/min default) | same |
| `RATE_LIMIT_TRUSTED_PROXY_COUNT` | `1` (Cloud Run's proxy) | `1` (nginx) |

### Inbound webhook receivers (S12-03/S12-04)

All five receivers ship OFF (each answers its uniform rejection until
its credential is set); enable only what you use. Secrets belong in
Secret Manager mirrored into Cloud Run env — never in the repository
(it is public). Full contract: [webhooks.md](webhooks.md).

| Variable | Used by | Notes |
|---|---|---|
| `WEBHOOKS_GITHUB_SECRETS` | `/webhooks/github` | comma-separated HMAC secrets; rotation is overlap (add new, remove old) |
| `WEBHOOKS_JENKINS_SECRETS` | `/webhooks/jenkins` | same HMAC family; the Jenkinsfile snippet computes `X-Shipperdog-Signature` ([ci-jenkins.md](ci-jenkins.md)) |
| `WEBHOOKS_GITLAB_SECRETS` | `/webhooks/gitlab` | the GitLab webhook token compared constant-time; TLS mandatory (GitLab signs nothing) |
| `WEBHOOKS_APPSTORE_BUNDLE_IDS` | `/webhooks/appstore` | the bundle-id allowlist (Apple's JWS proves authenticity; this is authorization) |
| `WEBHOOKS_APPSTORE_TRUSTED_ROOTS` | `/webhooks/appstore` | Apple root certs (default: the bundled public Apple Root CA G3) |
| `WEBHOOKS_GOOGLE_PLAY_AUDIENCE` | `/webhooks/google-play` | the push subscription's OIDC audience — this endpoint's URL; empty keeps the receiver off |
| `WEBHOOKS_GOOGLE_PLAY_TRUSTED_EMAILS` | `/webhooks/google-play` | optional service-account allowlist |
| `WEBHOOKS_GOOGLE_PLAY_JWKS_URI` | `/webhooks/google-play` | Google's public JWKS by default; pin a mirror if your egress policy needs it |
| `WEBHOOKS_MAX_TIMESTAMP_SKEW` | all S12-04 receivers | replay window (default PT5M) |
| `WEBHOOKS_MAX_BODY_BYTES` | all receivers | body ceiling (default 25 MB; enforced on the declared length before reading) |

Rate limiter semantics and the X-Forwarded-For trust arithmetic:
[portal.md](portal.md#hardening-and-deployment-s8-05).

### Secret store and adapter sandbox (S13-01)

Credential material for store accounts (Apple, Google Play) lives
behind the secret-store port (ADR-0008); the platform holds only
references. Wiring ([distribution.md](distribution.md)):

| Variable | Surface | Notes |
|---|---|---|
| `SECRETS_PROVIDER` | all credential surfaces | `envelope` (default) or `gcp-secret-manager` |
| `SECRETS_ENVELOPE_KEK` | the envelope store | Base64 of 32 bytes. MUST be set in production when running the envelope store: blank mints an EPHEMERAL process-local key (a restart stops every reference resolving). Never commit it |
| `SECRETS_GCP_PROJECT_ID` | the GCP store | the project owning the secrets (with ADC configured) |
| `SECRETS_GCP_ENDPOINT` | the GCP store | override (tests/mirrors); default secretmanager.googleapis.com |
| `DISTRIBUTION_ADAPTERS__<CHANNEL>__TIMEOUT` / `__RETRY_ATTEMPTS` / `__RETRY_BACKOFF` | the adapter sandbox | per-channel wall and retry posture (defaults 30 s / 2 / 1 s); e.g. `DISTRIBUTION_ADAPTERS__TESTFLIGHT__TIMEOUT` |
| `DISTRIBUTION_APPLE__BASE_URL` | the App Store Connect client (S13-02) | default `https://api.appstoreconnect.apple.com`; override only for mirrors/test stacks — the connector dials it under dispatches, group syncs, and record refreshes |
| `DISTRIBUTION_GOOGLE-PLAY__BASE_URL` | the Google Play Developer API client (S13-04) | default `https://androidpublisher.googleapis.com/androidpublisher/v3/applications`; override only for mirrors/test stacks. The OAuth2 token endpoint is NOT a knob: it rides the service-account JSON document itself (`token_uri`) |
| `DISTRIBUTION_GOOGLE-PLAY__READ_TIMEOUT` | the Google Play client (S13-04) | default `PT60S`; the AAB media upload rides it, so size the timeout to the artifact sizes a dispatch carries (the sandbox's own wall applies above it: `DISTRIBUTION_ADAPTERS__PLAY__TIMEOUT`) |
| `DISTRIBUTION_RECONCILIATION__ENABLED` | the reconciliation pass (S13-02) | `false` (default — a self-hosted stack never wakes up dialing Apple) or `true` (production with store connectors in service: the pass refreshes PENDING/DISTRIBUTING store records through the adapters' status answers) |
| `DISTRIBUTION_RECONCILIATION__FIXED_DELAY` | the reconciliation pass | cadence between passes; default `PT15M` |
| `DISTRIBUTION_STAGED_ROLLOUT__ADVANCEMENT__ENABLED` | the staged tenant rollout advancement pass (S13-06) | `false` (default — an unattended pass that widens production rollouts is a deployment's own decision) or `true` (the AUTOMATIC mode's unattended cadence: every RUNNING AUTOMATIC plan gets one policy-gated advancement attempt per pass; the ADVANCE action on the staged rollout route serves the same engine everywhere, and CHECKPOINT plans are never advanced unattended) |
| `DISTRIBUTION_STAGED_ROLLOUT__ADVANCEMENT__FIXED_DELAY` | the staged rollout advancement pass | cadence between passes; default `PT5M` |

The App Store production channel's phased release (S13-03) is an
ENVIRONMENT CONFIGURATION key, not a process env var: set
`phased-release` to `"true"` in the production environment's
configuration snapshot (`PUT /api/v1/environments/{id}/config`) and
every release the environment dispatches freezes that decision into
its manifest at APPROVED and starts Apple's 7-day curve at
submission; absent or `"false"` submits a manual release. The Google
Play channel's ring-to-track mapping override (S13-04) is the same
kind of key: `play-track` names the testing track explicitly
(`internal` or a closed track name); absent, the release's ring code
maps through the documented default (Engineering/QA to `internal`,
Tenant Pilot/UAT to `alpha`), and production is refused — S13-05 owns
it. Telemetry note for dashboards:
`shipperdog.adapter.ratelimit` is tagged `provider` since #63 (Apple
throttles the API key, which both Apple channels usually share; the
Play connector answers `google-play`, the project's quota grain), and
`shipperdog.adapter.call.duration` gained an `operation` tag
(distribute, status, phased-release-view, phased-release-control).

## Rollback

Cloud Run revisions keep the last images: `gcloud run services
update-traffic shipperdog-api --to-revisions REVISION=100` (same for
`shipperdog-portal`). Schema rollback is NOT supported by design
(ADR-0001: expand-only forward migrations) — a bad migration is
forward-fixed. Terraform state changes roll back with
`terraform apply` against the previous commit.

## Observability

- **Uptime checks** (Cloud Monitoring, provisioned): api
  `/actuator/health` + portal `/`, every minute, TLS-validated. Wire
  alert notification channels to your project's preferred sinks.
- **Cloud Run dashboards**: request rate, latency, and instance
  counts per service out of the box.
- **Compose Grafana** (S1-07 + S8-05): availability and error-rate
  panels against the 99.9% SLO, rate-limit rejections per rule.

## Dwara (target architecture)

Production traffic is intended to front the api through Dwara (the
gateway): it terminates client auth policies, rate limits at the
edge, and forwards with `X-Forwarded-*`. The app is ready for it —
forwarded-header strategy is env-configured, the rate limiter's
trust depth is a variable, and no origin assumptions are baked into
clients (same-origin `/api` everywhere). Integration is a separate
infra project, deliberately out of this story's scope.
