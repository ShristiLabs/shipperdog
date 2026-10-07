# ADR-0005: M1 deploys to Cloud Run over a private VPC data tier

Date: 2026-10-08
Status: Accepted (issue #39, S8-05)

## Context

S8-05 is M1's shipping story: a repeatable, hardened deployment of
the api and the tester portal — the vehicle the milestone's exit
criteria ("demonstrable in a deployed environment") ride on. PRD 45
names "GKE / Cloud Run" as the deployment target and P8 demands
enterprise-grade security at every layer. The story's scope pins
Cloud Run for both services with terraform in `infrastructure/`,
managed TLS, smoke tests, and uptime probing; Dwara (the gateway) is
explicitly out — a separate infra project documented as the target
architecture.

The platform's runtime needs are fixed by earlier stories: PostgreSQL
(Flyway system of record), Redis (the degradable decision-input
cache, S7-03), GCS with application default credentials (ADR-0003),
secrets through the environment only (no secrets in committed files).

## Decision

**Cloud Run v2 services for api and portal, a private VPC data tier,
and one nginx image that serves the portal everywhere.**

1. **Cloud Run, not GKE.** Two services, managed TLS on run.app
   domains, scale-to-configured-bounds, startup/liveness probes on
   `/actuator/health`. Nothing in M1 needs a cluster: noDaemonSets,
   no node pools, no cluster upgrade story — and every month M1 ships
   without Kubernetes is operational risk not carried. GKE remains
   the M2+ option if workloads appear that need it (the PRD's
   either-or is honored, not foreclosed).
2. **Private data tier behind a Serverless VPC connector.** Cloud SQL
   (PostgreSQL 17, `ipv4_enabled = false`) and Memorystore
   (`PRIVATE_SERVICE_ACCESS`) live on private IPs reachable only
   through the connector; the app keeps its plain JDBC and Redis
   clients — no Cloud SQL socket-factory dependency baked into the
   image, so the same container runs unchanged against compose.
3. **Management endpoints ride the serving port on Cloud Run.** The
   compose stack keeps the separate loopback management port (S1-07:
   `/actuator/*` never public). Cloud Run ingress serves exactly one
   port, so the deployment sets `MANAGEMENT_SERVER_PORT=8080` with
   `MANAGEMENT_ENDPOINTS_EXPOSE=health,info` — health is the
   platform's own liveness surface (`show-details` stays `never`),
   and `prometheus` is NOT exposed publicly: production metric
   ingestion is a Cloud Monitoring / managed-Prometheus decision that
   belongs to the gateway era; the compose Grafana stack remains the
   M1 observability vehicle.
4. **The portal image is the hardened nginx, parameterized by
   `API_UPSTREAM`.** The same container serves compose (upstream
   `api:8080`) and Cloud Run (the api service's URL) through the
   official image's template mechanism — the CI security-header
   check grades exactly the artifact that deploys.
5. **Secrets via Secret Manager, injected as env by Cloud Run.** The
   JWT signing key, download-proxy key, and database password are
   terraform-managed secrets referenced with `secret_key_ref`; the
   app's environment-only configuration rule is unchanged.
6. **Uptime probing by Cloud Monitoring** on both public origins
   (every minute, TLS-validated), provisioned with the stack; the
   Grafana availability dashboard (S8-05's panels on the S1-07
   stack) covers the compose/local view.
7. **Forwarded headers**: `FORWARD_HEADERS_STRATEGY=framework`
   (Google's documented setting for Cloud Run); the in-app rate
   limiter resolves its client identity from the raw
   `X-Forwarded-For` chain itself, with its own trust depth — see
   `docs/portal.md` (S8-05 section) for the interplay.

## Consequences

- Deploys are `terraform apply` + image pushes, orchestrated by the
  manual `deploy` workflow (`.github/workflows/deploy.yml`) and
  verified by the post-deploy smoke test (health, tester login,
  catalog). PRs validate the IaC (`terraform fmt/validate` in CI)
  without GCP credentials.
- The M1 demo environment runs with the demo seed
  (`seed_demo_data = true`): the smoke test's login and catalog legs
  ride the seeded tester. A production-tenant deployment flips the
  flag and provisions real data; the platform has no
  production-first-admin bootstrap yet (dev seed or operator SQL) —
  an M2 story (org bootstrap) that this ADR records honestly rather
  than inventing a backdoor for.
- Single-region, single-project, scale-bounded: the multi-region and
  CDN/WAF questions are explicitly out of the story's scope.
- Cloud Run's fronting proxy appends the client address to
  `X-Forwarded-For`; the rate limiter's default trust depth (one
  proxy) matches, and Dwara will slot in as a future first hop whose
  depth the same variable (`RATE_LIMIT_TRUSTED_PROXY_COUNT`)
  configures.
