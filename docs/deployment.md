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
| `RATE_LIMIT_TRUSTED_PROXY_COUNT` | `1` (Cloud Run's proxy) | `1` (nginx) |

Rate limiter semantics and the X-Forwarded-For trust arithmetic:
[portal.md](portal.md#hardening-and-deployment-s8-05).

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
