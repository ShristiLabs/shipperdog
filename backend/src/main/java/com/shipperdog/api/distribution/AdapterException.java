package com.shipperdog.api.distribution;

/**
 * The adapter failure taxonomy (issue #61, S13-01, AC5): every failure
 * a channel adapter raises crosses the framework's boundary as this
 * exception, carrying the {@link Category} the sandbox policy keys on
 * -- which failures are retried, how many times, and which problem
 * code the surface renders when retries are exhausted. An adapter that
 * throws anything ELSE fails its dispatch immediately as {@code
 * PERMANENT} with the platform's uniform internal-error detail: the
 * taxonomy is closed by construction.
 *
 * <p>The mapping to the platform's public error registry
 * (docs/error-codes.md):
 * <ul>
 *   <li>{@code TRANSIENT} -- retried with backoff; exhausted answers
 *       {@code ADAPTER_FAILED} (502, upstream reported a retryable
 *       fault).</li>
 *   <li>{@code RATE_LIMITED} -- retried with backoff; exhausted
 *       answers {@code ADAPTER_RATE_LIMITED} (503, upstream
 *       throttled).</li>
 *   <li>{@code TIMEOUT} -- the call is cut at the configured per-
 *       adapter timeout and retried; exhausted answers {@code
 *       ADAPTER_TIMEOUT} (504).</li>
 *   <li>{@code AUTHENTICATION} -- never retried (retrying a bad
 *       credential is a lockout generator); answers {@code
 *       ADAPTER_AUTHENTICATION_FAILED} (502, no material in the
 *       detail).</li>
 *   <li>{@code PERMANENT} -- never retried; answers {@code
 *       ADAPTER_FAILED} (502) with the adapter's public detail.</li>
 * </ul>
 */
public class AdapterException extends RuntimeException {

    /** The closed set of failure kinds the sandbox policy keys on. */
    public enum Category {
        TRANSIENT,
        RATE_LIMITED,
        TIMEOUT,
        AUTHENTICATION,
        PERMANENT
    }

    private final Category category;
    private final String channel;

    /**
     * One adapter failure. The message is the PUBLIC detail (it may
     * surface in the distribution record's failure trail); secret
     * material never reaches it -- the adapter holds it behind
     * {@link SecretMaterial}.
     */
    public AdapterException(Category category, String channel, String message) {
        super(message);
        this.category = category;
        this.channel = channel;
    }

    public AdapterException(Category category, String channel, String message, Throwable cause) {
        super(message, cause);
        this.category = category;
        this.channel = channel;
    }

    public Category category() {
        return category;
    }

    public String channel() {
        return channel;
    }
}
