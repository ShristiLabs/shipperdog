---
description: Route notifications - subscribe roles and channels to domain events, test the pipeline, and read the delivery log.
---

# Route notifications

The **Notifications** screen is your routing table for domain
events: which events go to which channel and recipients — the in-app
inbox, email, a signed webhook, or the chat channels. Beside it, the
delivery ledger records every attempt.

![The Notifications screen](/screenshots/console-notifications.png)

## Create a subscription

1. Open **Notifications** and click **New subscription**.

2. Pick the **Events** — from the frozen vocabulary that ships with
   the platform: `ARTIFACT_UPLOADED`, `ARTIFACT_VALIDATED`,
   `ARTIFACT_FAILED`, `TESTER_DOWNLOADED`, `RELEASE_CREATED`,
   `RELEASE_TRANSITIONED`, `APPROVAL_REQUESTED`, `RELEASE_APPROVED`,
   `RELEASE_PROMOTED`, `TESTER_INVITED`, `FEEDBACK_RECEIVED`,
   `FEEDBACK_MARKED_CRITICAL`, and `AUDIT_EVENT` (the SIEM bridge).
   Subscribe one subscription to as many as it should carry.

3. Pick the **Channel**:

   - **In-app inbox** — messages surface in the Console for the
     chosen recipients.
   - **Email** — the classic.
   - **Webhook (signed)** — an HTTPS receiver of yours; ShipperDog
     signs every delivery.
   - **Slack** / **Microsoft Teams** — a channel webhook URL.

4. Name the recipients:

   - Inbox and email take **Recipient roles** (a multi-select; anyone
     holding the role hears about it) and optionally **Recipient user
     ids**.
   - The webhook family takes the channel's **webhook URL** and, on
     creation, a **Signing secret** (16+ characters) — the key your
     receiver verifies deliveries with. When editing, a blank secret
     keeps the standing one; set a new value to rotate.

5. Save. New subscriptions are born enabled; the **Enabled** switch
   in the editor is the parking switch — a disabled subscription
   routes nothing but keeps its shape.

## Test before you trust

**Send test** on a subscription pushes one synthetic event through
the *real* channel pipeline — not a mock. Success confirms
end-to-end; failure renders the error inline ("the delivery log
below carries the retry schedule") and the ledger gains the row.

## Read the delivery ledger

The log beneath the subscriptions lists every delivery attempt with
its status and last error. Failed deliveries retry with backoff and
stay visible when exhausted — the integrator's working list. Park a
misbehaving subscription (the **Enabled** switch in its editor)
while you fix the receiver, then test again.

## What to subscribe to, as a starting shape

| You are | Subscribe |
|---|---|
| Release manager | `APPROVAL_REQUESTED` (email) — your pending queue grows |
| QA lead | `FEEDBACK_RECEIVED` + `FEEDBACK_MARKED_CRITICAL` (Slack) — the queue moves |
| Security reviewer | `FEEDBACK_MARKED_CRITICAL` — security reports surface fast |
| Compliance | `AUDIT_EVENT` through the [SIEM stream](/how-to/console/siem-streams.md) rather than chat — the durable shape |

::: tip Events are identifiers, not prose
Event payloads carry identifiers and stable vocabularies only —
never personal data a listener lacks the authority to read. Route
freely; the platform already worried about it.
:::

**Related guides:** [SIEM streams](/how-to/console/siem-streams.md) ·
[dashboards](/how-to/console/analytics-and-dashboards.md) ·
[org-admin quickstart](/roles/org-admin.md)
