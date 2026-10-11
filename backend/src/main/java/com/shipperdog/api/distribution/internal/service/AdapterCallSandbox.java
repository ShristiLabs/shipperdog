package com.shipperdog.api.distribution.internal.service;

import com.shipperdog.api.config.MetricNames;
import com.shipperdog.api.distribution.AdapterException;
import com.shipperdog.api.distribution.DistributionException;
import com.shipperdog.api.distribution.internal.config.DistributionProperties;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.IntFunction;
import org.springframework.stereotype.Component;

/**
 * The per-channel sandbox every adapter call runs under (issue #61,
 * S13-01, AC5; shared since S13-03, #63): one traced, timed
 * observation per attempt ({@code shipperdog.adapter.duration} tagged
 * channel, attempt, and operation — the timer and the trace span are
 * the same artifact, PRD 28), a wall-clock cut that interrupts the
 * hung call, and bounded retries keyed on the failure taxonomy.
 * Anything the adapter throws that is not {@link AdapterException}
 * is PERMANENT (the taxonomy is closed).
 *
 * <p>Extracted from the dispatch service when the App Store
 * connector's phased-release control (S13-03, AC3) needed the SAME
 * walls the dispatch and the status poll run under: one sandbox, one
 * telemetry shape, every adapter conversation.
 */
@Component
public class AdapterCallSandbox {

    private final DistributionProperties properties;
    private final ObservationRegistry observations;
    private final ExecutorService calls = Executors.newVirtualThreadPerTaskExecutor();

    public AdapterCallSandbox(DistributionProperties properties, ObservationRegistry observations) {
        this.properties = properties;
        this.observations = observations;
    }

    @PreDestroy
    void shutdown() {
        calls.close();
    }

    /**
     * Runs one sandboxed adapter conversation. The call receives its
     * 1-based attempt number (the dispatch rebuilds its command per
     * attempt with it; single-shot calls ignore it).
     *
     * @param channel   the channel id the sandbox's walls and tags resolve by
     * @param operation the operation tag of this conversation's attempts (distribute, status, phased-release, ...)
     * @param call      the adapter conversation, attempt-numbered
     * @return the answer, the taxonomy failure, and the calls consumed
     */
    public <T> Result<T> run(String channel, String operation, IntFunction<T> call) {
        DistributionProperties.AdapterSandbox sandbox = properties.sandboxOf(channel);
        int attempt = 1;
        AdapterException lastFailure = null;
        while (attempt <= sandbox.totalAttempts()) {
            Observation observation = Observation.createNotStarted(MetricNames.ADAPTER_CALL, observations)
                    .lowCardinalityKeyValue("channel", channel)
                    .lowCardinalityKeyValue("attempt", String.valueOf(attempt))
                    .lowCardinalityKeyValue("operation", operation);
            Future<T> submitted = null;
            try {
                observation.start();
                final int attemptNumber = attempt;
                submitted = calls.submit(() -> call.apply(attemptNumber));
                T answer = submitted.get(sandbox.timeout().toMillis(), TimeUnit.MILLISECONDS);
                observation.lowCardinalityKeyValue("outcome", "ok");
                observation.stop();
                return new Result<>(answer, null, attempt);
            } catch (TimeoutException e) {
                // The wall cut: interrupt the hung call so its thread and
                // socket do not linger past the sandbox's verdict.
                if (submitted != null) {
                    submitted.cancel(true);
                }
                lastFailure = new AdapterException(
                        AdapterException.Category.TIMEOUT,
                        channel,
                        "the adapter call exceeded its " + sandbox.timeout().toMillis() + " ms timeout");
            } catch (java.util.concurrent.ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof AdapterException adapterFailure) {
                    lastFailure = adapterFailure;
                } else if (cause instanceof DistributionException domainFault) {
                    // A DOMAIN fault the conversation surfaced (the
                    // phased-release surface's honest 404s): the
                    // sandbox walls the provider call, it never rewrites
                    // the platform's own problem answers (the reviewer's
                    // finding — the 404 contract must surface verbatim).
                    observation.lowCardinalityKeyValue("outcome", "domain-fault");
                    observation.stop();
                    throw domainFault;
                } else {
                    lastFailure = new AdapterException(
                            AdapterException.Category.PERMANENT,
                            channel,
                            "the adapter call failed: " + cause.getClass().getSimpleName());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                observation.lowCardinalityKeyValue("outcome", "interrupted");
                observation.stop();
                throw new IllegalStateException("adapter call interrupted", e);
            } catch (java.util.concurrent.RejectedExecutionException e) {
                lastFailure = new AdapterException(
                        AdapterException.Category.TRANSIENT, channel, "the adapter pool refused the call");
            }
            observation.lowCardinalityKeyValue("outcome", taxonomyOf(lastFailure));
            observation.stop();
            if (!retryable(lastFailure.category()) || attempt == sandbox.totalAttempts()) {
                return new Result<>(null, lastFailure, attempt);
            }
            attempt++;
            sleep(sandbox.retryBackoff());
        }
        return new Result<>(null, lastFailure, attempt);
    }

    private static boolean retryable(AdapterException.Category category) {
        return category == AdapterException.Category.TRANSIENT
                || category == AdapterException.Category.RATE_LIMITED
                || category == AdapterException.Category.TIMEOUT;
    }

    /** The taxonomy's public code for a failure (the record's and the metric's vocabulary). */
    public static String taxonomyOf(AdapterException failure) {
        return taxonomyCodeOf(failure).codeValue();
    }

    /** The taxonomy's problem code for a failure (the same closed mapping the records carry). */
    public static DistributionException.Code taxonomyCodeOf(AdapterException failure) {
        return switch (failure.category()) {
            case TIMEOUT -> DistributionException.Code.ADAPTER_TIMEOUT;
            case RATE_LIMITED -> DistributionException.Code.ADAPTER_RATE_LIMITED;
            case AUTHENTICATION -> DistributionException.Code.ADAPTER_AUTHENTICATION_FAILED;
            case TRANSIENT, PERMANENT -> DistributionException.Code.ADAPTER_FAILED;
        };
    }

    private static void sleep(Duration backoff) {
        try {
            Thread.sleep(backoff.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("adapter backoff interrupted", e);
        }
    }

    /** The sandbox's answer: the conversation's answer, its taxonomy failure, and the calls consumed. */
    public record Result<T>(T answer, AdapterException failure, int calls) {}
}
