package com.shipperdog.api.artifact.internal.validation;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The validation knobs of the artifact module (issue #24, S5-05).
 * Defaults live in {@code application.yml} next to the extraction
 * limits; the shape mirrors {@code ArtifactExtractionProperties}
 * because the two tiers share the same backpressure philosophy.
 *
 * @param concurrency the validation executor's pool size; a pipeline
 *     run is I/O-bound (a size probe, a digest stream, registry
 *     reads), so a small pool saturates the same stores extraction
 *     already sizes for
 * @param queueCapacity the bounded queue ceiling of the executor: a
 *     full queue refuses NEW work loudly (the artifact's validation
 *     document records VALIDATION_QUEUE_FULL and the artifact stays
 *     VALIDATING) instead of accepting unbounded backlog
 * @param storageRetryAttempts attempts against transient storage
 *     failures before the attempt is recorded VALIDATION_UNAVAILABLE
 *     (the artifact stays VALIDATING — infrastructure proved nothing
 *     about the binary)
 */
@ConfigurationProperties(prefix = "shipperdog.artifact.validation")
public record ArtifactValidationProperties(int concurrency, int queueCapacity, int storageRetryAttempts) {}
