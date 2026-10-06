package com.shipperdog.api.artifact.internal.storage;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The upload limits of the artifact module (issue #20, S5-01).
 *
 * @param maxBytes the platform-wide artifact ceiling (413 beyond, on
 *     both the multipart and the resumable-session paths)
 * @param resumableThresholdBytes multipart uploads at or below this
 *     size stream through the server; larger ones are refused with the
 *     413 that points at the upload-session path (the story's >100 MB
 *     rule, enforced)
 * @param sessionTtl the upload session's lifetime; finalize refuses
 *     past it (410)
 */
@ConfigurationProperties(prefix = "shipperdog.artifact.upload")
public record ArtifactUploadProperties(long maxBytes, long resumableThresholdBytes, Duration sessionTtl) {}
