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
Current call sites: organization creation (`OrganizationService`) and
the RBAC enforcement point (`RbacPermissionEvaluator` tags the guarded
organization/tenant target). Later stories (E3+) call the same helper
as entities appear.

## Metrics and the `shipperdog_*` namespace

Names are declared once in `MetricNames` (dotted Micrometer form; the
Prometheus registry renders underscores + `_seconds` units):

| Prometheus name | Recorded by | Story |
|---|---|---|
| `shipperdog_authorization_decision_duration_seconds` | RBAC enforcement point, tag `outcome=allow\|deny` | S1-07 (SLO: <100 ms) |
| `shipperdog_artifact_upload_duration_seconds` | reserved | E5 |
| `shipperdog_artifact_validation_duration_seconds` | reserved | E5 |
| `shipperdog_release_duration_seconds` | reserved | E9 |
| `shipperdog_distribution_duration_seconds` | reserved | E13 |
| `shipperdog_download_count` | reserved | E5/E9 |
| `shipperdog_installation_count` | reserved | E11 |
| `shipperdog_promotion_duration_seconds` | reserved | E10 |
| `shipperdog_approval_duration_seconds` | reserved | E10 |

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
