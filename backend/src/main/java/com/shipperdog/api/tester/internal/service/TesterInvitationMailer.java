package com.shipperdog.api.tester.internal.service;

import com.shipperdog.api.notification.EmailSender;
import com.shipperdog.api.tester.internal.config.TesterProperties;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * Composes and delivers the tester invitation email (issue #26): the
 * accept link, its expiry, and the notification-seam hand-off. Shared
 * by the creation attempt and the re-invite path so both speak the
 * same wording; the portal base URL comes from
 * {@link TesterProperties}.
 */
@Component
public class TesterInvitationMailer {

    private final EmailSender email;
    private final TesterProperties properties;

    TesterInvitationMailer(EmailSender email, TesterProperties properties) {
        this.email = email;
        this.properties = properties;
    }

    /** Delivers the invitation email with the single-use accept link. */
    public void sendInvitation(String to, String fullName, String rawToken, Instant expiresAt) {
        String body = "Hello "
                + fullName
                + ",\n\nYou have been invited to test applications on ShipperDog.\n"
                + "Accept the invitation and choose a password at:\n"
                + acceptUrl(rawToken)
                + "\n\nThe link is single-use and expires at "
                + expiresAt
                + ".\nIf you did not expect this invitation, ignore this message.";
        email.send(to, "You are invited to test on ShipperDog", body);
    }

    /** The accept link handed to the invoker (dev-mode visibility, S1-03 pattern). */
    public String acceptUrl(String rawToken) {
        String base = properties.portalBaseUrl();
        return base + (base.endsWith("/") ? "" : "/") + "accept-invitation?token=" + rawToken;
    }
}
