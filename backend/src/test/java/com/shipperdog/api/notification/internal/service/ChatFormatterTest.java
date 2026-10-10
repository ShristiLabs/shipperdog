package com.shipperdog.api.notification.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shipperdog.api.notification.DomainEvent;
import com.shipperdog.api.notification.DomainEventName;
import com.shipperdog.api.notification.internal.bus.NotificationProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The chat formatters' unit (issue #52, S12-02, AC1/AC2/AC4): the
 * data-driven templates rendered into Slack Block Kit and the Teams
 * connector card, against recorded payload shapes -- no network, no
 * container, the payload members the OutboxRecorder actually writes
 * (the delivery suite proves the wire end-to-end). The entity-context
 * acceptance criterion is asserted here fact by fact: release, app,
 * ring, actor, and the blocker vocabulary on failures.
 */
class ChatFormatterTest {

    private static final String CONSOLE = "https://console.shipperdog.example";

    private final ObjectMapper wire = new ObjectMapper();

    private final SlackChatFormatter slack = new SlackChatFormatter(properties(CONSOLE));

    private final TeamsChatFormatter teams = new TeamsChatFormatter(properties(CONSOLE));

    private static NotificationProperties properties(String consoleBaseUrl) {
        return new NotificationProperties(
                true,
                Duration.ofSeconds(2),
                100,
                5,
                Duration.ofSeconds(2),
                5,
                Duration.ofSeconds(2),
                Duration.ofSeconds(5),
                false,
                consoleBaseUrl);
    }

    /** The named fact's value, or null when the card carries no such fact (map order is not a contract). */
    private static String factValue(JsonNode facts, String name) {
        for (JsonNode fact : facts) {
            if (name.equals(fact.get("name").asText())) {
                return fact.get("value").asText();
            }
        }
        return null;
    }

    private static DomainEvent eventOf(
            DomainEventName name, UUID applicationId, UUID releaseId, Map<String, Object> payload) {
        return new DomainEvent(
                UUID.randomUUID(),
                name,
                UUID.randomUUID(),
                null,
                applicationId,
                null,
                null,
                releaseId,
                "corr-123",
                payload,
                Instant.parse("2026-10-04T12:00:00Z"));
    }

    // AC1: Slack blocks -------------------------------------------------------------

    @Test
    void slackPromotionRendersBlocksFactsAndTheDeepLink() throws Exception {
        UUID app = UUID.randomUUID();
        UUID release = UUID.randomUUID();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("releaseRef", "Banking 7.12.0");
        payload.put("fromRing", "qa");
        payload.put("toRing", "beta");
        payload.put("tenants", java.util.List.of());
        payload.put("crossedToPromoted", false);

        JsonNode body = wire.readTree(slack.render(eventOf(DomainEventName.RELEASE_PROMOTED, app, release, payload)));

        // The fallback strip renders the title with the releaseRef.
        assertThat(body.get("text").asText()).isEqualTo("Release promoted — Banking 7.12.0");
        JsonNode blocks = body.get("blocks");
        assertThat(blocks.isArray()).isTrue();
        assertThat(blocks.get(0).get("type").asText()).isEqualTo("header");
        assertThat(blocks.get(0).get("text").get("text").asText()).isEqualTo("Release promoted — Banking 7.12.0");
        // The facts section: the entity context AC4 names.
        JsonNode fields = blocks.get(1).get("fields");
        assertThat(fields.toString()).contains("Release");
        assertThat(fields.toString()).contains("Banking 7.12.0");
        assertThat(fields.toString()).contains("From ring");
        assertThat(fields.toString()).contains("qa");
        assertThat(fields.toString()).contains("To ring");
        assertThat(fields.toString()).contains("beta");
        // The context strip: the event name and occurrence time.
        assertThat(blocks.get(2).get("elements").get(0).get("text").asText())
                .contains("RELEASE_PROMOTED")
                .contains("2026-10-04T12:00:00Z");
        // The deep link (AC1): the release page in the console.
        JsonNode button = blocks.get(3).get("elements").get(0);
        assertThat(button.get("url").asText()).isEqualTo(CONSOLE + "/releases/" + app + "/" + release);
        assertThat(button.get("text").get("text").asText()).isEqualTo("Open release");
    }

    @Test
    void slackFactsThePayloadLacksSimplyDoNotRender() throws Exception {
        // An approval event with no releaseRef in the payload (the
        // honest degradation the template design promises: no blanks).
        JsonNode body = wire.readTree(
                slack.render(eventOf(DomainEventName.APPROVAL_REQUESTED, null, UUID.randomUUID(), Map.of())));

        assertThat(body.get("text").asText()).isEqualTo("Approval requested");
        JsonNode blocks = body.get("blocks");
        // Header, then context -- no facts section for an empty fact set.
        assertThat(blocks.get(1).get("type").asText()).isEqualTo("context");
        // No application context: the deep link degrades to the
        // notifications page, never a link with null ids baked in.
        JsonNode actions = blocks.get(2);
        assertThat(actions.get("type").asText()).isEqualTo("actions");
        assertThat(actions.get("elements").get(0).get("url").asText()).isEqualTo(CONSOLE + "/notifications");
    }

    // AC2: the Teams connector card ---------------------------------------------------

