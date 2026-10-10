# Inbound webhook receivers (webhook reference)

The authenticated inbound receivers (FR-022, PRD 19): five
`POST /webhooks/*` routes, deliberately outside `/api/v1` — each
delivery's own provider authentication IS the request's
authentication, verified by the integration module before any
processing. This page is the reference for all five; the CI-driven
senders are documented per CI system ([ci.md](ci.md) for GitHub
Actions, [ci-jenkins.md](ci-jenkins.md), [ci-gitlab.md](ci-gitlab.md)).

## The receiver family

| Route | Provider | Authentication | On/off switch |
|---|---|---|---|
| `POST /webhooks/github` | GitHub Actions | HMAC-SHA256 over the body (`X-Hub-Signature-256`, `sha256=hex`), constant-time over a rotating secret list | `WEBHOOKS_GITHUB_SECRETS` (empty = off) |
| `POST /webhooks/jenkins` | Jenkins | The receiver-defined contract: HMAC-SHA256 over the body (`X-Shipperdog-Signature`, `sha256=hex`) — Jenkins has no standard webhook signature, so the pipeline computes it (openssl one-liner in [ci-jenkins.md](ci-jenkins.md)) | `WEBHOOKS_JENKINS_SECRETS` (empty = off) |
| `POST /webhooks/gitlab` | GitLab | `X-Gitlab-Token` compared constant-time over a rotating token list (GitLab signs nothing — the transport MUST be TLS) | `WEBHOOKS_GITLAB_SECRETS` (empty = off) |
| `POST /webhooks/appstore` | App Store | Apple's JWS `signedPayload` (ES256, x5c chain to the pinned Apple Root CA G3) + the bundle-id allowlist (authenticity, then authorization) | `WEBHOOKS_APPSTORE_BUNDLE_IDS` (empty = effectively off: everything ignores) |
| `POST /webhooks/google-play` | Google Play RTDN | Pub/Sub push OIDC bearer (RS256 against Google's public JWKS; issuer, audience, expiry, issued-at freshness, optional sender-email allowlist) | `WEBHOOKS_GOOGLE_PLAY_AUDIENCE` (empty = off) |

## The two uniform-rejection families

- **The shared-secret family (GitHub, Jenkins, GitLab)** answers every
  authenticity failure the same **401 `WEBHOOK_SIGNATURE_REJECTED`** —
  absent header, malformed value, wrong key, stale timestamp replay,
  and no secret configured at all are indistinguishable by design.
- **The store family (App Store, Google Play)** answers every
  authenticity failure the same **400 `WEBHOOK_DELIVERY_REJECTED`**
  (per S12-04's acceptance criteria) — invalid or unverifiable
  provider signature, wrong audience, expired/stale token, stale
  provider timestamp: one answer, never a hint which leg refused.

A verified sender's unreadable body answers
**400 `WEBHOOK_PAYLOAD_MALFORMED`** on every receiver (the sender
needs the diagnostic). A delivery beyond the ceiling answers
**413 `WEBHOOK_PAYLOAD_TOO_LARGE`** before its bytes are parsed —
and, since S12-04, before they are even buffered: the bounded-read
filter refuses an over-ceiling declared `Content-Length` without
reading the body.

## The receipt

Every processed delivery answers 200 with what the receiver did:

```json
{"status": "ACCEPTED", "deliveryId": "72d3162e-cc78-11e3-81ab-4c9367dc0958"}
```

- **ACCEPTED** — consumed and acted on (an enrichment, a mapped store
  event);
- **IGNORED** — consumed, deliberately not acted on (an unknown event,
  a non-success build, a foreign bundle, shape drift inside a verified
  payload): providers' contracts are 2xx-or-retry, and a no-op must
  not churn retries;
- **DUPLICATE** — the ledger already processed this delivery id (a
  network replay or the provider's own redelivery): the idempotent
  no-op.

## Replay protection

Two independent layers, applied per provider:

1. **The timestamp window** (`WEBHOOKS_MAX_TIMESTAMP_SKEW`, default
   PT5M): every S12-04 provider stamps its deliveries — Jenkins
   `buildTimestamp`, GitLab pipeline `finished_at`/`created_at`, Apple
   `signedDate` (milliseconds), Google `publishTime` and the JWT's
   `iat`. A stamp outside the window is a replay: the family's uniform
   rejection (401 shared-secret, 400 store). A consumed event shape
   whose stamp cannot be evaluated degrades to not-acted-on (IGNORED),
   never to a silent pass. GitHub sends no timestamp; its replay
   defense is the ledger alone (documented posture: GitHub delivery
   ids are unique per delivery attempt).
2. **The delivery-id ledger** (`inbound_webhook_deliveries`, V41): the
   claim-first unique key on (provider, delivery_id) answers a
   concurrent or replayed copy DUPLICATE without reprocessing, in the
   same transaction as the processing.

The store receivers add a third, domain-grain layer: the normalized
event's own unique key on (provider, provider_event_id) in
`store_status_events` (V42) — a copy that arrives under a fresh
transport id converges on the one row, never re-mapped.

## What the store receivers do (and deliberately do not)

`/webhooks/appstore` and `/webhooks/google-play` map each verified
notification **thin** to one `store_status_events` row: the provider,
its own event id (Apple `notificationUUID`; the push `messageId`), a
stable event-type vocabulary (Apple `notificationType[:subtype]`;
Google `kind[/type]`, e.g. `subscription/2`), and the store-side
identifiers (bundle id; purchase token; subscription id or sku).
This is E13's state-sync foundation: the connectors own what a
notification MEANS for a release — no business interpretation is
persisted at the receiver.

Google Play's RTDN envelopes carry no package name (the subscription's
app is resolved by E13 from the purchase token); Apple's carry a
bundle id, which the allowlist authorizes. Google's `TestNotification`
maps like any other kind — event type `test` — because a configured
subscription's first delivery IS the test.

## Metrics, audit, and rate limits (the S12-04 hardening)

- **Metrics**: `shipperdog_webhooks_deliveries_total`, tagged
  `provider` (github/jenkins/gitlab/appstore/google-play) and
  `outcome` (accepted/ignored/duplicate/rejected/malformed/too_large)
  — one increment per terminal answer, the per-provider
  accepted/rejected ratio and duplicate rate from one meter family.
  Rate-limited requests count under
  `shipperdog_ratelimit_rejected_total{rule="webhooks"}` instead
  (they never reach a receiver).
- **Audit**: one audit event per answered delivery —
  `INBOUND_WEBHOOK_ACCEPTED`/`IGNORED`/`REJECTED`/`DUPLICATE`,
  `ARTIFACT_PROVENANCE_ENRICHED` (the CI receivers' backfills), and
  `STORE_EVENT_MAPPED` (the store receivers' mapping). Never the
  credential, never the raw payload; the delivery id is the join key
  to the provider's own delivery log; audit detail values carry the
  ledger columns' own caps.
- **Rate limits**: `POST /webhooks/**` is throttled per client IP
  (bucket4j, the S8-05 limiter) — burst 60/min by default
  (`RATE_LIMIT_WEBHOOKS_CAPACITY`/`RATE_LIMIT_WEBHOOKS_REFILL`),
  answered 429 `RATE_LIMITED` with `Retry-After` before any signature
  or certificate-chain work.

## Configuration

Environment only; no committed secrets. An unset credential keeps
that receiver off (its uniform rejection for every delivery).

| Property | Env | Default | Meaning |
|---|---|---|---|
| `shipperdog.webhooks.github-secrets` | `WEBHOOKS_GITHUB_SECRETS` | empty | GitHub HMAC secrets (comma-separated list; rotation is overlap) |
| `shipperdog.webhooks.jenkins-secrets` | `WEBHOOKS_JENKINS_SECRETS` | empty | Jenkins HMAC secrets (the snippet's openssl one-liner computes the header) |
| `shipperdog.webhooks.gitlab-secrets` | `WEBHOOKS_GITLAB_SECRETS` | empty | GitLab tokens (TLS mandatory — GitLab signs nothing) |
| `shipperdog.webhooks.appstore-bundle-ids` | `WEBHOOKS_APPSTORE_BUNDLE_IDS` | empty | The App Store authorization allowlist |
| `shipperdog.webhooks.appstore-trusted-roots` | `WEBHOOKS_APPSTORE_TRUSTED_ROOTS` | `classpath:webhooks/apple-root-ca-g3.cer` | Apple root certificates (public roots; fail-closed at startup if they do not parse) |
| `shipperdog.webhooks.google-play-audience` | `WEBHOOKS_GOOGLE_PLAY_AUDIENCE` | empty | The required OIDC audience (this endpoint's URL, as configured in the push subscription) |
| `shipperdog.webhooks.google-play-trusted-emails` | `WEBHOOKS_GOOGLE_PLAY_TRUSTED_EMAILS` | empty | Optional service-account allowlist for the same token |
| `shipperdog.webhooks.google-play-jwks-uri` | `WEBHOOKS_GOOGLE_PLAY_JWKS_URI` | Google's JWKS endpoint | Where Google's public signing keys are fetched (cached 10 min; fail closed) |
| `shipperdog.webhooks.max-timestamp-skew` | `WEBHOOKS_MAX_TIMESTAMP_SKEW` | PT5M | The replay window |
| `shipperdog.webhooks.max-body-bytes` | `WEBHOOKS_MAX_BODY_BYTES` | 26214400 | The body ceiling (checked on the declared length before reading, and on the actual bytes before parsing) |

Provider setup notes:

- **App Store**: configure the server notifications URL in App Store
  Connect to `https://<deployment>/webhooks/appstore`; Apple retries
  non-2xx, which the receipt vocabulary honors (duplicates are 200
  DUPLICATE no-ops).
- **Google Play**: create the Pub/Sub subscription for the RTDN topic
  with an OIDC token whose audience is exactly
  `https://<deployment>/webhooks/google-play`; mirror that audience
  into `WEBHOOKS_GOOGLE_PLAY_AUDIENCE`. Pub/Sub redelivers the same
  `messageId` until acknowledged — answered DUPLICATE, idempotent.
