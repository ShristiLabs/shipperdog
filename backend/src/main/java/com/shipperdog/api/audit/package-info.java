/**
 * Immutable audit trail of security-relevant events (issue #6, PRD 27 /
 * FR-024). Modules emit through {@link com.shipperdog.api.audit.AuditLogger}
 * (the one-line full-field API) or the unchanged
 * {@link com.shipperdog.api.audit.AuditEventPublisher} surface; the shared
 * binding enriches every event with request context (correlation id,
 * client IP), applies {@link com.shipperdog.api.audit.AuditRedaction},
 * logs it, and persists it transactionally to the append-only
 * {@code audit_events} table. Tamper-evident chaining (hash chain or
 * periodic anchoring) is documented future hardening, deliberately out of
 * M1 scope; SIEM streaming arrives with S12-01/S14-05.
 *
 * <p>Internal implementation details belong in an {@code .internal.} subpackage
 * here; other modules must not import it (enforced by ArchUnit).
 */
package com.shipperdog.api.audit;
