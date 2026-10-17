---
description: Inbound webhooks - the five authenticated receivers (GitHub, Jenkins, GitLab, App Store, Google Play), their setup, and their signature contracts.
---

# Inbound webhooks

ShipperDog receives events from five providers through authenticated
inbound receivers. Each delivery's own provider authentication *is*
the request's authentication — there is no user token on these
routes. Your **platform operator** switches each receiver on; this
page is the setup and signature contract per provider.

| Receiver | Provider | Authentication | On/off switch |
|---|---|---|---|
| `POST /webhooks/github` | GitHub Actions | HMAC-SHA256 over the body (`X-Hub-Signature-256`) | `WEBHOOKS_GITHUB_SECRETS` (empty = off) |
| `POST /webhooks/jenkins` | Jenkins | The receiver-defined contract: HMAC-SHA256 (`X-Shipperdog-Signature`) computed by your pipeline | `WEBHOOKS_JENKINS_SECRETS` |
| `POST /webhooks/gitlab` | GitLab | `X-Gitlab-Token` compared constant-time (GitLab signs nothing — the transport must be TLS) | `WEBHOOKS_GITLAB_SECRETS` |
| `POST /webhooks/appstore` | App Store | Apple's JWS `signedPayload` (ES256, certificate chain to Apple's root) plus a bundle-id allowlist | `WEBHOOKS_APPSTORE_BUNDLE_IDS` |
| `POST /webhooks/google-play` | Google Play RTDN | Pub/Sub push OIDC bearer (RS256 against Google's public keys; issuer, audience, expiry, freshness) | `WEBHOOKS_GOOGLE_PLAY_AUDIENCE` |

## Set up the GitHub receiver

1. Your platform operator sets `WEBHOOKS_GITHUB_SECRETS` to the
   HMAC secret(s) — a *list*, so rotation is overlap: add the new
   secret, remove the old once deliveries flow.

2. In your GitHub repository: **Settings — Webhooks — Add webhook**.
   URL `https://<your-deployment>/webhooks/github`, content type
   `application/json`, the same secret.

3. Subscribe to the **workflow run** event. Completed runs whose
   commit matches artifacts' frozen `git_commit` claims backfill
   their run URL and pipeline name (backfill-only — values an
   upload already asserted are never overwritten). Teams using the
   [upload action](/how-to/automation/github-actions.md) get a
   no-op; teams uploading other ways still get run URLs attached.

## Set up the Jenkins receiver

Jenkins has no standard webhook signature, so the receiver defines
a thin contract your pipeline computes (the `openssl` one-liner
ships in the [Jenkins template](/how-to/automation/jenkins.md)):
the exact body bytes signed
`X-Shipperdog-Signature: sha256=hex(HMAC-SHA256(secret, body))`,
with a build-completed JSON body. Your operator enables it with
`WEBHOOKS_JENKINS_SECRETS`.

## Set up the GitLab receiver

1. Your operator sets `WEBHOOKS_GITLAB_SECRETS`.
2. In GitLab: **Settings — Webhooks**, URL
   `https://<your-deployment>/webhooks/gitlab`, `application/json`,
   the same token, subscribed to the **Pipeline** event.

A successful pipeline whose commit matches artifacts backfills
their run URL and pipeline name, backfill-only. Because GitLab
sends the token verbatim, TLS is not optional.

## Set up the App Store receiver

1. Your operator sets `WEBHOOKS_APPSTORE_BUNDLE_IDS` to the
   bundle ids allowed through (empty effectively off: everything
   ignores).

2. In App Store Connect: **App information — App Store Server
   Notifications**, point the production (and sandbox) endpoint at
   `https://<your-deployment>/webhooks/appstore`.

Apple signs every notification (`signedPayload`, ES256 with a
certificate chain the receiver validates to Apple's root CA), and
the bundle-id allowlist authorizes — authenticity, then
authorization. Store events (a TestFlight build's processing
state, phased-release day ticks) map onto the release dashboard's
channel facts.

## Set up the Google Play receiver

1. Your operator sets `WEBHOOKS_GOOGLE_PLAY_AUDIENCE` — the
   audience string Google's push carries for your deployment.

2. In Google Play: **Monetization setup — Real-time developer
   notifications**, the Pub/Sub topic's push endpoint pointing at
   `https://<your-deployment>/webhooks/google-play`.

Google's Pub/Sub push signs each delivery as an OIDC bearer token
(RS256 against Google's public keys); the receiver verifies
issuer, audience, expiry, and freshness, with an optional
sender-email allowlist your operator may set.

## What every receiver answers

```json
{"status": "ACCEPTED", "deliveryId": "72d3162e-…"}
```

- **`ACCEPTED`** — consumed and acted on.
- **`IGNORED`** — consumed, deliberately not acted on (an unknown
  event, a foreign bundle, a non-success build): providers expect
  2xx-or-retry, and a no-op must not churn retries.
- **`DUPLICATE`** — the delivery-id ledger already processed this
  delivery: the idempotent no-op.

Rejections are uniform by family — never a hint which leg refused:

- Shared-secret family (GitHub, Jenkins, GitLab): every
  authenticity failure answers **401 `WEBHOOK_SIGNATURE_REJECTED`**.
- Store family (App Store, Google Play): every authenticity
  failure answers **400 `WEBHOOK_DELIVERY_REJECTED`**.
- A verified sender's unreadable body answers
  **400 `WEBHOOK_PAYLOAD_MALFORMED`**; a body beyond the ceiling
  **413 `WEBHOOK_PAYLOAD_TOO_LARGE`**.

Two replay defenses apply everywhere: a timestamp window
(providers that stamp deliveries) and the delivery-id ledger
(every provider). Every outcome is audited — never the secret,
never the raw payload.

**Related guides:** [GitHub Actions](/how-to/automation/github-actions.md) ·
[Jenkins](/how-to/automation/jenkins.md) ·
[GitLab](/how-to/automation/gitlab.md)
