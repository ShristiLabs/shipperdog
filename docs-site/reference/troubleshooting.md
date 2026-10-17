---
description: ShipperDog troubleshooting - the top ten support scenarios as symptom, cause, and fix, from CLI exit codes and portal 404s to SSO, SCIM, webhooks, gates, and promotion blockers.
---

# Troubleshooting

The ten scenarios support actually sees, each as symptom, likely
cause, and fix. Codes you meet along the way are explained in
[error codes](/reference/error-codes.md); the vocabulary lives in the
[glossary](/reference/glossary.md).

## The CLI exited non-zero

The exit code is a stable contract pipelines branch on — read it
first, then the printed problem.

| Exit | What it says | Fix |
|---|---|---|
| `1` | Server-side/5xx failure | Retry; if it repeats, report the trace id. |
| `2` | Usage — bad flags, missing args, unresolvable name | Check the command shape against [the CLI reference](/how-to/automation/cli-reference.md). |
| `3` | Auth — no credentials, or 401 | Mint a fresh PAT; `shipperdog login` or set `SHIPPERDOG_TOKEN`. |
| `4` | Validation — the API refused the request (4xx), or validation settled FAILED | Read the problem's code in [error codes](/reference/error-codes.md#cli-and-ci). |
| `5` | Network — refused, DNS, timeout | Check the API URL (`--api` / `SHIPPERDOG_API`) and connectivity. |

## The portal signed me out and 401s everything

| Symptom | Likely cause | Fix |
|---|---|---|
| Working session turns to `UNAUTHENTICATED`/`INVALID_TOKEN` on every action | The access token expired or was revoked mid-session | Sign in again; the portal keeps your place. If it repeats immediately, ask your admin whether your account was suspended or the session's refresh family was revoked (for example by an IdP-initiated logout). |

## A 404 for something you know exists

| Symptom | Likely cause | Fix |
|---|---|---|
| An app, version, release, or feedback row 404s for you but not a colleague | Concealment, not absence: without membership/grant/role you receive the byte-identical 404 a genuine miss produces | Verify your [tenant membership](/concepts/tenancy-isolation.md) and [grant](/concepts/authorization.md); the resource's owner or an admin can compare in the [audit trail](/how-to/console/siem-streams.md) |
| A release detail 404s right after creation in CI | Wrong application scope in the CLI reference (`--app`) | Re-run with the application that owns the release |

## The invitation page says the link is invalid

| Symptom | Likely cause | Fix |
|---|---|---|
| "Open the link from your email" on the join page | Link expired (seven days) or already used — links are single-use | Ask your team for a fresh invitation ([accept an invitation](/how-to/portal/accept-invite.md)) |
| Fresh link still refuses | The email you typed on the join card differs from the invited address | Use exactly the address the invitation was sent to |

## SSO login does not complete

The redirect leaves the IdP and lands nowhere useful — one of the
handshake facts disagrees between the IdP and the organization's IdP
configuration ([configure SSO](/how-to/console/sso-oidc-saml.md)).

| Symptom | Likely cause | Fix |
|---|---|---|
| IdP shows an error before redirecting | Client ID or redirect URI mismatch on the IdP application | Copy the console's SP metadata into the IdP app exactly |
| Redirect lands on an error page | Issuer URL typo, or `enabled` is still `false` | Re-check `issuerUrl` against the IdP's real issuer; enable the record when tested |
| One user's login lands in the Console, another's bounces back to sign-in with an SSO error | Provisioning posture: `INVITED_ONLY` admits members only — and every SSO refusal, whatever its cause, answers the same uniform bounce (the distinct reason rides the audit line) | Add the member first, or switch to `JIT` deliberately (its default role is the VIEWER floor) |
| Group role mappings never apply | `groupClaimName` does not match the token's claim | Name the claim exactly as the IdP issues it |

## SCIM answers 401 to everything

