package com.shipperdog.api.notification;

/**
 * Outbound email delivery (issue #26): the seam every story that sends
 * mail programs against, so no module wires a transport of its own.
 * The {@code Tester invited} notification EVENT (publish/subscribe,
 * PRD section 11) is deliberately NOT modeled here — it is reserved
 * for the S12-01 notifications epic, which will formalize channels,
 * templates, and preferences on top of this seam.
 *
 * <p>Implementations must never log the raw message body at levels
 * production log aggregation replays to wider audiences than the
 * recipient — the default implementation logs at WARNING on the
 * audit logger, mirroring the S1-03 reset-link delivery decision
 * (dev-mode visibility, no transport). SMTP transport arrives with
 * S12-02.
 */
public interface EmailSender {

    /**
     * Delivers a plain-text email. Implementations must treat the body
     * as recipient-private content.
     *
     * @param to      the recipient address (already normalized by the
     *                caller)
     * @param subject the message subject
     * @param body    the plain-text body
     */
    void send(String to, String subject, String body);
}
