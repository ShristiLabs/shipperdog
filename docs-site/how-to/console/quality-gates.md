---
description: Work with quality gates - read the live gate block, submit results from CI, and override a blocking gate with an audited reason.
---

# Work with quality gates

Quality gates are the measurable half of release governance: a ring
expects certain gates — unit tests passed, coverage above a
threshold, crash-free rate, open critical bugs — and a failed or
data-less required gate blocks the release's READY crossing until it
passes or someone [overrides](#override-a-blocking-gate) it with a
reason. You read gates on the release dashboard; results arrive from
CI.

![Quality gates on the release dashboard](/screenshots/roles/console-rm-release-detail.png)

The gate model — required versus advisory, fail-closed, metric-backed
gates — is covered in
[approvals and gates](/concepts/approvals-gates.md).

## Read the gate block

Open a release (Releases — View). The **Quality gates** block lists
every gate the release's ring expects, each with:

- its **state chip** — `passed`, `failed`, `no data yet
  (fail-closed)`, or `not reported yet`; a required failed gate also
  wears `BLOCKED`,
- the **observed value against the threshold** for numeric gates
  (coverage `87.5` against `threshold 80`),
- an **evidence** link — the report a human can follow,
- `from analytics` on metric-backed gates (their values resolve from
  analytics at evaluation time, not from submissions),
- the override record when one exists.

## Which gates a release expects

The ring decides. The **Release rings** screen shows each ring's
required-gate count on its card (see
[rings and promotion](/how-to/console/rings-and-promotion.md)); a
release lists the gates of the ring it sits on. A release on a ring
with no required gates states that plainly: no gates required, none
reported.

## Submit a result from CI

Gates report through the API or the
[CLI](/how-to/automation/cli-reference.md) — the natural home is
your pipeline:

```bash
shipperdog gate result R-1028 coverage-threshold \
  --status pass --value 87.5 \
  --evidence-url https://ci.example.com/run/8841/coverage
```

- `--status` is `pass` or `fail`; `--evidence-url` is mandatory —
  a gate without proof does not exist.
- Threshold gates (like `coverage-threshold`) also take `--value`,
  the observed number.
- Metric-backed gates (`crash-free-threshold`,
  `open-critical-bugs`) refuse manual submission: their values
  resolve from analytics, fail-closed until the feeds land.
- Re-running a gate is latest-wins; the history is kept and the row
  shows how many results were recorded.

Read the block back with `shipperdog gate list R-1028`.

## Override a blocking gate

The governed exception — exactly once, with a record:

1. On a gate that is not passed (failed, or no data yet), click
   **Override**.
2. Write the **reason** — the dialog marks it *mandatory, audited*.
3. Confirm with **Override**.

The gate keeps its recorded state; only the override stops it
blocking. The row permanently shows who overrode, why, and when,
and the dashboard surfaces the override. Overriding requires the
gate-override authority (release managers and organization admins).

::: tip The feedback shortcut
The `open-critical-bugs` gate reads the release's feedback block
live — [triage a report](/how-to/console/feedback-triage.md) and the
next promotion attempt re-evaluates. Resolve the bug instead of
overriding the gate whenever you can.
:::

**Related guides:** [rings and promotion](/how-to/console/rings-and-promotion.md) ·
[triage feedback](/how-to/console/feedback-triage.md) ·
[CLI reference](/how-to/automation/cli-reference.md)
