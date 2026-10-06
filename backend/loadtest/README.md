# S7-03 authorization decision load test

`authorization-decision.js` is the issue #32 (S7-03) acceptance
criterion 5 load test: a reproducible mixed load against the
first-party decision API (`GET /api/v1/authorization/decisions/
download` and `.../eligibility`) at ~50 rps with a warm decision-input
cache, asserting the PRD 42 authorization-decision SLO (p95 < 100 ms)
per endpoint leg.

## What it exercises

- The **granted leg**: the persona holding an EXPLICIT_VERSION grant
  walks the full PRD 11 chain — user, organization, tenant (cached
  membership flag), application, version + the cached grant-set
  entitlement — and stops at the artifact grain's concealed miss (the
  world carries no artifact, so the issuance-inputs probe is the one
  step not measured here; the integration suite covers it
  functionally).
- The **denied leg**: ungranted personas (the entitlement miss) and an
  unknown-version probe (the concealed miss) — denials must be as
  fast as allows.
- The **eligibility listing**: the effective set over cached inputs.

## Running

The target is the local compose stack (`docker compose up` from the
repository root — postgres, redis, GCS emulator, and the API built
from the current tree: `docker compose build api && docker compose up
-d api`). The dev-seed platform administrator provisions the world
through the API at setup; nothing is pre-seeded by hand.

Run k6 via the official image (no local install needed):

```sh
docker run --rm --network host -i \
  -v "$PWD/backend/loadtest:/loadtest:ro" \
  -e BASE_URL=http://localhost:8080 \
  grafana/k6:1.4.0 run - < /loadtest/authorization-decision.js \
  2>&1 | tee /tmp/k6-run.txt
```

Or with a local k6:

```sh
cd backend/loadtest && k6 run authorization-decision.js
```

Knobs (env): `RPS` (default 50), `DURATION` (default 60s), `TESTERS`
(default 4), `BASE_URL`. The threshold block fails the run if any
leg's p95 crosses 100 ms or any unexpected status appears.

## Degraded-mode run (AC3)

With Redis stopped the same script measures the fail-closed fallback
(correct, slower — the SLO is expected to hold only with a healthy
cache):

```sh
docker compose stop redis
# ... run the script ...
docker compose start redis
```

Watch `shipperdog_authorization_decision_cache_total{outcome="degraded"}`
on the management port (`127.0.0.1:8081/actuator/prometheus`) to see
the fallback, and `outcome="hit"` during the healthy run.

## Results

Committed result transcripts live beside this README
(`results-*.txt`): the healthy-cache run and the degraded-mode run
from the story's verification, including the per-leg p95 lines.
