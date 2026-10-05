package com.shipperdog.api.config.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The Idempotency-Key ledger (issue #8). A designated POST that carries a
 * key reserves a row before the handler runs; retries within the retention
 * window replay the stored response verbatim instead of re-executing. The
 * reservation commits in its own transaction so a business rollback never
 * takes the reservation with it (mirroring the login-failure recorder's
 * REQUIRES_NEW pattern).
 *
 * <p>Outcomes: deterministic responses (any status below 500) are captured
 * and replayed; server faults are not — the caller's retry is allowed to
 * reach a fresh handler. Keys live 24 hours; expired rows are swept lazily
 * by the next reservation that touches them.
 */
@Service
public class IdempotencyService {

    /** Retention window for replayable outcomes. */
    static final Duration RETENTION = Duration.ofHours(24);

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    private final IdempotencyKeyRepository keys;

    private final Clock clock;

    private final TransactionTemplate transactions;

    public IdempotencyService(IdempotencyKeyRepository keys, Clock clock, TransactionTemplate transactions) {
        this.keys = keys;
        this.clock = clock;
        this.transactions = transactions;
    }

    /**
     * Reserves the key for this principal/target, or reports what an
     * earlier request left behind. The claim is a single atomic
     * insert-on-conflict-do-nothing, so concurrent duplicates cannot
     * interleave; lookups and the expiry takeover run in their own
     * transactions.
     *
     * @return created=true when this request owns the key (its outcome must
     *         be recorded via {@link #complete}); otherwise stored=true
     *         with the replayable response, or in-progress=true when a
     *         concurrent request is still executing.
     */
    public Reservation acquire(UUID principalId, String method, String requestPath, String rawKey) {
        String keyHash = sha256(rawKey);
        if (insertIfAbsent(principalId, keyHash, method, requestPath, now().plus(RETENTION))) {
            return Reservation.own();
        }
        Optional<IdempotencyKeyEntity> existing = transactions.execute(status ->
                keys.findByPrincipalIdAndKeyHashAndMethodAndRequestPath(principalId, keyHash, method, requestPath));
        if (existing.isEmpty()) {
            // Lost a race with the sweep or a concurrent expiry takeover;
            // claim conservatively as in-progress and let the caller retry.
            return Reservation.busy();
        }
        IdempotencyKeyEntity row = existing.get();
        if (row.isExpired(now())) {
            boolean reclaimed =
                    reserveAfterExpiry(row, principalId, keyHash, method, requestPath, now().plus(RETENTION));
            return reclaimed ? Reservation.own() : Reservation.busy();
        }
        return row.getState() == IdempotencyKeyEntity.State.COMPLETED
                ? Reservation.replay(row.getResponseStatus(), row.getResponseContentType(), row.getResponseBody())
                : Reservation.busy();
    }

    /** Atomic claim; the expiry sweep rides along in the same short transaction. */
    private boolean insertIfAbsent(
            UUID principalId, String keyHash, String method, String requestPath, Instant expiresAt) {
        Integer inserted = transactions.execute(status -> {
            sweepExpired();
            return keys.insertIfAbsent(principalId, keyHash, method, requestPath, expiresAt);
        });
        return inserted != null && inserted > 0;
    }

    /**
     * Takes over an expired slot: the conditional delete only succeeds while
     * the row is still stale, and the fresh claim re-inserts atomically — a
     * peer racing us either loses the delete or the insert.
     */
    private boolean reserveAfterExpiry(
            IdempotencyKeyEntity stale,
            UUID principalId,
            String keyHash,
            String method,
            String requestPath,
            Instant expiresAt) {
        Boolean reclaimed = transactions.execute(status -> {
            int deleted = keys.deleteIfExpired(stale.getId(), now());
            return deleted > 0 && keys.insertIfAbsent(principalId, keyHash, method, requestPath, expiresAt) > 0;
        });
        return Boolean.TRUE.equals(reclaimed);
    }

    private Instant now() {
        return Instant.now(clock);
    }

    /**
     * Captures the outcome of a request that owned its key. Responses of
     * 500 and above are deliberately not recorded: a retry must be allowed
     * to execute again.
     */
    public void complete(
            UUID principalId,
            String method,
            String requestPath,
            String rawKey,
            int status,
            String contentType,
            byte[] body) {
        if (status >= 500) {
            return;
        }
        transactions.executeWithoutResult(tx -> keys.findByPrincipalIdAndKeyHashAndMethodAndRequestPath(
                        principalId, sha256(rawKey), method, requestPath)
                .ifPresent(row -> {
                    row.complete(status, contentType, new String(body, StandardCharsets.UTF_8));
                    keys.save(row);
                }));
    }

    /** Cheap bounded sweep so expiry does not depend on the exact key being reused. */
    private void sweepExpired() {
        try {
            keys.deleteExpiredBefore(Instant.now(clock));
        } catch (RuntimeException e) {
            log.debug("idempotency expiry sweep skipped", e);
        }
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JVM spec; unreachable.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Outcome of {@link #acquire}. */
    public record Reservation(boolean created, boolean inProgress, Integer status, String contentType, String body) {

        static Reservation own() {
            return new Reservation(true, false, null, null, null);
        }

        static Reservation busy() {
            return new Reservation(false, true, null, null, null);
        }

        static Reservation replay(Integer status, String contentType, String body) {
            return new Reservation(false, false, status, contentType, body);
        }
    }
}
