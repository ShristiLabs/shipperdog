package com.shipperdog.api.audit;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * An immutable audit record carrying the full FR-024 field set (issue #6):
 * who ({@code userId}), what ({@code type} plus entity references), when
 * ({@code occurredAt}), from where ({@code ipAddress}), tenant /
 * organization context, before / after JSON snapshots, why ({@code
 * reason}), result ({@code success}), and the {@code correlationId} of
 * the originating request.
 *
 * <p>The original seven-component constructor (S1-03) remains available
 * and source-compatible: existing callers keep compiling and the binding
 * layer attaches request context (correlation id, client IP) to every
 * event whose fields are empty.
 *
 * <p>Records are never mutated: the shared {@link AuditEventPublisher}
 * binding enriches and redacts by producing copies before the event is
 * logged, republished in-process, and persisted.
 *
 * @param type          what happened; never null
 * @param occurredAt    when it happened; never null once published
 *                      ({@link AuditLogger} defaults it from its clock)
 * @param userId        the acting user, or null when the actor is anonymous
 * @param orgId         the resolved organization context, or null
 * @param tenantId      the resolved tenant context, or null
 * @param success       whether the event's outcome was successful
 * @param detail        machine-readable context (reason codes and similar);
 *                      never carries credentials, tokens, or raw secrets
 * @param ipAddress     the client address the request came from, or null
 *                      when attached outside a request
 * @param applicationId the affected application, or null
 * @param versionId     the affected version, or null
 * @param artifactId    the affected artifact, or null
 * @param before        JSON snapshot of the state before the operation
 * @param after         JSON snapshot of the state after the operation
 * @param reason        human/compliance-readable motivation, or null
 * @param correlationId the correlation id of the originating request
 */
public record AuditEvent(
        AuditEventType type,
        Instant occurredAt,
        UUID userId,
        UUID orgId,
        UUID tenantId,
        boolean success,
        Map<String, String> detail,
        String ipAddress,
        UUID applicationId,
        UUID versionId,
        UUID artifactId,
        String before,
        String after,
        String reason,
        String correlationId) {

    public AuditEvent {
        Objects.requireNonNull(type, "type");
        detail = detail == null ? Map.of() : Map.copyOf(detail);
    }

    /**
     * The S1-03 shape: the original seven-argument constructor, kept
     * source-compatible so no existing caller changes with S1-06. The
     * FR-024 extension fields are null and get request context attached
     * by the publisher binding.
     */
    public AuditEvent(
            AuditEventType type,
            Instant occurredAt,
            UUID userId,
            UUID orgId,
            UUID tenantId,
            boolean success,
            Map<String, String> detail) {
        this(
                type,
                occurredAt,
                userId,
                orgId,
                tenantId,
                success,
                detail,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    /** Starts a builder; only {@code type} is mandatory. */
    public static Builder builder(AuditEventType type) {
        return new Builder(type);
    }

    /** Fluent construction for callers reporting the full FR-024 shape. */
    public static final class Builder {

        private final AuditEventType type;
        private Instant occurredAt;
        private UUID userId;
        private UUID orgId;
        private UUID tenantId;
        private boolean success;
        private Map<String, String> detail = Map.of();
        private String ipAddress;
        private UUID applicationId;
        private UUID versionId;
        private UUID artifactId;
        private String before;
        private String after;
        private String reason;
        private String correlationId;

        private Builder(AuditEventType type) {
            this.type = Objects.requireNonNull(type, "type");
        }

        public Builder occurredAt(Instant occurredAt) {
            this.occurredAt = occurredAt;
            return this;
        }

        public Builder userId(UUID userId) {
            this.userId = userId;
            return this;
        }

        public Builder orgId(UUID orgId) {
            this.orgId = orgId;
            return this;
        }

        public Builder tenantId(UUID tenantId) {
            this.tenantId = tenantId;
            return this;
        }

        public Builder success(boolean success) {
            this.success = success;
            return this;
        }

        public Builder detail(Map<String, String> detail) {
            this.detail = detail;
            return this;
        }

        public Builder ipAddress(String ipAddress) {
            this.ipAddress = ipAddress;
            return this;
        }

        public Builder applicationId(UUID applicationId) {
            this.applicationId = applicationId;
            return this;
        }

        public Builder versionId(UUID versionId) {
            this.versionId = versionId;
            return this;
        }

        public Builder artifactId(UUID artifactId) {
            this.artifactId = artifactId;
            return this;
        }

        /** JSON snapshot of the state before the operation. */
        public Builder before(String before) {
            this.before = before;
            return this;
        }

        /** JSON snapshot of the state after the operation. */
        public Builder after(String after) {
            this.after = after;
            return this;
        }

        public Builder reason(String reason) {
            this.reason = reason;
            return this;
        }

        public Builder correlationId(String correlationId) {
            this.correlationId = correlationId;
            return this;
        }

        public AuditEvent build() {
            return new AuditEvent(
                    type,
                    occurredAt,
                    userId,
                    orgId,
                    tenantId,
                    success,
                    detail,
                    ipAddress,
                    applicationId,
                    versionId,
                    artifactId,
                    before,
                    after,
                    reason,
                    correlationId);
        }
    }
}
