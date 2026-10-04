# ShipperDog

ShipperDog is an enterprise platform for mobile-app beta distribution and
release management: upload builds, route them through workflows and quality
gates, distribute them to testers, and collect feedback.

Status: greenfield scaffold (issue S1-01). The backend skeleton, local
infrastructure, and CI baseline described below are the foundation every
subsequent story builds on.

## Repository layout

| Path | Contents |
|---|---|
| `backend/` | Spring Boot modular-monolith API (Java 25, single Maven module) |
| `frontend/` | Tester and admin web app (placeholder until S8-01) |
| `cli/` | Developer CLI (placeholder until S5-06) |
| `infrastructure/` | Local docker compose stack |
| `docs/` | Developer documentation |
| `.github/workflows/` | GitHub Actions CI |

The backend is a modular monolith: one deployable, with one package per
bounded context (identity, organization, tenant, application, version,
artifact, tester, authorization, release, workflow, environment, gate,
distribution, feedback, device, analytics, integration, notification, audit).
Cross-module imports of `.internal.` packages are rejected by ArchUnit tests
(`backend/src/test/java/com/shipperdog/api/architecture/ModuleBoundaryArchTest.java`).

## Quickstart (docker compose)

Requires Docker (with compose v2).

```bash
docker compose up
```

This starts PostgreSQL 17, Redis 8, a GCS emulator (fsouza/fake-gcs-server),
and the API. Defaults are baked in; to override them, copy `.env.example` to
`.env` and edit.

Verify the API:

```bash
curl http://localhost:8080/actuator/health
# {"status":"UP","groups":["liveness","readiness"]}
```

The dev profile seeds a bootstrap admin (`dev@shipperdog.local` /
`shipperdog-dev-admin`, local only — see `docs/authentication.md`):

```bash
curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"dev@shipperdog.local","password":"shipperdog-dev-admin"}'
```

Tear down (and drop the local database volume):

```bash
docker compose down -v
```

## Building and testing locally

Requires JDK 25 and Maven 3.9+. Note: if your default `java` is older than
25, point `JAVA_HOME` at a JDK 25 install first.

```bash
cd backend
export JAVA_HOME=$(/usr/libexec/java_home -v 25)  # macOS; adjust per platform
mvn spotless:apply   # format
mvn verify           # format check + build + tests
```

`mvn verify` runs the Spotless format check (pinned to palantir-java-format),
compiles, and runs the test suite. The Spring Boot integration tests use
Testcontainers, so Docker must be running.

## Configuration

All runtime configuration comes from environment variables; nothing secret
is stored in committed files. The compose file wires the services together
using these variables (local defaults in `infrastructure/docker-compose.yml`
and `.env.example`):

| Variable | Purpose | Local default |
|---|---|---|
| `DATABASE_URL` | JDBC URL | `jdbc:postgresql://localhost:5432/shipperdog` |
| `DATABASE_USERNAME` / `DATABASE_PASSWORD` | Database credentials | `shipperdog` |
| `SERVER_PORT` | HTTP port | `8080` |
| `AUTH_JWT_SECRET` | HS256 signing key for auth tokens (min 32 bytes; required) | compose-only dev value |
| `AUTH_ACCESS_TOKEN_TTL` | Access-token lifetime | `PT15M` |
| `AUTH_REFRESH_TOKEN_TTL` | Refresh-token lifetime | `P7D` |
| `AUTH_LOCKOUT_THRESHOLD` | Failed logins before lockout | `5` |
| `AUTH_LOCKOUT_DURATION` | Lockout duration | `PT15M` |
| `AUTH_PASSWORD_RESET_TTL` | Password-reset token lifetime | `PT30M` |
| `AUTH_INVITATION_TTL` | Registration invitation lifetime | `P3D` |
| `MANAGEMENT_ENDPOINTS_EXPOSE` | Actuator endpoints exposed | `health,info` |
| `MANAGEMENT_HEALTH_SHOW_DETAILS` | Health detail visibility | `never` |

The API refuses to start without `AUTH_JWT_SECRET`. Production supplies a
high-entropy value from its secret manager; local development uses the
compose default, which is local-only exactly like the database password.

## CI

`.github/workflows/ci.yml` runs `mvn verify` (format check, build, tests)
on every pull request and on pushes to `main`.

Merge blocking: branch protection on `main` — requiring the
`backend-ci / build-and-test` status check and pull requests for all
changes — is the intended policy, but it could not be enabled
programmatically because branch protection requires a public repository or
a paid GitHub plan and this repository is currently private on a free
plan. Until that changes, treat a green `backend-ci` run as a hard review
gate: do not merge a PR whose check has failed or is pending. To enable it
later: Settings -> Branches -> Add branch protection rule for `main`,
require the `backend-ci / build-and-test` status check, require pull
requests.

## Development conventions

- Conventional Commits (`feat:`, `fix:`, `chore:`, `docs:`, `test:`, `ci:`).
- Formatting is enforced by Spotless with a pinned palantir-java-format
  version; run `mvn spotless:apply` before pushing.
- No secrets in committed files; configuration is environment-only.
- Database schema changes arrive with Flyway starting in S1-02; the app
  runs with `ddl-auto: validate` only.
