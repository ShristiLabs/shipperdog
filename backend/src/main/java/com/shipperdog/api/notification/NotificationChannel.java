package com.shipperdog.api.notification;

/**
 * FR-023's V1.5 delivery channels (issues #51 and #52, S12-01/S12-02):
 * the transport a subscription routes its events to. Slack and
 * Microsoft Teams (FR-023's chat channels, S12-02) are values of this
 * vocabulary riding the webhook machinery -- same URL + signing
 * secret + target policy + retry ledger; only the rendered body is
 * provider-shaped (docs/notifications.md).
 */
public enum NotificationChannel {
    /**
     * The platform inbox: the delivery row IS the item -- written at
     * dispatch, read through the self-scoped inbox route, no external
     * hop, terminal the moment it exists.
     */
    IN_APP,
    /** Email through the {@link EmailSender} seam (WARN-log stub; SMTP transport is a standing follow-up). */
    EMAIL,
    /** A signed HTTP POST of the canonical event JSON, with retry/backoff. */
    WEBHOOK,
    /** A signed POST of a Slack Block Kit message to the subscription's incoming-webhook URL (S12-02). */
    SLACK,
    /** A signed POST of a Teams connector card to the subscription's webhook URL (S12-02). */
    TEAMS
}