    @Test
    void teamsCardRendersFactsThemeAndOpenUri() throws Exception {
        UUID app = UUID.randomUUID();
        UUID release = UUID.randomUUID();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("releaseRef", "Banking 7.12.0");
        payload.put("roundNumber", 2);
        payload.put("position", 1);
        payload.put("approvalType", "QA");

        JsonNode card = wire.readTree(teams.render(eventOf(DomainEventName.APPROVAL_REQUESTED, app, release, payload)));

        assertThat(card.get("@type").asText()).isEqualTo("MessageCard");
        assertThat(card.get("@context").asText()).isEqualTo("http://schema.org/extensions");
        assertThat(card.get("summary").asText()).isEqualTo("Approval requested — Banking 7.12.0");
        assertThat(card.get("themeColor").asText()).isEqualTo("0078D7");
        JsonNode section = card.get("sections").get(0);
        assertThat(section.get("activityTitle").asText()).isEqualTo("Approval requested — Banking 7.12.0");
        JsonNode facts = section.get("facts");
        assertThat(factValue(facts, "Release")).isEqualTo("Banking 7.12.0");
        assertThat(factValue(facts, "Approval")).isEqualTo("QA");
        assertThat(factValue(facts, "Round")).isEqualTo("2");
        assertThat(factValue(facts, "Position")).isEqualTo("1");
        assertThat(factValue(facts, "Blocker")).isNull();
        JsonNode action = section.get("potentialAction").get(0);
        assertThat(action.get("@type").asText()).isEqualTo("OpenUri");
        assertThat(action.get("targets").get(0).get("url").asText())
                .isEqualTo(CONSOLE + "/releases/" + app + "/" + release);
    }

    // AC4: entity context, blockers on failures -----------------------------------------

    @Test
    void failureEventsRenderTheirBlockersFirstClass() throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("platform", "IOS");
        payload.put("outcome", "FAILED");
        payload.put("failureCode", "INFOPLIST_MISSING");
        UUID app = UUID.randomUUID();

        JsonNode slackBody = wire.readTree(slack.render(eventOf(DomainEventName.ARTIFACT_FAILED, app, null, payload)));
        assertThat(slackBody.get("text").asText()).isEqualTo("Artifact failed validation");
        assertThat(slackBody.get("blocks").get(1).get("fields").toString())
                .contains("Blocker")
                .contains("INFOPLIST_MISSING");

        JsonNode teamsCard = wire.readTree(teams.render(eventOf(DomainEventName.ARTIFACT_FAILED, app, null, payload)));
        assertThat(teamsCard.get("themeColor").asText()).isEqualTo("CC0000");
        assertThat(teamsCard.get("sections").get(0).get("facts").toString()).contains("INFOPLIST_MISSING");
    }

    @Test
    void theActorFactRendersForEventsThatCarryOne() throws Exception {
        UUID tester = UUID.randomUUID();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("platform", "ANDROID");
        payload.put("testerUserId", tester.toString());
        payload.put("releaseRef", "Banking 7.12.1");

        JsonNode card = wire.readTree(teams.render(
                eventOf(DomainEventName.TESTER_DOWNLOADED, UUID.randomUUID(), UUID.randomUUID(), payload)));
        assertThat(factValue(card.get("sections").get(0).get("facts"), "Actor")).isEqualTo(tester.toString());
    }

    @Test
    void everyNameInTheVocabularyRendersThroughBothFormatters() throws Exception {
        // The completeness guard: every wired name (and the test name)
        // has a template and renders valid provider JSON for a minimal
        // event -- a name arriving with its owning story can never
        // ship a payload shape the formatters cannot render; it just
        // renders the default partial until its template lands.
        for (DomainEventName name : DomainEventName.values()) {
            DomainEvent event = eventOf(name, UUID.randomUUID(), UUID.randomUUID(), Map.of("releaseRef", "R 1.0.0"));
            JsonNode slackBody = wire.readTree(slack.render(event));
            assertThat(slackBody.get("text").asText())
                    .as("slack renders %s", name)
                    .isNotEmpty();
            assertThat(slackBody.get("blocks").isArray())
                    .as("slack blocks for %s", name)
                    .isTrue();
            JsonNode teamsCard = wire.readTree(teams.render(event));
            assertThat(teamsCard.get("@type").asText())
                    .as("teams card for %s", name)
                    .isEqualTo("MessageCard");
            assertThat(teamsCard.get("sections").isArray())
                    .as("teams sections for %s", name)
                    .isTrue();
        }
    }

    // The test message (AC3) ---------------------------------------------------------------

    @Test
    void theTestMessageRendersAsTheTestTemplateThroughBothFormatters() throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("channel", "SLACK");
        payload.put("subscriptionId", "sub-id");
        DomainEvent event = new DomainEvent(
                UUID.randomUUID(),
                DomainEventName.NOTIFICATION_TEST_SENT,
                UUID.randomUUID(),
                null,
                null,
                null,
                null,
                null,
                null,
                payload,
                Instant.parse("2026-10-04T12:00:00Z"));

        JsonNode slackBody = wire.readTree(slack.render(event));
        assertThat(slackBody.get("text").asText()).isEqualTo("ShipperDog test notification");
        assertThat(slackBody.get("blocks").toString()).contains("Channel").contains("SLACK");
        assertThat(slackBody.get("blocks").toString()).contains(CONSOLE + "/notifications");

        JsonNode teamsCard = wire.readTree(teams.render(event));
        assertThat(teamsCard.get("summary").asText()).isEqualTo("ShipperDog test notification");
        assertThat(teamsCard
                        .get("sections")
                        .get(0)
                        .get("potentialAction")
                        .get(0)
                        .get("name")
                        .asText())
                .isEqualTo("Open notifications");
    }
}
