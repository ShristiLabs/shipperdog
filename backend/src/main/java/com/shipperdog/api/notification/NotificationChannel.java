package com.shipperdog.api.notification;

/**
 * FR-023's V1.5 delivery channels (issue #51, S12-01): the transport
 * a subscription routes its events to. Slack and Microsoft Teams
 * (FR-023's other two channels) are S12-02's formatters on top of the
 * webhook machinery this story ships -- they arrive as new values of
 * this vocabulary, never as a different model.
 */
public enum NotificationChannel {
    /**
     * The platform inbox: the delivery row IS the item -- written at
     * dispatch, read through the self-scoped inbox route, no external
     * hop, terminal the moment it exists.
     */
    IN_APP,
    /** Email through the {@link EmailSender} seam (WARN-log stub; SMTP transport is S12-02). */
    EMAIL,
    /** A signed HTTP POST to the subscription's URL, with retry/backoff. */
    WEBHOOK
}
