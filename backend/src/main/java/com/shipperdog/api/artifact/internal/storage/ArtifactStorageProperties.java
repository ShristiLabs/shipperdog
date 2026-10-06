package com.shipperdog.api.artifact.internal.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The artifact module's storage configuration (issue #20, S5-01,
 * ADR-0003): one storage target — bucket, optional emulator host,
 * project id. Environment-only configuration per the platform
 * profile: {@code application.yml} reads the {@code ARTIFACT_*}
 * variables and supplies compose defaults.
 *
 * @param bucket the storage bucket artifacts land in (the PRD 24
 *     layout inside it)
 * @param host the emulator endpoint (e.g. {@code http://localhost:4443}
 *     for the compose GCS emulator); blank means production GCS through
 *     application default credentials — one client, both targets
 * @param projectId the GCP project (ignored by the emulator)
 * @param createBucketIfAbsent whether to create the bucket at startup
 *     when absent (true locally; production may pre-create and set
 *     false)
 */
@ConfigurationProperties(prefix = "shipperdog.artifact.storage")
public record ArtifactStorageProperties(String bucket, String host, String projectId, boolean createBucketIfAbsent) {}
