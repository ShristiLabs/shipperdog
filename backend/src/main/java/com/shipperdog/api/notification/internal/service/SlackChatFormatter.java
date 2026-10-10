package com.shipperdog.api.notification.internal.service;

import com.shipperdog.api.notification.DomainEvent;
import com.shipperdog.api.notification.NotificationChannel;
import com.shipperdog.api.notification.internal.bus.NotificationProperties;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * The Slack channel's rendering (issue #52, S12-02, AC1): one Block
 * Kit message per event -- a header block with the title, a section
 * block with the template's facts as fields, a context block with the
 * event name and occurrence time, and an actions block with the deep
 * link button back to the console entity. The plain {@code text}
 * member beside the blocks is the fallback strip every notification
 * surface and the mobile push preview render.
 *
 * <p>Posted to the subscription's incoming-webhook URL by the {@link
 * WebhookDeliveryClient} with the standard signature headers -- Slack
 * ignores them (its URLs are themselves the bearer capability), and
 * they cost nothing; a receiver that fronts Slack with its own relay
 * can still verify the way docs/notifications.md documents. Block Kit
 * caps a section at ten fields and a message at fifty blocks -- the
 * template table's facts are far inside both, and the formatter
 * splits at the cap rather than emitting a message Slack would refuse.
 */
@Component
class SlackChatFormatter implements ChatMessageFormatter {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final NotificationProperties properties;

    SlackChatFormatter(NotificationProperties properties) {
        this.properties = properties;
    }

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.SLACK;
    }

    @Override
    public byte[] render(DomainEvent event) {
        ChatEventTemplates.Template template = ChatEventTemplates.of(event.name());
        String title = ChatEventTemplates.titleOf(event, template);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("text", title);
        List<Object> blocks = new ArrayList<>();
        blocks.add(Map.of("type", "header", "text", Map.of("type", "plain_text", "text", title)));
        List<Object> fields = factFields(event, template);
        if (!fields.isEmpty()) {
            blocks.add(Map.of("type", "section", "fields", fields));
        }
        blocks.add(Map.of(
                "type",
                "context",
                "elements",
                List.of(Map.of("type", "mrkdwn", "text", event.name().name() + " • " + event.occurredAt()))));
        String link = ChatEventTemplates.linkOf(event, template, properties.consoleBaseUrl());
        if (!link.isBlank()) {
            blocks.add(Map.of(
                    "type",
                    "actions",
                    "elements",
                    List.of(Map.of(
                            "type",
                            "button",
                            "text",
                            Map.of("type", "plain_text", "text", linkLabel(template)),
                            "url",
                            link))));
        }
        body.put("blocks", blocks);
        return JSON.writeValueAsBytes(body);
    }

    /** The facts the payload actually carries, in Block Kit field shape, capped at Slack's ten. */
    private static List<Object> factFields(DomainEvent event, ChatEventTemplates.Template template) {
        List<Object> fields = new ArrayList<>();
        for (ChatEventTemplates.Fact fact : template.facts()) {
            String value = firstPresent(event, fact);
            if (value == null || fields.size() >= 10) {
                continue;
            }
            fields.add(Map.of("type", "mrkdwn", "text", "*" + fact.label() + "*\n" + value));
        }
        return fields;
    }

    static String linkLabel(ChatEventTemplates.Template template) {
        return switch (template.link()) {
            case RELEASE -> "Open release";
            case APPLICATION -> "Open application";
            case NOTIFICATIONS -> "Open notifications";
        };
    }

    /** The first payload member the fact's key list finds on the event, or null when the event carries none. */
    static String firstPresent(DomainEvent event, ChatEventTemplates.Fact fact) {
        for (String key : fact.payloadKeys()) {
            Object value = event.payload().get(key);
            if (value != null) {
                return ChatEventTemplates.valueOf(value);
            }
        }
        return null;
    }
}
