/**
 * Immutable audit trail of security-relevant events. Modules emit through
 * {@link com.shipperdog.api.audit.AuditEventPublisher}; the current
 * binding logs events and republishes them in-process, and the durable
 * sink arrives with the audit framework story (S1-06) without changing
 * callers.
 *
 * <p>Internal implementation details belong in an {@code .internal.} subpackage
 * here; other modules must not import it (enforced by ArchUnit).
 */
package com.shipperdog.api.audit;
