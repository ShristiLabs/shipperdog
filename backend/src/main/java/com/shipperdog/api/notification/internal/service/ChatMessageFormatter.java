package com.shipperdog.api.notification.internal.service;

import com.shipperdog.api.notification.DomainEvent;
import com.shipperdog.api.notification.NotificationChannel;

/**
 * One provider's rendering of the per-event {@link ChatEventTemplates
 * template} (issue #52, S12-02): the adapter seam the same data-driven
 * partials pour into -- Slack blocks here today, the S14-05 SIEM
 * formatter and V3 AI summary renderer tomorrow, each a new
 * implementation of this one interface over the same template table.
 */
interface ChatMessageFormatter {

    /** The channel this formatter renders for. */
    NotificationChannel channel();

    /** The message body bytes for one event: provider JSON, deterministic key order. */
    byte[] render(DomainEvent event);
}
