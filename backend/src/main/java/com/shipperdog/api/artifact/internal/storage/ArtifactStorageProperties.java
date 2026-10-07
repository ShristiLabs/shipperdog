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
public record ArtifactStorageProperties(String bucket, String host, String projectId, boolean createBucketIfAbsent) {

    /**
     * True when an emulator host is configured (local/CI compose): the
     * one predicate both the store (no credentials, direct host) and
     * the download tier (proxy URLs instead of GCS V4 signing) branch
     * on. Lives here — on the configuration it derives from — so the
     * two consumers cannot drift apart at the M2 production seam
     * (issue #39's consolidation of the #38 review's duplication
     * finding).
     */
    public boolean emulatorMode() {
        return host != null && !host.isBlank();
    }
}
