# ADR-0006: Portal and console sessions stay in localStorage for M1, hardened by CSP

Date: 2026-10-08
Status: Accepted (issue #39, S8-05)

## Context

S8-05's scope says "cookie/localStorage token strategy hardened", and
the S8-01 portal documented an accepted trade-off: both SPAs keep the
token pair (access + refresh) in `localStorage`, one atomic write per
session change, because a stolen token grants only what its holder
could already do — the API never trusts the client for
authorization. This ADR records the decision consciously instead of
by inertia, and names the hardening that ships with it.

The alternative is cookie-based sessions (httpOnly refresh cookie,
CSRF handling, same-site discipline): materially better XSS
containment (a script cannot exfiltrate what it cannot read), at the
cost of a backend cookie surface (issuance, rotation, revocation
semantics, CSRF tokens on the console's mutating calls) and CORS
rework the moment origins split.

## Decision

**localStorage stays for M1, and the shipped hardening is:**

1. **A strict CSP on both origins** (the S8-05 header set):
   `script-src 'self'` with no inline scripts in either bundle — the
   XSS vector that would make token theft trivial is the one the
   policy eliminates; `connect-src 'self'` bounds exfiltration
   channels; `object-src 'none'`, `frame-ancestors 'none'`,
   `base-uri 'self'`. Graded continuously by the CI header check.
2. **Short-lived access tokens** (`AUTH_ACCESS_TOKEN_TTL`, 15 min)
   with **single-use refresh rotation and reuse detection** (the
   identity tier since S1-03): a stolen refresh token dies the first
   time the legitimate client refreshes.
3. **Rate-limited login and download doors** (S8-05's in-app
   limiter): brute force pays per client IP, and the identity lockout
   still owns per-account.
4. **HSTS, nosniff, DENY framing, no-referrer, empty
   Permissions-Policy** on every origin the platform serves (nginx
   for the SPAs, Spring Security for the API).

**The migration path to cookies is recorded, not built:** when SSO
arrives (M2/M3, PRD P8), the session tier moves to httpOnly cookies
issued by the identity module alongside the OIDC flow — the client's
`tokenStore` is the only seam to replace, and this ADR is the
reminder that M1 shipped with the trade-off consciously accepted.

## Consequences

- No backend change for M1 sessions; the portal and console keep
  their independent storage keys (tester and operator coexist in one
  browser).
- XSS remains the threat model's live wire: the CSP is load-bearing,
  and any future feature that wants an inline script or a
  third-party origin must come back through this decision (a nonce
  story or a CSP amendment), not quietly weaken the policy.
- Token lifetime bounds exposure; logout and session-expiry clear
  the store atomically.
- Security reviewers doing enterprise due diligence get this ADR as
  the honest answer to "where do sessions live".
