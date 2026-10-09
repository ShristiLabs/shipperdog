package com.shipperdog.api.feedback.internal.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The feedback module's attachment configuration (issue #46, S11-01):
 * one storage target (bucket plus optional emulator host — the same
 * two-target shape the artifact module established) and the
 * attachment limits (AC3's "up to configured limits"): the per-kind
 * byte ceilings for PRD 8.13's three attachment classes and the
 * per-report file count. Environment-only configuration per the
 * platform profile: {@code application.yml} reads the
 * {@code FEEDBACK_*} variables and supplies compose defaults.
 *
 * @param bucket the storage bucket attachment objects land in (the
 *     tenant-scoped feedback prefix inside it)
 * @param host the emulator endpoint (the compose GCS emulator); blank
 *     means production GCS through application default credentials
 * @param projectId the GCP project (ignored by the emulator)
 * @param createBucketIfAbsent whether to create the bucket at startup
 *     when absent (true locally; production may pre-create)
 * @param maxScreenshotBytes the SCREENSHOT ceiling (png/jpg)
 * @param maxVideoBytes the VIDEO ceiling (mp4)
 * @param maxLogBytes the LOG ceiling (txt/log)
 * @param maxAttachmentsPerReport the per-report file count bound — an
 *     abuse bound on a surface any linked tester may write
 */
@ConfigurationProperties(prefix = "shipperdog.feedback.storage")
public record FeedbackStorageProperties(
        String bucket,
        String host,
        String projectId,
        boolean createBucketIfAbsent,
        long maxScreenshotBytes,
        long maxVideoBytes,
        long maxLogBytes,
        int maxAttachmentsPerReport) {

    /** True when an emulator host is configured (local/CI compose) — no credentials, direct host. */
    public boolean emulatorMode() {
        return host != null && !host.isBlank();
    }
}
