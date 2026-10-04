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
| frontend | frontend/ | placeholder until S8-01 |
| cli | cli/ | placeholder until S5-06 |
| infrastructure | infrastructure/ | local docker compose stack |
| docs | docs/ | developer docs |
| workflows | .github/workflows/ | GitHub Actions CI |

## Reviewers / CODEOWNERS

- To be defined as the team grows; first code lands in issue #1.

## Out of Scope Until Later Issues

- Messaging (Kafka/Pub/Sub) — M2, issue for S12-01.
- Business logic of any module — issues #2 onward.
- Production deployment/IaC.
