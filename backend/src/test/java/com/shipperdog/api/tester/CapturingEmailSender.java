package com.shipperdog.api.tester;

import com.shipperdog.api.notification.EmailSender;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Primary;

/**
 * Test-only {@link EmailSender} that records every message in memory so
 * integration tests can assert the invitation email content (accept
 * link, recipient) without parsing log output. Import with
 * {@code @Import(CapturingEmailSender.class)}.
 */
@TestConfiguration(proxyBeanMethods = false)
@Primary
public class CapturingEmailSender implements EmailSender {

    /** One delivered message. */
    public record Sent(String to, String subject, String body) {}

    private final List<Sent> sent = new CopyOnWriteArrayList<>();

    @Override
    public void send(String to, String subject, String body) {
        sent.add(new Sent(to, subject, body));
    }

    public List<Sent> sent() {
        return sent;
    }

    public void reset() {
        sent.clear();
    }
}
