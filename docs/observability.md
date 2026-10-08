# Observability (S1-07)

The observability baseline (issue #7, PRD section 28): traces, metrics,
and logs correlated by correlation id and entity ids, so the latency
SLOs (PRD section 42) are measurable the moment features land. OSS
end-to-end: OpenTelemetry traces via Micrometer, Prometheus metrics,
Grafana dashboards, structured JSON logs.

## What is wired

| Signal | Mechanism | Where it shows up |
|---|---|---|
| Traces | Micrometer observations bridged onto the OpenTelemetry SDK (no javaagent) | Every HTTP request creates a `SERVER` span (`http.server.requests`) plus security child spans (`authenticate`, `authorize method`, `secured request`) |
| Metrics | Actuator + Micrometer, Prometheus registry | `/actuator/prometheus` on the internal management port |
| Logs | Logback with correlation pattern + optional structured JSON | Every line carries `[correlationId]` and, when tracing is active, `[app,traceId,spanId]` |
| Correlation | `CorrelationIdFilter` (S1-06) feeds spans and audit | Same id in span attribute, log lines, and `audit_events.correlation_id` |

### The Boot 4 dependency trap

`spring-boot-micrometer-tracing-opentelemetry` carries the OTel tracing
auto-configuration but does **not** pull the bridge jar — its
auto-configuration is `ConditionalOnClass(io.micrometer.tracing.otel.
bridge.OtelTracer)` and silently falls back to the noop tracer
(zero spans, no error) without it. `micrometer-tracing-bridge-otel` is
therefore declared explicitly in `backend/pom.xml`. If spans ever stop
appearing, check for that jar first.

## Correlation model

One id, three signals, no mapping (the #6 follow-up):

- The request filter issues/accepts `X-Correlation-Id` (8-64 chars,
  URL-safe alphabet) and puts it in the MDC (`correlationId`).
- `TracingConfig` registers an observation filter that copies the MDC
  value onto every observation as a high-cardinality key value — which
  Micrometer Tracing renders as the span attribute `correlation_id`
  on every span of the request (server and children). High-cardinality
  key values never become metric tags, so per-request ids cannot
  inflate metric cardinality.
- Audit enrichment (S1-06) stamps the same id on the persisted rows.
- With tracing active, Spring Boot's log correlation adds
  `[application,traceId,spanId]` to log lines automatically; our level
  pattern adds `[correlationId]`. Example:

```text
INFO [corr-trace-logs-audit-0001] ... [7bb23273d4170ab6cffd47eaa2053492-98560c13a7a764a0] c.s.a.a.i.LoggingAuditEventPublisher : audit event=LOGIN_FAILURE ...
```

## Entity attributes (PRD section 28)

Business code stamps the entity ids it operates on via
`TraceAttributes` (`com.shipperdog.api.config`): `organization`,
`tenant`, `application`, `version`, `artifact`, `release` — UUID
parameters only, never names or PII. The ids land as span attributes
(`organization_id`, `tenant_id`, ...) on the request's active child
span (Spring Security wraps dispatch in a `secured request`
observation), so any span search by entity id finds the request.
Current call sites: organization creation (`OrganizationService`),
the RBAC enforcement point (`RbacPermissionEvaluator` tags the guarded
organization/tenant/application/version target), and the artifact
upload paths (`ArtifactService` tags the application, version, and
pre-allocated artifact of every upload, session grant, and finalize —
S5-01). Later stories (E3+) call the same helper
as entities appear.

## Metrics and the `shipperdog_*` namespace

Names are declared once in `MetricNames` (dotted Micrometer form; the
Prometheus registry renders underscores + `_seconds` units):

| Prometheus name | Recorded by | Story |
|---|---|---|
| `shipperdog_authorization_decision_duration_seconds` | RBAC enforcement point (tag `outcome=allow\|deny`); since S7-01 also the layered authorization engine's decisions, distinguished by tag `engine=layered`; since S7-03 the meter carries a percentile histogram, so the p95 is computable in Prometheus | S1-07; layered leg S7-01 (issue #30) (SLO: <100 ms); histogram S7-03 (issue #32) |
| `shipperdog_authorization_decision_cache_total` | The decision-input cache (Redis) resolver, tags `family=grants\|membership\|visibility\|tenants-of-tester` and `outcome=hit\|miss\|degraded\|bypass\|invalidate` — `degraded` counts Redis failures that fell through to direct evaluation (fail-closed; sustained growth is the operator's slow-decision signal), `bypass` the disabled cache, `invalidate` write-through key drops | S7-03 (issue #32) |
| `shipperdog_artifact_upload_duration_seconds` | Artifact upload service, tags `path=multipart\|resumable` and `outcome=success\|failure` (the failure leg records a store or insert failure after streaming began, not a rejected request) | S5-01 (issue #20) |
| `shipperdog_artifact_extraction_duration_seconds` | Artifact metadata extraction job, tags `platform=IOS\|ANDROID` and `outcome=success\|failed-parse\|failed-infra` (the malformed-binary vs infra split; the PRD 42 "artifact metadata processing < 30 sec" SLO observes this meter) | S5-02 (issue #21) |
| `shipperdog_artifact_validation_duration_seconds` | Artifact validation pipeline (one timer per completed attempt), tags `platform=IOS\|ANDROID` and `outcome=success\|failed\|blocked` (the stage-failure vs infra-block split; the PRD 42 "artifact metadata processing < 30 sec" SLO observes the extraction meter plus this one end to end) | S5-05 (issue #24) |
| `shipperdog_release_duration_seconds` | reserved | E9 |
| `shipperdog_distribution_duration_seconds` | reserved | E13 |
| `shipperdog_download_url_generation_duration_seconds` | Download issuance service, tags `outcome=issued\|denied`: the ALLOW leg that minted a URL (decision, scoped read, routing, minting) vs every concealed denial, recorded for the ratio — the PRD 42 "download URL generation < 500 ms" SLO observes the `issued` leg | S8-04 (issue #38) |
| `shipperdog_download_count` | Download issuance service, tag `platform=IOS\|ANDROID`, one increment per ALLOWED issuance — the PRD 28 reserved name wired live, reconcilable against the per-artifact `download_count` column (V23) and the `DOWNLOAD_ISSUED` audit trail | S8-04 (issue #38); reserved since E5/E9 |
| `shipperdog_ratelimit_rejected_total` | The in-app rate limiter (S8-05), tag `rule=login\|downloads`, one increment per 429 — on the login rule, the brute-force early warning beside the identity lockout | S8-05 (issue #39) |
| `shipperdog_installation_count` | reserved | E11 |
| `shipperdog_promotion_duration_seconds` | reserved | E10 |
| `shipperdog_approval_duration_seconds` | Wired live since S10-02 (issue #41): a timer of every approval round's terminal crossing — the span from a round's instantiation to SATISFIED or REJECTED, tag `outcome=satisfied\|rejected`. Rework is a NEW round, so each round times its own governance moment (rework cycles read as multiple recordings for one release) | E10 (S10-02, issue #41) |

JVM and HTTP metrics come from the actuator defaults
(`jvm_memory_used_bytes`, `http_server_requests_seconds_*`). The HTTP
server timer publishes histogram buckets
(`management.metrics.distribution.percentiles-histogram."http.
server.requests"` defaults to true) so `histogram_quantile` p95 works.

## Environment variables

| Variable | Default | Effect |
|---|---|---|
| `MANAGEMENT_ENDPOINTS_EXPOSE` | `health,info` | Which actuator endpoints exist. Add `prometheus` to scrape |
| `MANAGEMENT_SERVER_PORT` | (unset = same port) | Moves all management endpoints to an internal port |
| `TRACING_SAMPLING_PROBABILITY` | `1.0` | Head sampling; dial down as volume grows |
| `MANAGEMENT_METRICS_HTTP_HISTOGRAM` | `true` | Histogram buckets for `http.server.requests` |
| `LOGGING_STRUCTURED_FORMAT_CONSOLE` | (plain text) | `logstash` renders JSON logs with correlation/trace ids |
| `OTEL_SERVICE_NAME` / `OTEL_RESOURCE_ATTRIBUTES` | `shipperdog-api` | OTel resource attributes (Boot maps OTEL_* env vars natively) |
| `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` | (unset = no export) | Enables OTLP span export — vendor-neutral, direct to a collector |

## Scrape security posture

`/actuator/health` and `/actuator/info` are the only public actuator
endpoints (unchanged). `SecurityConfig` also permits
`/actuator/prometheus` unauthenticated **by design**: the endpoint only
exists where an operator puts it. The local stack moves all management
endpoints to `MANAGEMENT_SERVER_PORT=8081`, published loopback-only
(`127.0.0.1:8081` on the host, in-network for the Prometheus service),
never on the public API port. An operator who exposes `prometheus` on
the API port instead owns protecting it upstream.

## Local stack

`docker compose up` (from `infrastructure/`) adds:

- `prometheus` — scrapes `api:8081/actuator/prometheus` every 15 s; UI
  at `http://localhost:9090` (loopback only).
- `grafana` — provisioned datasource + dashboard, no manual import; UI
  at `http://localhost:3000` (loopback only; admin/admin by default,
  anonymous viewers allowed; override `GRAFANA_ADMIN_PASSWORD`).

The provisioned **ShipperDog API** dashboard covers the issue's three
required panels — request rate by route, 5xx error rate, p95 latency —
plus authorization decision rate/p95 (the <100 ms SLO signal) and JVM
heap. PromQL uses `$__rate_interval` windows over
`http_server_requests_seconds_*` and
`shipperdog_authorization_decision_duration_seconds_*`.

### Availability panels (S8-05, issue #39)

The PRD 42 availability SLO (99.9% for the API and the tester portal)
is now on the dashboard: an **availability stat** (`1 - 5xx fraction`
over the trailing hour, thresholds at the 99.9% SLO), an **error-rate
share** timeseries (the 0.1% budget view), and a **rate-limit
rejections** panel per rule (the login rule's sustained growth is the
brute-force early warning beside the identity lockout). The deployed
environment's external uptime probing is Cloud Monitoring uptime
checks provisioned with the terraform stack
([deployment.md](deployment.md)).

### The decision SLO under load (S7-03, issue #32)

`backend/loadtest/` holds the reproducible k6 mixed-load test for the
authorization decision SLO (see its README): ~50 rps across the
decision API's granted/denied/eligibility legs with a warm
decision-input cache, asserting p95 < 100 ms per leg. The committed
result transcripts measured p95 ~6 ms warm-cache and ~7 ms with Redis
stopped (the fail-closed breaker at work) — both an order of
magnitude inside the SLO; the pre-breaker degraded baseline (3.2 s
p95) is the finding that motivated the breaker.

With `LOGGING_STRUCTURED_FORMAT_CONSOLE=logstash` (set in compose) the
API writes structured JSON logs: one object per line with `correlationId`
MDC, trace/span ids, logger, level, and message.

## Tests

- `TracingIntegrationTest` — real finished spans captured via a test
  `SpanProcessor` bean: span per request with `correlation_id` +
  `service.name`, the same id in logs and committed audit rows, entity
  attributes on the request's trace.
- `PrometheusMetricsIntegrationTest` — scrape output contains JVM,
  HTTP, and `shipperdog_*` series; timer tagged by outcome; health
  stays public and the business API stays authenticated.
- `TraceAttributesTest` — unit: ids land as high-cardinality key
  values; no-ops outside an observation.

## Deferred

- Alerting rules and SLO burn-rate alerts (explicitly out of issue
  scope; land with production hardening).
- OTel collector in compose (direct OTLP export is already wired via
  env; add the collector when a backend exists).
- `event_type`/`correlation_id` indexes on `audit_events` (volume-driven).
