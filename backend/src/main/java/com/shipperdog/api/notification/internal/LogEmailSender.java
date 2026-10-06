package com.shipperdog.api.notification.internal;

import com.shipperdog.api.notification.EmailSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Default {@link EmailSender}: logs the message on the audit logger at
 * WARNING — the exact delivery pattern the S1-03 password-reset link
 * established (no mail transport exists in M1; SMTP arrives with
 * S12-02). Local development, integration tests, and operators tailing
 * the log can read the message; nothing is sent over the network.
 */
@Component
public class LogEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(LogEmailSender.class);

    @Override
    public void send(String to, String subject, String body) {
        log.warn(
                "email delivery (no transport configured, S12-02 pending): to=<{}> subject=\"{}\"\n{}",
                to,
                subject,
                body);
    }
}
