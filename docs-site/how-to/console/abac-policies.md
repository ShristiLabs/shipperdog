---
description: Author ABAC policies - DENY and REQUIRE rules that narrow access by application, environment, and ring, with versioned history.
---

# Author ABAC policies

Attribute-based access control is the fine-grained layer *above*
roles: while roles say what kind of act you may perform, ABAC rules
say under which conditions any act happens at all. The **Access
policies** screen authors them as data — versioned, ordered, and
audited.

![The Access policies screen](/screenshots/console-abac.png)

Where ABAC sits in the full decision chain is covered in
[authorization](/concepts/authorization.md).

::: warning Both effects only ever narrow
`DENY` blocks outright; `REQUIRE` demands a condition hold. Neither
can *grant* anything a role did not already allow — ABAC is a
one-way ratchet toward less access, never more.
:::

## Create a policy

1. Open **Access policies** and click **New policy**.

2. Give it a **Name** and pick the **Effect**:

   - **DENY (refuse when it holds)** — when the condition holds, the
     act is refused.
   - **REQUIRE (must hold)** — when the scoped act happens, the
     condition must hold or it is refused.

3. Pick the **Action** the rule governs — `PROMOTE` (ring hops),
   `DOWNLOAD`, `READ_ARTIFACT`, or `LIST_APPLICATIONS`.

4. Scope the rule — any field left empty means *any*:

   - **Application (any if empty)**
   - **Environment (any if empty)**
   - **Ring (any if empty)**

5. Set the **Position (order)** — rules evaluate in this order, so
   an early DENY wins before anything later runs.

6. Write the **Condition (JSON)** — the typed condition editor. The
   one-click template seeds a working example; the client checks
   that the JSON parses (a syntax gate — the server's own
   validation is the authority, and its field errors surface
   inline).

7. Save. **Save policy** writes the rule; references (the
   application, environment, or ring it names) resolve server-side
   at write time — a rule naming an unknown ring is refused, not
   silently dead.

## Manage the rule set

- **Edit** reopens the dialog; each save creates a new *version* of
  the policy — nothing is overwritten.
- **Version history** (per policy) lists every version with its
  enabled state.
- The **Enabled** switch in the editor parks a rule without deleting
  it; a disabled chip marks parked rows in the list.

## A worked shape

The classic production guard — "promotions into production require
the release to carry no open critical findings" — becomes a
`REQUIRE` rule scoped to the `production` ring, and a quarantine
shape — "the suspended tenant sees nothing" — a `DENY` rule scoped
to that tenant's context. Compose narrow rules over broad ones;
each is audited when it fires.

**Related guides:** [SSO setup](/how-to/console/sso-oidc-saml.md) ·
[SIEM streams](/how-to/console/siem-streams.md) ·
[authorization concepts](/concepts/authorization.md)
