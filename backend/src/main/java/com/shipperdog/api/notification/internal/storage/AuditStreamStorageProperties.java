package com.shipperdog.api.notification.internal.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The audit-stream bucket writer's storage knobs (issue #71,
 * S14-05): the same two-target shape the artifact and feedback
 * stores ride -- a configured host talks to the compose GCS emulator
 * (local/CI); a blank host falls through to the client's defaults
 * (production GCS, application default credentials -- the customer
 * grants the platform's writer identity access to their bucket, the
 * PRD 25 posture documented in docs/siem.md). One module, one
 * client, one bucket namespace per concern.
 *
 * @param host the emulator endpoint, or blank for production GCS
 * @param projectId the GCP project id the client binds
 */
@ConfigurationProperties(prefix = "shipperdog.notifications.audit-stream.storage")
public record AuditStreamStorageProperties(
        @DefaultValue("") String host,
        @DefaultValue("shipperdog-local") String projectId) {}
