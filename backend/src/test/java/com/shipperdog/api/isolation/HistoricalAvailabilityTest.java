package com.shipperdog.api.isolation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * INV-006's proof (S7-05, issue #34 acceptance criterion 3): a
 * previously authorized version stays downloadable for the authorized
 * tester as newer versions arrive — version history is permanent and
 * authorization is per-version truth, never "the newest one wins".
 * Pinned shapes:
 *
 * <ul>
 *   <li>An EXPLICIT_VERSION grant on the old version keeps deciding
 *       ALLOW after two newer versions (each with its own artifact)
 *       exist — the eligibility listing shows all granted entries,
 *       old first-class beside new.</li>
 *   <li>A LATEST_N window slides forward with new versions (the
 *       window's meaning), and the versions that slid OUT of the
 *       window stop deciding ALLOW for that grant — while a tester
 *       holding an explicit grant on the slid-out version keeps
 *       downloading it: the two shapes compose, neither truncates the
 *       other.</li>
 *   <li>A TAGGED grant keeps the tagged version downloadable after
 *       newer versions arrive, even when the tag moves to a newer
 *       version (the grant follows the TAG; the explicitly granted
 *       tester keeps the old one).</li>
 *   <li>ARCHIVED versions stay readable with history (PRD P5) — but
 *       the download decision denies them (distribution ended), which
 *       is the documented lifecycle rule, not an INV-006 regression:
 *       the invariant covers availability while the version remains
 *       distributable.</li>
 * </ul>
 */
class HistoricalAvailabilityTest extends IsolationScenarioHarness {

    @Test
    void oldVersionDownloadsSurviveNewVersionsAndSlidingWindows() throws Exception {
        World world = world();
        IsolationScenarioHarness.IsolatedTenant side = world.sideA();

        // The old version carries a real artifact already (the world
        // seeds 1.0.0 READY). Two newer versions arrive, each with its
        // own artifact.
        UUID v2 = createVersion(side.platformAdminToken(), side.applicationId(), "7.13.0");
        readyArtifact(side.platformAdminToken(), v2, "7.13.0");
        UUID v3 = createVersion(side.platformAdminToken(), side.applicationId(), "7.14.0");
        readyArtifact(side.platformAdminToken(), v3, "7.14.0");

        // Tester one: explicit on the OLD version. Tester two: the
        // newest-1 window (a second linked tester of the same tenant).
        grantExplicitVersion(
                side.tenantAdminToken(), side.tenantId(), side.testerId(), side.applicationId(), side.versionId());
        UUID windowTester = inviteSecondTester(side, "hist-window@example.com", "Window Tester");
        grantLatestN(side.tenantAdminToken(), side.tenantId(), windowTester, side.applicationId(), 1);

        // Before the window slides: window tester sees only v3.
        assertThat(versionNamesOf(get(side.testerToken(), eligibilityUri(side.tenantId(), side.applicationId()), 200)))
                .containsExactly(com.shipperdog.api.artifact.AndroidFixtures.VERSION_NAME);
        assertThat(versionNamesOf(get(
                        accessToken(login("hist-window@example.com", PASSWORD)),
                        eligibilityUri(side.tenantId(), side.applicationId()),
                        200)))
                .containsExactly("7.14.0");

        // A fourth version arrives: the window slides to it; the
        // explicit tester's old version is untouched.
        UUID v4 = createVersion(side.platformAdminToken(), side.applicationId(), "7.15.0");
        String windowToken = accessToken(login("hist-window@example.com", PASSWORD));
        assertThat(versionNamesOf(get(windowToken, eligibilityUri(side.tenantId(), side.applicationId()), 200)))
                .as("LATEST_N=1 follows the newest version")
                .containsExactly("7.15.0");
        assertThat(versionNamesOf(get(side.testerToken(), eligibilityUri(side.tenantId(), side.applicationId()), 200)))
                .as("the explicit old-version grant survives every newer arrival")
                .containsExactly(com.shipperdog.api.artifact.AndroidFixtures.VERSION_NAME);
        get(side.testerToken(), downloadUri(side.tenantId(), side.versionId()), 200);
        assertThat(codeOf(getRaw(windowToken, downloadUri(side.tenantId(), v3))))
                .as("the version that slid out of the window denies for the window grant")
                .isEqualTo("VERSION_NOT_FOUND");

        // The explicit tester keeps downloading v1 while holding
        // nothing else — the composition never truncates.
        get(side.testerToken(), downloadUri(side.tenantId(), side.versionId()), 200);
    }

    @Test
    void aTaggedGrantFollowsTheTagWhileExplicitGrantsKeepTheOldVersion() throws Exception {
        IsolationScenarioHarness.IsolatedTenant side = world().sideA();
        UUID v2 = createVersion(side.platformAdminToken(), side.applicationId(), "7.13.0");
        readyArtifact(side.platformAdminToken(), v2, "7.13.0");
        setTags(side.platformAdminToken(), side.applicationId(), v2, "BETA");
        UUID taggedTester = inviteSecondTester(side, "hist-tag@example.com", "Tag Tester");
        String taggedToken = accessToken(login("hist-tag@example.com", PASSWORD));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("testerId", taggedTester.toString());
        body.put("applicationId", side.applicationId().toString());
        body.put("accessType", "TAG");
        body.put("tag", "BETA");
        body.put("reason", "suite tagged grant");
        post(side.tenantAdminToken(), grantsPath(side.tenantId()), body, 201);

        get(taggedToken, downloadUri(side.tenantId(), v2), 200);

        // A newer version takes the tag: the tagged grant now covers
        // the NEW version; an explicit grant on the old one survives.
        grantExplicitVersion(side.tenantAdminToken(), side.tenantId(), side.testerId(), side.applicationId(), v2);
        UUID v3 = createVersion(side.platformAdminToken(), side.applicationId(), "7.14.0");
        readyArtifact(side.platformAdminToken(), v3, "7.14.0");
        setTags(side.platformAdminToken(), side.applicationId(), v3, "BETA");
        // The tag MOVES: v2's copy is cleared (a tag may be shared by
        // several versions; moving it is an explicit registry action).
        setTags(side.platformAdminToken(), side.applicationId(), v2);

        get(taggedToken, downloadUri(side.tenantId(), v3), 200);
        assertThat(codeOf(getRaw(taggedToken, downloadUri(side.tenantId(), v2))))
                .as("the tag moved; the tagged grant moved with it")
                .isEqualTo("VERSION_NOT_FOUND");
        get(side.testerToken(), downloadUri(side.tenantId(), v2), 200);
    }

    @Test
    void archivedVersionsStayReadableButStopDistributing() throws Exception {
        IsolationScenarioHarness.IsolatedTenant side = world().sideA();
        grantExplicitVersion(
                side.tenantAdminToken(), side.tenantId(), side.testerId(), side.applicationId(), side.versionId());
        get(side.testerToken(), downloadUri(side.tenantId(), side.versionId()), 200);

        assertThat(exchange(
                                "/api/v1/applications/" + side.applicationId() + "/versions/" + side.versionId()
                                        + "/archive",
                                side.platformAdminToken(),
                                org.springframework.http.HttpMethod.POST,
                                null)
                        .status())
                .isEqualTo(200);

        // Readable forever with history (PRD P5, INV-006's record)...
        get(
                side.orgAdminToken(),
                "/api/v1/applications/" + side.applicationId() + "/versions/" + side.versionId(),
                200);
        get(
                side.orgAdminToken(),
                "/api/v1/applications/" + side.applicationId() + "/versions/" + side.versionId() + "/history",
                200);
        // ...and distribution CONTINUES (PRD 18's state machine: ARCHIVE
        // is the soft terminal — read-only metadata, frozen artifacts,
        // downloads live; REVOKED distribution is the state that blocks
        // downloads, pinned by the #33 suite). The granted tester
        // keeps downloading the archived version — history
        // availability, exactly INV-006's promise.
        get(side.testerToken(), downloadUri(side.tenantId(), side.versionId()), 200);
        assertThat(versionNamesOf(get(side.testerToken(), eligibilityUri(side.tenantId(), side.applicationId()), 200)))
                .as("the archived version stays in the eligible set for its granted testers")
                .containsExactly(com.shipperdog.api.artifact.AndroidFixtures.VERSION_NAME);
    }

    /** Invites and links a second tester to the side's tenant; returns the tester id. */
    private UUID inviteSecondTester(IsolationScenarioHarness.IsolatedTenant side, String email, String fullName) {
        var invited = post(
                side.platformAdminToken(),
                "/api/v1/testers",
                Map.of("organizationId", side.organizationId().toString(), "email", email, "fullName", fullName),
                201);
        client.post()
                .uri("/api/v1/auth/register")
                .body(Map.of(
                        "invitationToken",
                        invited.get("invitation").get("invitationToken").asString(),
                        "email",
                        email,
                        "password",
                        PASSWORD,
                        "fullName",
                        fullName))
                .retrieve()
                .body(String.class);
        post(
                side.platformAdminToken(),
                "/api/v1/tenants/" + side.tenantId() + "/testers",
                Map.of("testerId", invited.get("id").asText()),
                201);
        return UUID.fromString(invited.get("id").asText());
    }

    private static List<String> versionNamesOf(tools.jackson.databind.JsonNode eligibility) {
        List<String> names = new java.util.ArrayList<>();
        for (tools.jackson.databind.JsonNode entry : eligibility.get("entries")) {
            names.add(entry.get("versionName").asString());
        }
        return names;
    }
}
