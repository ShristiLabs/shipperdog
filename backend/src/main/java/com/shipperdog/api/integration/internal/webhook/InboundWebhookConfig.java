package com.shipperdog.api.integration.internal.webhook;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Binds the inbound webhook receiver's properties (issue #53,
 * S12-03): {@code shipperdog.webhooks.*} -- the per-provider secret
 * lists (rotation is overlap), the timestamp-window replay seam, and
 * the body ceiling. See {@link InboundWebhookProperties}.
 */
@Configuration
@EnableConfigurationProperties(InboundWebhookProperties.class)
public class InboundWebhookConfig {}