The SCIM surface's 401 is **uniform by design** — no response ever
hints which leg refused, so the fix is a checklist, not a probe
([configure SCIM](/how-to/console/scim-provisioning.md)).

| Symptom | Likely cause | Fix |
|---|---|---|
| Every push from the IdP gets 401 | The connector token in the IdP differs from the one in ShipperDog's SCIM config | Re-copy the token, or rotate it in the console and update the IdP |
| Worked yesterday, 401 today | The token was rotated on one side only | Re-sync both sides |
| Need to know which leg refused | The distinct reason rides the audit line, never the response | An administrator reads the SCIM audit entries |

## Webhook deliveries all fail 401 — or one hits 413

| Symptom | Likely cause | Fix |
|---|---|---|
| Every delivery from GitHub/Jenkins/GitLab answers 401 `WEBHOOK_SIGNATURE_REJECTED` | The shared secret differs between sender and receiver, or no secret is configured at the receiver | Set the identical secret on both sides ([inbound webhooks](/how-to/automation/webhooks.md)) |
| Sporadic 401s from the same sender | Stale-timestamp replay defense: the sender's clock drifts outside the window | Fix the sender's clock skew |
| Store deliveries (App Store/Play) answer 400 `WEBHOOK_DELIVERY_REJECTED` | Provider token/audience verification failed | Re-check the provider's signed-configuration on the receiver |
| A delivery answers 413 `WEBHOOK_PAYLOAD_TOO_LARGE` | Body over the payload ceiling (default 25 MB) | The provider must stay under the ceiling — GitHub's own cap matches |

## A gate is stuck "no data yet (fail-closed)"

| Symptom | Likely cause | Fix |
|---|---|---|
| Required gate blocks with no result ever recorded | No result submitted — the gate needs a pass/fail with a mandatory evidence URL from your pipeline | Submit it from CI: `shipperdog gate result R-… <type> --status pass --evidence-url …` |
| Metric-backed gate (`crash-free-threshold`, `open-critical-bugs`) has no value | It resolves from analytics, not submissions — missing feeds leave it fail-closed | Let the analytics feed land, or [override once with an audited reason](/how-to/console/quality-gates.md#override-a-blocking-gate) |
| `open-critical-bugs` stays red | Feedback reports sit untriaged — the gate reads the live feedback block | [Triage the reports](/how-to/console/feedback-triage.md); the next evaluation re-reads |

## The download link expired mid-install

| Symptom | Likely cause | Fix |
|---|---|---|
| "Link expired" moments after tapping Download | Signed URLs live minutes by design; the pause took too long | Tap Download again — a fresh URL is minted per request |
| Fresh links also refuse, "build not available" | The release was rolled back or the grant revoked while you were installing | That is distribution being cut, not a bug — the team decides what re-opens access ([rollback](/how-to/console/rollback.md)) |

## Promotion answers 409 with a blocker list

| Symptom | Likely cause | Fix |
|---|---|---|
| `RELEASE_PROMOTION_BLOCKED` with gates/approvals/eligibility named | The target ring's evaluation refused the hop — every blocker is listed with evidence | Run `shipperdog release precheck <id>`; fix each named blocker, promote again ([promote through rings](/how-to/console/rings-and-promotion.md)) |
| `RELEASE_PROMOTION_RING_SKIP` | The hop tried to jump a ring — one ring at a time is the default | Promote to the next ring; skips only if your organization explicitly allowed them |
| `RELEASE_PROMOTION_RING_SPLIT` | The selected targets sit on different rings (a prior selective promotion split them) | Promote ring group by ring group |
| `RELEASE_PROMOTION_CONFLICT` | A concurrent promotion moved the targets first | Re-read the release and retry — nothing partial committed |

::: tip When reporting anything
Quote the **trace id** from the toast or the CLI output. It joins your
failure to the exact server-side events — the difference between a
ticket and an answer.
:::
