/**
 * User identity: accounts, credentials, and authentication primitives
 * (issue #3). Public surface: the {@code /api/v1/auth/**} REST endpoints,
 * their request/response contracts, and the security chain for
 * {@code /api/v1/**}. The credential sources are intentionally behind
 * service interfaces so an OIDC provider can be added (S14-01) without
 * touching callers.
 *
 * <p>Internal implementation details belong in an {@code .internal.} subpackage
 * here; other modules must not import it (enforced by ArchUnit).
 */
package com.shipperdog.api.identity;
