package com.shipperdog.api.artifact.internal.extraction;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The extraction knobs of the artifact module (issue #21, S5-02).
 * Defaults live in {@code application.yml} next to the upload limits.
 *
 * @param concurrency the extraction executor's pool size; extraction
 *     is I/O-bound (ranged object reads), so a small pool saturates
 *     an object store nicely
 * @param queueCapacity the bounded queue ceiling of the executor: a
 *     full queue refuses NEW work loudly (the artifact's
 *     {@code extraction_error} records EXTRACTION_QUEUE_FULL) instead
 *     of accepting unbounded backlog — the bounded-queue rule the
 *     story's async scope line demands
 * @param maxEntryBytes the per-entry cap a parser may read and
 *     inflate: Info.plist files and provisioning profiles are small;
 *     the cap turns a zip bomb into an ENTRY_TOO_LARGE failure, never
 *     an allocation
 * @param storageRetryAttempts attempts against transient storage
 *     failures before the extraction is recorded EXTRACTION_UNAVAILABLE
 *     (the artifact stays VALIDATING — never failed for infra's sake)
 */
@ConfigurationProperties(prefix = "shipperdog.artifact.extraction")
public record ArtifactExtractionProperties(
        int concurrency, int queueCapacity, int maxEntryBytes, int storageRetryAttempts) {}
