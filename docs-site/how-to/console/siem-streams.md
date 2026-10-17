---
description: Route the SIEM audit stream and read the audit trail - exports to your collector or bucket, the delivery ledger, and dead-letter replay.
---

# Route the SIEM audit stream and read the audit trail

Every consequential act in ShipperDog — every mutation, decision,
and refusal — lands in an immutable audit event. The **SIEM audit
streams** screen is where that trail leaves the platform: exports
carry it to your HTTPS collector in the `SHIPPERDOG_JSON` or
`SPLUNK_HEC` shape, or to a storage bucket in signed batches.

![The SIEM audit streams screen](/screenshots/roles/console-org-audit-streams.png)

What the audit trail records and why it is immutable is covered in
[the security model](/concepts/security-model.md).

::: tip Who holds this screen
Organization administrators alone — the surface ships
security-relevant material off-platform, so no other role sees it.
:::

## Create an export

1. Open **SIEM audit streams** and click **New SIEM export**.

2. Pick the **Kind**:

   - **HTTPS collector** — deliveries POST to your endpoint.
   - **Bucket** — signed batch files land under your
     `gs://bucket/prefix`.

3. For an HTTPS collector:

   - **Format** — `SHIPPERDOG_JSON` (the versioned envelope) or
     `SPLUNK_HEC` (Splunk's HTTP Event Collector shape).
   - **Collector URL (https://)** — your endpoint.
   - **Signing secret reference (secret manager)** — the HMAC key
     for deliveries, as a secret-manager *reference*; secret
     material itself never enters the configuration.
   - Optionally an **Auth header name** (for example
     `Authorization`) and its **Auth secret reference** (the header
     value — for example the HEC token — resolved the same way).

4. For a bucket export: the **Bucket URI** and the **Initial
   watermark** — empty starts at now (forward-only);
   `1970-01-01T00:00:00Z` backfills the entire audit trail in
   bounded passes.

5. The **Event-type filter** (comma-separated audit event types,
   empty = ALL) narrows the stream — for example only
   `AUTHORIZATION_DECISION`, `ABAC_POLICY_DENIED`, and their
   families for a pure access-governance feed.

6. Save. The **Enabled** switch parks an export without deleting it
   (a parked chip marks it in the list).

## Test the pipeline

**Test** on an export pushes one synthetic envelope through the
*real* delivery pipeline and answers synchronously. A green test
means the collector, the signature, and the auth header all worked;
a red test means the ledger below now carries the diagnostic.

## Read the delivery ledger

**Deliveries** on an export opens the ledger: every attempt with
its status — `SCHEDULED` buffering, `DELIVERED` history, and the
`FAILED` dead-letter. Failed deliveries retry with backoff; when
exhausted they stay visible.

**Replay dead-letter** returns every terminally failed delivery to
the retry schedule with a fresh attempt budget — after you fix the
collector, one click re-runs the queue.

## Bucket batches

**Batches** on a bucket export lists its batch history — each batch
a signed manifest of the events it carries, with the completeness
rollup answering "did anything get lost?" Your compliance team
verifies a batch against its manifest signature; nothing about the
export requires trusting the platform's word for it.

## What flows

The envelope is a versioned public contract — `event_id` (the
dedup key), `event_type` from the frozen vocabulary, timestamps,
actor, subject ids, `result`, correlation id, and the structured
before/after detail. Members are append-only within a schema
version; your parser will not wake up one morning to a different
shape. Every type of audited act is available to the filter —
governance (role changes, policy writes), the release path's every
crossing, and the authorization-decision and ABAC-denial families.

**Related guides:** [ABAC policies](/how-to/console/abac-policies.md) ·
[SSO setup](/how-to/console/sso-oidc-saml.md) ·
[security model concepts](/concepts/security-model.md)
