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
 * The Microsoft Teams channel's rendering (issue #52, S12-02, AC2):
 * one connector card (MessageCard) per event -- the {@code themeColor}
 * carries the template's semantic color, the section's {@code facts}
 * carry the entity context, and an {@code OpenUri} action carries the
 * deep link back to the console entity.
 *
 * <p>Posted to the subscription's webhook URL by the {@link
 * WebhookDeliveryClient} with the standard signature headers (Teams
 * ignores unknown headers). The card shape is the connector-card
 * contract the acceptance criterion names; Teams workflow URLs (the
 * Power Automate mechanism that replaced retired Office 365 connector
 * creation) accept the same event facts when the workflow's trigger is
 * the plain "when a webhook request is received" template --
 * docs/notifications.md carries the note.
 */
@Component
class TeamsChatFormatter implements ChatMessageFormatter {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final NotificationProperties properties;

    TeamsChatFormatter(NotificationProperties properties) {
        this.properties = properties;
    }

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.TEAMS;
    }

    @Override
    public byte[] render(DomainEvent event) {
        ChatEventTemplates.Template template = ChatEventTemplates.of(event.name());
        String title = ChatEventTemplates.titleOf(event, template);
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("@type", "MessageCard");
        card.put("@context", "http://schema.org/extensions");
        card.put("themeColor", template.themeColor());
        card.put("summary", title);
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("activityTitle", title);
        List<Object> facts = factList(event, template);
        if (!facts.isEmpty()) {
            section.put("facts", facts);
        }
        section.put("text", event.name().name() + " • " + event.occurredAt());
        section.put("markdown", false);
        String link = ChatEventTemplates.linkOf(event, template, properties.consoleBaseUrl());
        if (!link.isBlank()) {
            section.put(
                    "potentialAction",
                    List.of(Map.of(
                            "@type", "OpenUri",
                            "name", SlackChatFormatter.linkLabel(template),
                            "targets", List.of(Map.of("os", "default", "url", link)))));
        }
        card.put("sections", List.of(section));
        return JSON.writeValueAsBytes(card);
    }

    /** The facts the payload actually carries, in the connector card's name/value shape. */
    private static List<Object> factList(DomainEvent event, ChatEventTemplates.Template template) {
        List<Object> facts = new ArrayList<>();
        for (ChatEventTemplates.Fact fact : template.facts()) {
            String value = SlackChatFormatter.firstPresent(event, fact);
            if (value == null) {
                continue;
            }
            facts.add(Map.of("name", fact.label(), "value", value));
        }
        return facts;
    }
}
