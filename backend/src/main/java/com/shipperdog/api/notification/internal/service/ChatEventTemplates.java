package com.shipperdog.api.notification.internal.service;

import com.shipperdog.api.notification.DomainEvent;
import com.shipperdog.api.notification.DomainEventName;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The per-event chat message templates (issue #52, S12-02, AC4): the
 * data-driven partials the {@link SlackChatFormatter} and {@link
 * TeamsChatFormatter} both render -- never a formatter-private string.
 * The story's technical note is the design constraint: the same
 * per-event rendering later feeds the S14-05 SIEM formatting and V3 AI
 * summaries, so the template table is the contract and the formatters
 * are thin adapters over it.
 *
 * <p>A template names the heading, the semantic color (Teams {@code
 * themeColor}; Slack blocks carry no color), whether the event is a
 * failure (blockers render first-class), which payload members surface
 * as facts (label + the payload keys to try in order -- first present
 * renders, so an event that carries fewer facts degrades gracefully
 * instead of rendering blanks), and the deep link's kind. The facts
 * render what the event actually carries (the payload discipline:
 * identifiers and stable vocabularies only) -- {@code releaseRef}, the
 * human-readable release reference, is the anchor fact every
 * release-grain event carries, and the failure events surface their
 * blocker vocabulary ({@code failureCode}, {@code reason}) beside it.
 * A name without a template renders the default partial -- reserved
 * names arriving with their owning stories need no code change to be
 * readable, only a template entry to be rich.
 */
final class ChatEventTemplates {

    /** The deep link a template's message button opens. */
    enum LinkKind {
        /** The release detail page (falls back to the application when the event carries no release). */
        RELEASE,
        /** The application detail page. */
        APPLICATION,
        /** The console's notifications page (the test message's destination). */
        NOTIFICATIONS
    }

    /** One fact's specification: the display label and the payload keys to try, in order. */
    record Fact(String label, List<String> payloadKeys) {}

    /**
     * One event's partial: the heading (the releaseRef appends when
     * present), the hex theme color, the failure marker, the facts,
     * and the deep link.
     */
    record Template(String heading, String themeColor, boolean failure, List<Fact> facts, LinkKind link) {}

    private static final String GREEN = "2EB67D";
    private static final String RED = "CC0000";
    private static final String BLUE = "0078D7";

    private static final Fact RELEASE = new Fact("Release", List.of("releaseRef"));
    private static final Fact PLATFORM = new Fact("Platform", List.of("platform"));

    private static final Map<DomainEventName, Template> TEMPLATES = templates();

    private ChatEventTemplates() {}

    /** The template for an event name: its partial when the table has one, the default partial otherwise. */
    static Template of(DomainEventName name) {
        return TEMPLATES.getOrDefault(name, DEFAULT);
    }

    private static Map<DomainEventName, Template> templates() {
        Map<DomainEventName, Template> table = new EnumMap<>(DomainEventName.class);
        table.put(
                DomainEventName.ARTIFACT_UPLOADED,
                new Template(
                        "Artifact uploaded",
                        BLUE,
                        false,
                        List.of(
                                PLATFORM,
                                new Fact("File", List.of("fileName")),
                                new Fact("Size (bytes)", List.of("size")),
                                new Fact("SHA-256", List.of("sha256"))),
                        LinkKind.APPLICATION));
        table.put(
                DomainEventName.ARTIFACT_VALIDATED,
                new Template(
                        "Artifact ready",
                        GREEN,
                        false,
                        List.of(PLATFORM, RELEASE, new Fact("Outcome", List.of("outcome"))),
                        LinkKind.APPLICATION));
        table.put(
                DomainEventName.ARTIFACT_FAILED,
                new Template(
                        "Artifact failed validation",
                        RED,
                        true,
                        List.of(
                                PLATFORM,
                                RELEASE,
                                new Fact("Outcome", List.of("outcome")),
                                new Fact("Blocker", List.of("failureCode"))),
                        LinkKind.APPLICATION));
        table.put(
                DomainEventName.RELEASE_CREATED,
                new Template(
                        "Release created",
                        BLUE,
                        false,
                        List.of(RELEASE, new Fact("Ring", List.of("ring"))),
                        LinkKind.RELEASE));
        table.put(
                DomainEventName.RELEASE_TRANSITIONED,
                new Template(
                        "Release status changed",
                        BLUE,
                        false,
                        List.of(
                                RELEASE,
                                new Fact("From", List.of("fromStatus")),
                                new Fact("To", List.of("toStatus")),
                                new Fact("Reason", List.of("reason"))),
                        LinkKind.RELEASE));
        table.put(
                DomainEventName.APPROVAL_REQUESTED,
                new Template(
                        "Approval requested",
                        BLUE,
                        false,
                        List.of(
                                RELEASE,
                                new Fact("Approval", List.of("approvalType")),
                                new Fact("Round", List.of("roundNumber")),
                                new Fact("Position", List.of("position"))),
                        LinkKind.RELEASE));
        table.put(
                DomainEventName.RELEASE_APPROVED,
                new Template(
                        "Release approved",
                        GREEN,
                        false,
                        List.of(RELEASE, new Fact("From", List.of("fromStatus")), new Fact("To", List.of("toStatus"))),
                        LinkKind.RELEASE));
        table.put(
                DomainEventName.RELEASE_PROMOTED,
                new Template(
                        "Release promoted",
                        GREEN,
                        false,
                        List.of(
                                RELEASE,
                                new Fact("From ring", List.of("fromRing")),
                                new Fact("To ring", List.of("toRing")),
                                new Fact("Crossed to PROMOTED", List.of("crossedToPromoted"))),
                        LinkKind.RELEASE));
        table.put(
                DomainEventName.TESTER_INVITED,
                new Template(
                        "Tester invited",
                        BLUE,
                        false,
                        List.of(new Fact("Re-invite", List.of("reinvite"))),
                        LinkKind.NOTIFICATIONS));
        table.put(
                DomainEventName.TESTER_DOWNLOADED,
                new Template(
                        "Tester downloaded an artifact",
                        BLUE,
                        false,
                        List.of(PLATFORM, RELEASE, new Fact("Actor", List.of("testerUserId"))),
                        LinkKind.RELEASE));
        table.put(
                DomainEventName.FEEDBACK_RECEIVED,
                new Template(
                        "Feedback received",
                        BLUE,
                        false,
                        List.of(
                                RELEASE,
                                new Fact("Type", List.of("feedbackType")),
                                new Fact("Attachments", List.of("attachmentCount"))),
                        LinkKind.RELEASE));
        table.put(
                DomainEventName.FEEDBACK_MARKED_CRITICAL,
                new Template(
                        "Feedback marked critical",
                        RED,
                        true,
                        List.of(RELEASE, new Fact("Type", List.of("feedbackType"))),
                        LinkKind.RELEASE));
        table.put(
                DomainEventName.NOTIFICATION_TEST_SENT,
                new Template(
                        "ShipperDog test notification",
                        BLUE,
                        false,
                        List.of(
                                new Fact("Channel", List.of("channel")),
                                new Fact("Subscription", List.of("subscriptionId"))),
                        LinkKind.NOTIFICATIONS));
        return table;
    }

    /** The partial every untemplated name renders: the event's own identity, nothing invented. */
    private static final Template DEFAULT =
            new Template("ShipperDog event", BLUE, false, List.of(RELEASE), LinkKind.RELEASE);

    // Rendering helpers shared by the formatters --------------------------------

    /**
     * The rendered title: the heading, the releaseRef appended with a
     * separating dash when the payload carries one -- the one-line
     * summary a chat client shows in its notification strip.
     */
    static String titleOf(DomainEvent event, Template template) {
        Object releaseRef = event.payload().get("releaseRef");
        return releaseRef == null ? template.heading() : template.heading() + " — " + releaseRef;
    }

    /**
     * The deep link the template's button opens, resolved against the
     * event's actual context: the release page when the event carries
     * application and release, else the application page, else the
     * notifications page -- never a link with a null id baked in.
     */
    static String linkOf(DomainEvent event, Template template, String consoleBaseUrl) {
        String base = consoleBaseUrl == null || consoleBaseUrl.isBlank() ? "" : stripTrailingSlash(consoleBaseUrl);
        return switch (template.link()) {
            case RELEASE ->
                event.applicationId() != null && event.releaseId() != null
                        ? base + "/releases/" + event.applicationId() + "/" + event.releaseId()
                        : applicationLink(event, base);
            case APPLICATION -> applicationLink(event, base);
            case NOTIFICATIONS -> base + "/notifications";
        };
    }

    private static String applicationLink(DomainEvent event, String base) {
        return event.applicationId() != null
                ? base + "/applications/" + event.applicationId()
                : base + "/notifications";
    }

    private static String stripTrailingSlash(String base) {
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    /** Renders one payload value as the fact the cards carry: stable vocabularies and identifiers, verbatim. */
    static String valueOf(Object value) {
        if (value == null) {
            return "";
        }
        return String.valueOf(value);
    }
}
