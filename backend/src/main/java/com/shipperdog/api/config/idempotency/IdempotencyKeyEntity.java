package com.shipperdog.api.config.idempotency;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One reserved Idempotency-Key (issue #8): a row exists from the moment a
 * designated POST with that key enters processing ({@link State#PROCESSING})
 * until its outcome is captured ({@link State#COMPLETED}), after which the
 * stored response is replayed for retries. Scope is the principal plus the
 * request target; the header value itself is stored only as its SHA-256
 * hex digest.
 */
@Entity
@Table(name = "idempotency_keys")
public class IdempotencyKeyEntity {

    public enum State {
        PROCESSING,
        COMPLETED
    }

    @Id
    private UUID id;

    private UUID principalId;

    private String keyHash;

    private String method;

    private String requestPath;

    @Enumerated(EnumType.STRING)
    private State state;

    private Integer responseStatus;

    private String responseContentType;

    private String responseBody;

    private Instant createdAt;

    private Instant expiresAt;

    protected IdempotencyKeyEntity() {}

    public UUID getId() {
        return id;
    }

    public UUID getPrincipalId() {
        return principalId;
    }

    public String getKeyHash() {
        return keyHash;
    }

    public String getMethod() {
        return method;
    }

    public String getRequestPath() {
        return requestPath;
    }

    public State getState() {
        return state;
    }

    public Integer getResponseStatus() {
        return responseStatus;
    }

    public String getResponseContentType() {
        return responseContentType;
    }

    public String getResponseBody() {
        return responseBody;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    boolean isExpired(Instant now) {
        return expiresAt.isBefore(now);
    }

    /** Captures the completed outcome; 5xx outcomes stay PROCESSING-free by never being recorded. */
    void complete(int status, String contentType, String body) {
        this.state = State.COMPLETED;
        this.responseStatus = status;
        this.responseContentType = contentType;
        this.responseBody = body;
    }
}
