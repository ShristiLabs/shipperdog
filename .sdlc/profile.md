# Project Profile — shipperdog

contracts: v1

## Overview

ShipperDog — enterprise mobile-app beta-distribution and release platform.
Modular-monolith backend inside a monorepo. Greenfield as of issue #1.

## Stack

- Language: Java 25. Toolchain: ALWAYS set
  `JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home`
  for Maven builds (system default `java` is 23 — builds fail or warn without it).
- Framework: Spring Boot, version pinned in `backend/pom.xml` (latest stable
  compatible with Java 25, verified on Maven Central at scaffold time).
- Build: Maven 3.9.x. Single module at `backend/` — explicitly NOT a Maven
  multi-module build; modularity enforced by ArchUnit tests instead.
- Architecture: one application package, one subpackage per bounded context
  (PRD §22): identity, organization, tenant, application, version, artifact,
  tester, authorization, release, workflow, environment, gate, distribution,
  feedback, device, analytics, integration, notification, audit.
  Cross-module imports of `.internal.` subpackages are forbidden (ArchUnit).
- Formatter: Spotless with pinned formatter plugin and pinned format version;
  `spotless:check` runs in CI.
- Data: PostgreSQL; cache: Redis; object storage: GCS (`fsouza/fake-gcs-server`
  emulator locally). Flyway migrations expected from issue #2 onward.
- Local env: `docker compose up` runs PostgreSQL, Redis, GCS emulator, and the
  API; `GET /actuator/health` must return UP.

## Commands (from `backend/` unless noted)

- fmt-cmd: `mvn spotless:apply`
- fmt-check: `mvn spotless:check` (also the lint gate; no separate linter yet)
- build-cmd: `mvn -DskipTests package`
- test-cmd: `mvn test`
- full-verify: `mvn verify` (fmt check + build + tests)

All Maven commands require JAVA_HOME set to the JDK 25 path above.

Frontend (from `frontend/`, since S2-04 — see [docs/frontend.md]):

- fmt-check/lint: `npm run lint` (eslint; no auto-format step yet)
- build-cmd: `npm run build` (strict tsc typecheck + Vite bundle)
- test-cmd: `npm test` (Vitest, happy-dom)
- Node >= 22.12; all dependencies exact-pinned with a committed lockfile.

Tester portal (from `portal/`, since S8-01 — see [docs/portal.md]):

- fmt-check/lint: `npm run lint` (eslint; no auto-format step)
- build-cmd: `npm run build` (strict tsc typecheck + Vite bundle)
- test-cmd: `npm test` (Vitest, happy-dom)
- Node >= 22.12; exact-pinned deps with committed lockfile; separate
  app/bundle from the console (no cross-imports), dev server :5174.

CLI (from `cli/`, since S5-06 — see [docs/cli.md]):

- fmt-cmd: `gofmt -w .` (checked via the lint gate)
- lint-cmd: `golangci-lint run` (config `cli/.golangci.yml`, v2.10.x)
- build-cmd: `go build ./...`
- test-cmd: `go test ./...` (includes the OpenAPI endpoint contract
  test against docs/openapi/baseline.yaml)
- Go 1.26; two direct deps (cobra, x/term) exact-pinned via go.mod +
  committed go.sum.

## Conventions

- Conventional Commits (feat/fix/chore/docs/test/ci).
- Configuration from environment variables only; `application.yml` reads
  `${VARS}` with local defaults supplied via docker compose `.env`. No secrets
  in committed files.
- No emoji, no TODO stubs, no commented-out code.
- CI: GitHub Actions; build + tests + format check on every PR; merge blocked
  on failure.

## Component Map

| Component | Path | Notes |
|---|---|---|
| backend | backend/ | Spring Boot application, single Maven module |
| frontend | frontend/ | Admin console: React 19 + TS strict + MUI 9 + React Query 5 + Router 7 on Vite 7 (S2-04) |
| portal | portal/ | Tester portal: React 19 + TS strict + MUI 9 + React Query 5 + Router 7 on Vite 7, separate app on :5174 (S8-01) |
| cli | cli/ | Go 1.26 developer CLI: login, artifact upload/list/view/revalidate, version list (S5-06) |
| infrastructure | infrastructure/ | local docker compose stack (API, console, portal, data, observability) + terraform Cloud Run deployment, smoke + security-header checks (S8-05) |
| docs | docs/ | developer docs |
| workflows | .github/workflows/ | GitHub Actions CI (backend-ci + frontend-ci incl. portal job + cli-ci) |

## Reviewers / CODEOWNERS

- To be defined as the team grows; first code lands in issue #1.

## Out of Scope Until Later Issues

- Messaging (Kafka/Pub/Sub) — M2, issue for S12-01.
- Business logic of any module — issues #2 onward.
- Production deployment beyond the Cloud Run stack shipped with S8-05
  (issue #39): multi-region, CDN/WAF, and the Dwara gateway front are
  later infra projects (see docs/deployment.md).
