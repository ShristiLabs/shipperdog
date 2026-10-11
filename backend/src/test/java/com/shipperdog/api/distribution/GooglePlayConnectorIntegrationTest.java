package com.shipperdog.api.distribution;

import static org.assertj.core.api.Assertions.assertThat;

import com.shipperdog.api.artifact.ArtifactStore;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.distribution.internal.google.GooglePlayDeveloperApiMock;
import com.shipperdog.api.distribution.internal.service.StoreStatusEventConsumer;
import com.shipperdog.api.identity.AuditCapture;
import com.shipperdog.api.integration.StoreStatusEventMapped;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The Google Play connector's end-to-end proof (issue #64, S13-04):
 * the first Android adapter on the SPI, run entirely against the
 * contract-faithful mock Play server — the service-account RS256
 * OAuth2 conversation with rotation (AC1), the two-phase AAB upload
 * to the ring-mapped track with the frozen release notes and the
 * track release's external id (AC2), the AAB-only format rule at the
 * adapter boundary (AC3), the tester email-list sync whose adds AND
 * removes both reflect (AC4), the track-status journey through the
 * refresh legs and the mapped-event receiver (AC5), and Google's
 * rate limits answered by the sandbox's retried backoff with the
 * provider-grain quota telemetry (AC6). NO live Google call anywhere:
 * the connector's base URL points at the mock, whose own assertion
 * verification is the auth model's proof.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
            "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef"
        })
@TestPropertySource(properties = "shipperdog.distribution.adapters.play.retry-backoff=PT0.05S")
@Testcontainers
@org.springframework.context.annotation.Import(com.shipperdog.api.identity.AuditCapture.class)
class GooglePlayConnectorIntegrationTest {

    private static final String DEV_ADMIN_EMAIL = "dev@shipperdog.local";
    private static final String DEV_ADMIN_PASSWORD = "shipperdog-dev-admin";
    private static final String PASSWORD = "a-perfectly-valid-password";

    /** The mock Play server: Google's shapes, the mock's key, the mock's verdicts. */
    static final GooglePlayDeveloperApiMock PLAY = new GooglePlayDeveloperApiMock();

    /** The compose GCS emulator image (ADR-0003): the artifact bytes' object storage. */
    @Container
    static final GenericContainer<?> GCS = new GenericContainer<>(
                    DockerImageName.parse("fsouza/fake-gcs-server:1.56.1"))
            .withCommand("-scheme", "http", "-host", "0.0.0.0", "-port", "4443")
            .withExposedPorts(4443);

    @DynamicPropertySource
    static void connector(DynamicPropertyRegistry registry) {
        registry.add("shipperdog.distribution.google-play.base-url", PLAY::baseUrl);
        registry.add(
                "shipperdog.artifact.storage.host", () -> "http://" + GCS.getHost() + ":" + GCS.getMappedPort(4443));
    }

    @AfterAll
    static void stopMock() {
        PLAY.stop();
    }

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));

    @LocalServerPort
    int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AuditCapture audit;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private ArtifactStore artifactStorage;

    @Autowired
    private StoreStatusEventConsumer storeEvents;

    @Autowired
    private io.micrometer.core.instrument.MeterRegistry meters;

    private RestClient client;

    private String slug;
    private UUID org;
    private String adminToken;
    private String ownerToken;
    private String managerToken;
    private UUID tenantA;
    private UUID app;
    private String packageName;
    private UUID pilotGroup;
    private UUID testerOne;
    private UUID testerTwo;
    private int versionCounter;
    private byte[] aabBytes;
    private String boundMaterial;

    @BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
        audit.reset();
        PLAY.reset();

        slug = "play-conn-" + UUID.randomUUID().toString().substring(0, 8);
        org = createOrg("Play Corp " + slug);
        adminToken = loginOfOrgRoleIn(org, slug + "-admin", "ORG_ADMIN");
        ownerToken = loginOfOrgRoleIn(org, slug + "-owner", "APP_OWNER");
        managerToken = loginOfOrgRoleIn(org, slug + "-manager", "RELEASE_MANAGER");
        tenantA = createTenant(adminToken, org, "Play Bank", "crm-" + slug);
        packageName = "com." + slug.replace("-", "") + ".banking";
        app = createAndroidApp(adminToken, tenantA, "Play Banking", packageName);
        pilotGroup = groupIdByName(tenantA, "Pilot");
        testerOne = activeTester(adminToken, org, "pilot-one@" + slug + ".example.com", "Pilot One");
        testerTwo = activeTester(adminToken, org, "pilot-two@" + slug + ".example.com", "Pilot Two");
        linkTesterToTenant(adminToken, tenantA, testerOne);
        linkTesterToTenant(adminToken, tenantA, testerTwo);
        post(
                adminToken,
                "/api/v1/tester-groups/" + pilotGroup + "/members",
                Map.of("testerId", testerOne.toString()),
                201);
        post(
                adminToken,
                "/api/v1/tester-groups/" + pilotGroup + "/members",
                Map.of("testerId", testerTwo.toString()),
                201);

        // The mock's Play state: the app of the package name, and the
        // org-stored service account (the whole JSON document as the
        // binding's material — no metadata convention).
        PLAY.registerApp(packageName);
        boundMaterial = PLAY.serviceAccountJson();
        bindGooglePlayCredential(adminToken, org, null, null, "Org Play Account", boundMaterial);
        aabBytes = ("mock-aab-bytes-" + slug).getBytes(StandardCharsets.UTF_8);
    }

    // -----------------------------------------------------------------------
    // AC1: service-account auth, delegation, rotation
    // -----------------------------------------------------------------------

    @Test
    void aWrongServiceAccountIsAuthenticationFailedAndNeverRetried() {
        linkPilotToInternal();
        // A document the mock does not trust (a foreign client email):
        // the token exchange's 400 invalid_grant is AUTHENTICATION —
        // retrying a rejected credential is a lockout generator.
        bindGooglePlayCredential(
                adminToken, org, null, null, "Org Play Wrong Client", withClientEmail("someone-else@example.com"));
        UUID release = releaseWalkedToActive(playEnvironment(null), "qa");
        JsonNode row = recordOf(release);
        assertThat(row.get("status").asString()).isEqualTo("FAILED");
        assertThat(row.get("failureCode").asString()).isEqualTo("ADAPTER_AUTHENTICATION_FAILED");
        assertThat(row.get("attempts").asInt()).isEqualTo(1);
        // The crossing still committed: store failures never fail a release.
        assertThat(jdbc.queryForObject("SELECT status FROM releases WHERE id = ?", String.class, release))
                .isEqualTo("ACTIVE");
    }

    @Test
    void anEnvironmentLevelOverrideServesTheDispatchThroughTheChain() {
        linkPilotToInternal();
        UUID environment = playEnvironment(null);
        // The delegation matrix under a REAL store dispatch: an app+env
        // google-play row with a WRONG client email wins over the org
        // row — the AUTHENTICATION that follows is proof the override
        // was the credential the connector signed with.
        bindGooglePlayCredential(
                adminToken,
                org,
                app,
                environment,
                "Env Play Wrong Client",
                withClientEmail("env-someone-else@example.com"));
        UUID release = releaseWalkedToActive(environment, "qa");
        JsonNode row = recordOf(release);
        assertThat(row.get("status").asString()).isEqualTo("FAILED");
        assertThat(row.get("failureCode").asString()).isEqualTo("ADAPTER_AUTHENTICATION_FAILED");
    }

    @Test
    void aRotatedServiceAccountMintsFreshAndTheOldCacheEntryNeverAnswers() {
        linkPilotToInternal();
        UUID first = releaseWalkedToActive(playEnvironment(null), "qa");
        assertThat(recordOf(first).get("status").asString()).isEqualTo("DISTRIBUTING");

        // Rotation: a NEW service-account document (a different key by
        // construction) replaces the org row's reference; the token
        // cache's old entry is keyed on the old digest, so the next
        // dispatch mints fresh against the new key and succeeds.
        bindGooglePlayCredential(adminToken, org, null, null, "Org Play Rotated", PLAY.serviceAccountJson());
        UUID second = releaseWalkedToActive(playEnvironment(null), "qa");
        assertThat(recordOf(second).get("status").asString()).isEqualTo("DISTRIBUTING");
        assertThat(recordOf(second).get("failureCode").isNull()).isTrue();
    }

    // -----------------------------------------------------------------------
    // AC2: the two-phase upload, the ring mapping, the frozen notes
    // -----------------------------------------------------------------------

    @Test
    void activeCrossingUploadsTheAabToTheInternalTrackWithFrozenNotesAndRecordsTheExternalId() {
        linkPilotToInternal();
        authorReleaseNotes("en-US", "Bug fixes and stability improvements.");
        UUID release = releaseWalkedToActive(playEnvironment(null), "qa");

        JsonNode row = recordOf(release);
        assertThat(row.get("channel").asString()).isEqualTo("play");
        assertThat(row.get("status").asString()).isEqualTo("DISTRIBUTING");
        assertThat(row.get("externalId").asString()).isEqualTo("internal#1#" + packageName);
        assertThat(row.get("failureCode").isNull()).isTrue();
        // The internal track serves immediately (no review — the story's
        // own note): Google's own status word rides the record.
        assertThat(row.get("providerStatus").asString()).isEqualTo("completed");

        // The wire: the media upload carried the artifact's stored bytes,
        // the track write carried the frozen notes, the edit committed.
        assertThat(PLAY.uploads()).hasSize(1);
        assertThat(PLAY.uploads().get(0).sizeBytes()).isEqualTo(aabBytes.length);
        List<GooglePlayDeveloperApiMock.RecordedCall> trackWrites = PLAY.callsOf("PUT", "/tracks/internal");
        assertThat(trackWrites).hasSize(1);
        assertThat(trackWrites.get(0).body()).contains("Bug fixes and stability improvements.");
        assertThat(PLAY.callsOf("POST", ":commit")).hasSize(1);

        // The dispatch is audited (the framework's own event).
        assertThat(audit.eventsOfType(AuditEventType.DISTRIBUTION_DISPATCHED)).hasSize(1);
    }

    @Test
    void theEnvironmentOverrideMapsTheRingToTheClosedTrackAndTheReviewJourneySurfaces() {
        linkPilotTo("alpha");
        UUID environment = playEnvironment("alpha");
        UUID release = releaseWalkedToActive(environment, "qa");
        JsonNode row = recordOf(release);
        assertThat(row.get("externalId").asString()).isEqualTo("alpha#1#" + packageName);
        // The closed track holds the release in review at the commit.
        assertThat(row.get("providerStatus").asString()).isEqualTo("inProgress");

        UUID recordId = UUID.fromString(row.get("id").asText());
        String refreshUri = "/api/v1/distribution/records/" + recordId + "/refresh?organizationId=" + org;
        // Still in review: DISTRIBUTING, Google owns the next step.
        JsonNode reviewing = post(managerToken, refreshUri, Map.of(), 200);
        assertThat(reviewing.get("status").asString()).isEqualTo("DISTRIBUTING");
        assertThat(reviewing.get("providerStatus").asString()).isEqualTo("inProgress");
        // The review completes: the record walks to DISTRIBUTED.
        PLAY.setTrackReleaseStatus(packageName, "alpha", 1, "completed");
        JsonNode completed = post(managerToken, refreshUri, Map.of(), 200);
        assertThat(completed.get("status").asString()).isEqualTo("DISTRIBUTED");
        assertThat(completed.get("providerStatus").asString()).isEqualTo("completed");
        assertThat(completed.get("distributedAt").isNull()).isFalse();
        assertThat(audit.eventsOfType(AuditEventType.DISTRIBUTION_RECORD_REFRESHED))
                .hasSize(2);
    }

    @Test
    void theUatRingsDefaultMappingIsTheClosedTrack() {
        linkPilotTo("alpha");
        UUID release = releaseWalkedToActive(playEnvironment(null), "uat");
        assertThat(recordOf(release).get("externalId").asString()).isEqualTo("alpha#1#" + packageName);
    }

    @Test
    void theFrozenNotesAreSentNotTheLiveRows() {
        linkPilotToInternal();
        authorReleaseNotes("en-US", "The frozen text.");
        UUID release = releaseWalkedToDeploying(playEnvironment(null), "qa");
        // An edit AFTER the APPROVED freeze: the authoring row moves,
        // the manifest's copy does not.
        authorReleaseNotes("en-US", "The edited live text.");
        post(
                managerToken,
                "/api/v1/releases/" + release + "/transitions",
                Map.of("applicationId", app.toString(), "to", "ACTIVE"),
                200);
        List<GooglePlayDeveloperApiMock.RecordedCall> writes = PLAY.callsOf("PUT", "/tracks/internal");
        assertThat(writes).hasSize(1);
        assertThat(writes.get(0).body()).contains("The frozen text.");
        assertThat(writes.get(0).body()).doesNotContain("The edited live text.");
    }

    // -----------------------------------------------------------------------
    // AC3: the format rule at the adapter boundary
    // -----------------------------------------------------------------------

    @Test
    void apkArtifactsAreRejectedForThePlayChannelAtDistributionTime() {
        linkPilotToInternal();
        UUID environment = playEnvironment(null);
        PLAY.clearCalls();
        UUID release = releaseWalkedToActiveWithFormat(environment, "qa", "APK");
        JsonNode row = recordOf(release);
        assertThat(row.get("status").asString()).isEqualTo("FAILED");
        assertThat(row.get("failureCode").asString()).isEqualTo("PLAY_AAB_REQUIRED");
        assertThat(row.get("failureDetail").asString()).contains("AAB");
        // The crossing still committed and the wire stayed quiet: no
        // edit, no upload, no track write.
        assertThat(jdbc.queryForObject("SELECT status FROM releases WHERE id = ?", String.class, release))
                .isEqualTo("ACTIVE");
        assertThat(PLAY.callsOf("POST", "/edits")).isEmpty();
        assertThat(PLAY.uploads()).isEmpty();
    }

    @Test
    void aProductionMappingIsRefusedLoudlyAsTheNextStorysSurface() {
        linkPilotToInternal();
        UUID environment = playEnvironment("production");
        PLAY.clearCalls();
        UUID release = releaseWalkedToActive(environment, "qa");
        JsonNode row = recordOf(release);
        assertThat(row.get("status").asString()).isEqualTo("FAILED");
        assertThat(row.get("failureCode").asString()).isEqualTo("ADAPTER_FAILED");
        assertThat(row.get("failureDetail").asString()).contains("S13-05");
        assertThat(PLAY.callsOf("POST", "/edits")).isEmpty();
    }

    @Test
    void anUnmappedRingWithoutAnOverrideIsTheMappingGap() {
        linkPilotToInternal();
        // An organization ring the default mapping does not serve: the
        // loud PERMANENT mapping gap, never a silent default.
        post(
                adminToken,
                "/api/v1/rings",
                Map.of("organizationId", org.toString(), "code", "canary", "name", "Canary", "position", 3),
                201);
        UUID environment = playEnvironment(null);
        PLAY.clearCalls();
        UUID release = releaseWalkedToActiveWithRing(environment, "canary");
        JsonNode row = recordOf(release);
        assertThat(row.get("status").asString()).isEqualTo("FAILED");
        assertThat(row.get("failureDetail").asString()).contains("canary");
        assertThat(PLAY.callsOf("POST", "/edits")).isEmpty();
    }

    @Test
    void aReleaseWithoutGroupLinksOnTheTrackFailsLoudly() {
        // The Pilot group exists but was never linked to the play track.
        UUID release = releaseWalkedToActive(playEnvironment(null), "qa");
        JsonNode row = recordOf(release);
        assertThat(row.get("status").asString()).isEqualTo("FAILED");
        assertThat(row.get("failureCode").asString()).isEqualTo("PLAY_GROUPS_UNMAPPED");
        assertThat(PLAY.callsOf("POST", "/edits")).isEmpty();
    }

    @Test
    void anUnknownPackageFailsItsRecordsWithGooglesOwnWords() {
        linkPilotToInternal();
        // The artifact's extracted package name names an app the
        // service account does not know: Google's own 404 message is
        // the actionable detail.
        UUID environment = playEnvironment(null);
        UUID release = releaseWalkedToActiveWithBundle(environment, "com.unknown.package");
        JsonNode row = recordOf(release);
        assertThat(row.get("status").asString()).isEqualTo("FAILED");
        assertThat(row.get("failureCode").asString()).isEqualTo("ADAPTER_FAILED");
        assertThat(row.get("failureDetail").asString()).contains("Application not found.");
    }

    // -----------------------------------------------------------------------
    // AC4: the tester email-list sync
    // -----------------------------------------------------------------------

    @Test
    void testerGroupSyncsAddAndRemoveBothReflectOnTheTrackEmailList() {
        JsonNode linked = linkPilotToInternal();
        UUID linkId = UUID.fromString(linked.get("id").asText());
        // The link lands and lists.
        JsonNode groups = get(adminToken, "/api/v1/distribution/play/groups?organizationId=" + org, 200);
        assertThat(groups.get("items").size()).isEqualTo(1);
        assertThat(groups.get("items").get(0).get("externalGroupId").asString()).isEqualTo("internal");

        // The first sync pushes both members.
        JsonNode first = post(
                adminToken,
                "/api/v1/distribution/play/groups/" + linkId + "/sync?organizationId=" + org,
                Map.of(),
                200);
        assertThat(first.get("addedEmails").toString()).contains("pilot-one@").contains("pilot-two@");
        assertThat(first.get("removedEmails").isEmpty()).isTrue();
        assertThat(PLAY.testerEmailsOf(packageName, "internal"))
                .containsExactlyInAnyOrder("pilot-one@" + slug + ".example.com", "pilot-two@" + slug + ".example.com");

        // Idempotent: a second sync changes nothing.
        JsonNode second = post(
                adminToken,
                "/api/v1/distribution/play/groups/" + linkId + "/sync?organizationId=" + org,
                Map.of(),
                200);
        assertThat(second.get("addedEmails").isEmpty()).isTrue();
        assertThat(second.get("removedEmails").isEmpty()).isTrue();

        // A removal in ShipperDog reflects on the track (AC4's remove
        // half — the Play split of TestFlight's never-remove doctrine).
        exchange("/api/v1/tester-groups/" + pilotGroup + "/members/" + testerTwo, adminToken, "DELETE", null)
                .expect(204);
        JsonNode third = post(
                adminToken,
                "/api/v1/distribution/play/groups/" + linkId + "/sync?organizationId=" + org,
                Map.of(),
                200);
        assertThat(third.get("removedEmails").toString()).contains("pilot-two@");
        assertThat(PLAY.testerEmailsOf(packageName, "internal")).containsExactly("pilot-one@" + slug + ".example.com");
        assertThat(audit.eventsOfType(AuditEventType.DISTRIBUTION_GROUP_SYNCED)).isNotEmpty();
    }

    @Test
    void theLinkWriteValidatesTheTrackAndRefusesProductionAndUnknownTracks() {
        put(
                adminToken,
                "/api/v1/distribution/play/groups",
                Map.of(
                        "organizationId", org.toString(),
                        "applicationId", app.toString(),
                        "groupId", pilotGroup.toString(),
                        "track", "production"),
                400);
        put(
                adminToken,
                "/api/v1/distribution/play/groups",
                Map.of(
                        "organizationId", org.toString(),
                        "applicationId", app.toString(),
                        "groupId", pilotGroup.toString(),
                        "track", "does-not-exist"),
                400);
        // The credential chain's own miss answers its own problem.
        JsonNode credentials = get(adminToken, "/api/v1/distribution/credentials?organizationId=" + org, 200);
        UUID credentialId =
                UUID.fromString(credentials.get("items").get(0).get("id").asText());
        exchange(
                        "/api/v1/distribution/credentials/" + credentialId + "?organizationId=" + org,
                        adminToken,
                        "DELETE",
                        null)
                .expect(204);
        JsonNode problem = put(
                adminToken,
                "/api/v1/distribution/play/groups",
                Map.of(
                        "organizationId", org.toString(),
                        "applicationId", app.toString(),
                        "groupId", pilotGroup.toString(),
                        "track", "internal"),
                404);
        assertThat(problem.get("code").asString()).isEqualTo("DISTRIBUTION_CREDENTIAL_NOT_FOUND");
    }

    // -----------------------------------------------------------------------
    // AC5: the status journey through refresh + the receiver event
    // -----------------------------------------------------------------------

    @Test
    void aFailedTrackReleaseFailsItsRecordWithTheHonestCode() {
        linkPilotToInternal();
        UUID release = releaseWalkedToActive(playEnvironment(null), "qa");
        UUID recordId = UUID.fromString(recordOf(release).get("id").asText());
        PLAY.setTrackReleaseStatus(packageName, "internal", 1, "failed");
        JsonNode failed = post(
                managerToken,
                "/api/v1/distribution/records/" + recordId + "/refresh?organizationId=" + org,
                Map.of(),
                200);
        assertThat(failed.get("status").asString()).isEqualTo("FAILED");
        assertThat(failed.get("failureCode").asString()).isEqualTo("PLAY_RELEASE_FAILED");
        assertThat(failed.get("providerStatus").asString()).isEqualTo("failed");
    }

    @Test
    void aMappedPlayEventRefreshesTheWalkingPlayRecordsAndOnlyThose() {
        linkPilotToInternal();
        UUID playRelease = releaseWalkedToActive(playEnvironment(null), "qa");
        UUID recordId = UUID.fromString(recordOf(playRelease).get("id").asText());
        // A walking record of ANOTHER channel the event must not touch.
        jdbc.update(
                "INSERT INTO channel_distributions (id, release_id, artifact_id, organization_id, channel, status,"
                        + " external_id, attempts, created_at, updated_at) VALUES (?::uuid, ?::uuid, ?::uuid, ?::uuid,"
                        + " 'testflight', 'DISTRIBUTING', 'build-1', 1, now(), now())",
                UUID.randomUUID(),
                playRelease,
                jdbc.queryForObject("SELECT artifact_id FROM channel_distributions WHERE id = ?", UUID.class, recordId),
                org);

        PLAY.setTrackReleaseStatus(packageName, "internal", 1, "failed");
        storeEvents.apply(new StoreStatusEventMapped("google-play", "test", null, java.time.Instant.now()));

        // The play record of the release (the testflight twin the
        // fixture inserted rides beside it).
        JsonNode after = get(
                        managerToken,
                        "/api/v1/distribution/records?organizationId=" + org + "&releaseId=" + playRelease,
                        200)
                .get("items")
                .get(0);
        // One play record of the release: the event refreshed it (the
        // provider's own failed word landed).
        assertThat(after.get("providerStatus").asString()).isEqualTo("failed");
        Integer testflightTouched = jdbc.queryForObject(
                "SELECT count(*) FROM channel_distributions WHERE channel = 'testflight' AND provider_status"
                        + " IS NOT NULL",
                Integer.class);
        assertThat(testflightTouched).isZero();
        assertThat(audit.eventsOfType(AuditEventType.STORE_EVENT_APPLIED)).isNotEmpty();
    }

    @Test
    void theTrackModelListingServesTheConsoleRead() {
        linkPilotToInternal();
        releaseWalkedToActive(playEnvironment(null), "qa");
        JsonNode tracks = get(
                adminToken, "/api/v1/distribution/play/tracks?organizationId=" + org + "&applicationId=" + app, 200);
        List<JsonNode> names = new java.util.ArrayList<>();
        tracks.get("tracks").forEach(track -> names.add(track.get("track")));
        assertThat(names.toString()).contains("internal").contains("alpha").contains("production");
        JsonNode internal = null;
        for (JsonNode track : tracks.get("tracks")) {
            if ("internal".equals(track.get("track").asString())) {
                internal = track;
            }
        }
        assertThat(internal).isNotNull();
        assertThat(internal.get("releases").size()).isEqualTo(1);
        assertThat(internal.get("releases").get(0).get("name").asString()).contains(".");
        assertThat(internal.get("releases").get(0).get("status").asString()).isEqualTo("completed");
    }

    // -----------------------------------------------------------------------
    // AC6: quota, backoff, telemetry; the authoring surface
    // -----------------------------------------------------------------------

    @Test
    void rateLimitedCallsAreRetriedWithBackoffAndCounted() {
        linkPilotToInternal();
        UUID environment = playEnvironment(null);
        double before = meters.counter(
                        com.shipperdog.api.config.MetricNames.ADAPTER_RATE_LIMIT, "provider", "google-play")
                .count();
        PLAY.throttleNextCall();
        UUID release = releaseWalkedToActive(environment, "qa");
        JsonNode row = recordOf(release);
        assertThat(row.get("status").asString()).isEqualTo("DISTRIBUTING");
        assertThat(row.get("attempts").asInt()).isEqualTo(2);
        double after = meters.counter(
                        com.shipperdog.api.config.MetricNames.ADAPTER_RATE_LIMIT, "provider", "google-play")
                .count();
        assertThat(after - before).isGreaterThanOrEqualTo(1.0);
    }

    @Test
    void aTokenEndpointServerErrorIsTransientAndTheRetryLands() {
        linkPilotToInternal();
        UUID environment = playEnvironment(null);
        // The taxonomy boundary (the reviewer's finding): the token
        // ENDPOINT's own 500 is TRANSIENT — the sandbox retries and
        // the dispatch walks on; only a refused ASSERTION is
        // AUTHENTICATION. A FRESH binding after the link guarantees
        // the dispatch actually exchanges (the link's dial cached the
        // previous document's token).
        bindGooglePlayCredential(adminToken, org, null, null, "Org Play Fresh Token", PLAY.serviceAccountJson());
        PLAY.failNextTokenServerSide();
        UUID release = releaseWalkedToActive(environment, "qa");
        JsonNode row = recordOf(release);
        assertThat(row.get("status").asString()).isEqualTo("DISTRIBUTING");
        assertThat(row.get("attempts").asInt()).isEqualTo(2);
    }

    @Test
    void aMissingStoredObjectIsTransientAndExhaustsItsAttempts() {
        linkPilotToInternal();
        UUID environment = playEnvironment(null);
        // The byte source's own fault (the stored object is absent):
        // TRANSIENT per attempt, exhausted to the record's honest
        // verdict with the actionable detail.
        UUID release = releaseWalkedToActiveWithoutObject(environment, "qa");
        JsonNode row = recordOf(release);
        assertThat(row.get("status").asString()).isEqualTo("FAILED");
        assertThat(row.get("attempts").asInt()).isEqualTo(3);
        assertThat(row.get("failureDetail").asString()).contains("stored bytes");
    }

    @Test
    void exhaustedRateLimitsFailTheRecordWithTheTaxonomyCodeAndCountEveryHit() {
        linkPilotToInternal();
        UUID environment = playEnvironment(null);
        double before = meters.counter(
                        com.shipperdog.api.config.MetricNames.ADAPTER_RATE_LIMIT, "provider", "google-play")
                .count();
        PLAY.throttleNextCalls(3);
        UUID release = releaseWalkedToActive(environment, "qa");
        JsonNode row = recordOf(release);
        assertThat(row.get("status").asString()).isEqualTo("FAILED");
        assertThat(row.get("failureCode").asString()).isEqualTo("ADAPTER_RATE_LIMITED");
        assertThat(row.get("attempts").asInt()).isEqualTo(3);
        double after = meters.counter(
                        com.shipperdog.api.config.MetricNames.ADAPTER_RATE_LIMIT, "provider", "google-play")
                .count();
        assertThat(after - before).isGreaterThanOrEqualTo(3.0);
    }

    @Test
    void theAuthoringSurfaceValidatesItsShapeAndRotatesTheSameLocale() {
        // Google's own cap enforced author-side: a frozen block can
        // never carry a value the provider would refuse.
        put(
                adminToken,
                "/api/v1/distribution/play/release-notes",
                Map.of(
                        "organizationId", org.toString(),
                        "applicationId", app.toString(),
                        "locale", "en-US",
                        "notes", "x".repeat(501)),
                400);
        put(
                adminToken,
                "/api/v1/distribution/play/release-notes",
                Map.of(
                        "organizationId",
                        org.toString(),
                        "applicationId",
                        app.toString(),
                        "locale",
                        "not-a-locale",
                        "notes",
                        "text"),
                400);
        // A second write to the same locale replaces the row whole.
        authorReleaseNotes("en-US", "First text.");
        authorReleaseNotes("en-US", "Second text.");
        JsonNode notes = get(adminToken, "/api/v1/distribution/play/release-notes?organizationId=" + org, 200);
        assertThat(notes.get("items").size()).isEqualTo(1);
        assertThat(notes.get("items").get(0).get("notes").asString()).isEqualTo("Second text.");
        // The delete removes the row.
        UUID notesId = UUID.fromString(notes.get("items").get(0).get("id").asText());
        exchange(
                        "/api/v1/distribution/play/release-notes/" + notesId + "?organizationId=" + org,
                        adminToken,
                        "DELETE",
                        null)
                .expect(204);
        assertThat(get(adminToken, "/api/v1/distribution/play/release-notes?organizationId=" + org, 200)
                        .get("items")
                        .size())
                .isZero();
    }

    // -----------------------------------------------------------------------
    // Cross-cutting: secrets, concealment, configuration faults
    // -----------------------------------------------------------------------

    @Test
    void theKeyMaterialNeverSurfacesInAuditAnswersOrRecords() {
        linkPilotToInternal();
        UUID release = releaseWalkedToActive(playEnvironment(null), "qa");
        UUID recordId = UUID.fromString(recordOf(release).get("id").asText());
        post(
                managerToken,
                "/api/v1/distribution/records/" + recordId + "/refresh?organizationId=" + org,
                Map.of(),
                200);

        // The private key's own base64 body (a distinctive fragment of
        // the generated key) appears in NO audit event, NO record view,
        // and NO authoring answer of the whole flow.
        String needle;
        try {
            String privateKey =
                    mapper.readTree(boundMaterial).path("private_key").asString();
            needle = privateKey
                    .replaceAll("-----[A-Z ]+-----", "")
                    .replaceAll("\\s", "")
                    .substring(40, 90);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        for (com.shipperdog.api.audit.AuditEvent event : audit.events()) {
            assertThat(event.toString()).doesNotContain(needle);
        }
        JsonNode records =
                get(managerToken, "/api/v1/distribution/records?organizationId=" + org + "&releaseId=" + release, 200);
        assertThat(records.toString()).doesNotContain(needle);
        Integer materialColumns = jdbc.queryForObject(
                "SELECT count(*) FROM store_accounts WHERE secret_reference = ? OR name = ? OR metadata::text = ?",
                Integer.class,
                needle,
                needle,
                needle);
        assertThat(materialColumns).isZero();
    }

    @Test
    void theNewRoutesConcealForeignOrganizations() {
        JsonNode linked = linkPilotToInternal();
        UUID linkId = UUID.fromString(linked.get("id").asText());
        String foreignToken = loginOfOrgRoleIn(
                createOrg("Play Foreign " + UUID.randomUUID()),
                "play-foreign-" + UUID.randomUUID().toString().substring(0, 8),
                "ORG_ADMIN");
        post(foreignToken, "/api/v1/distribution/play/groups/" + linkId + "/sync?organizationId=" + org, Map.of(), 404);
        exchange("/api/v1/distribution/play/groups/" + linkId + "?organizationId=" + org, foreignToken, "DELETE", null)
                .expect(404);
        get(foreignToken, "/api/v1/distribution/play/groups?organizationId=" + org, 404);
        get(foreignToken, "/api/v1/distribution/play/release-notes?organizationId=" + org, 404);
        // The link write with a foreign organization's id under a wrong
        // group: the shape's own 400, never a layer name.
        put(
                adminToken,
                "/api/v1/distribution/play/groups",
                Map.of(
                        "organizationId", org.toString(),
                        "applicationId", app.toString(),
                        "groupId", UUID.randomUUID().toString(),
                        "track", "internal"),
                400);
    }

    @Test
    void anIncompleteCredentialIsTheConfigurationFaultAndNeverDials() {
        linkPilotToInternal();
        UUID environment = playEnvironment(null);
        // Material that is not the service-account JSON document is a
        // binding-gap, not a provider conversation: the record fails
        // PERMANENT without a single token or API call.
        bindGooglePlayCredential(adminToken, org, null, null, "Org Play Not Json", "just-some-material");
        PLAY.clearCalls();
        UUID release = releaseWalkedToActive(environment, "qa");
        JsonNode row = recordOf(release);
        assertThat(row.get("status").asString()).isEqualTo("FAILED");
        assertThat(row.get("failureCode").asString()).isEqualTo("ADAPTER_FAILED");
        assertThat(row.get("attempts").asInt()).isEqualTo(1);
        assertThat(PLAY.callsOf("POST", "/token")).isEmpty();
        assertThat(PLAY.callsOf("POST", "/edits")).isEmpty();
    }

    // -----------------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------------

    private JsonNode linkPilotToInternal() {
        return linkPilotTo("internal");
    }

    private JsonNode linkPilotTo(String track) {
        return put(
                adminToken,
                "/api/v1/distribution/play/groups",
                Map.of(
                        "organizationId", org.toString(),
                        "applicationId", app.toString(),
                        "groupId", pilotGroup.toString(),
                        "track", track),
                200);
    }

    private void bindGooglePlayCredential(
            String token, UUID organizationId, UUID applicationId, UUID environmentId, String name, String material) {
        Map<String, Object> binding = new LinkedHashMap<>();
        binding.put("organizationId", organizationId.toString());
        if (applicationId != null) {
            binding.put("applicationId", applicationId.toString());
        }
        if (environmentId != null) {
            binding.put("environmentId", environmentId.toString());
        }
        binding.put("provider", "google-play");
        binding.put("name", name);
        binding.put("secretMaterial", material);
        put(token, "/api/v1/distribution/credentials", binding, 200);
    }

    /** The mock's document with a foreign client email (the assertion the mock must refuse). */
    private String withClientEmail(String email) {
        return PLAY.serviceAccountJson().replaceAll("\"[a-zA-Z0-9.-]+@[^\"\\\\]+\"", "\"" + email + "\"");
    }

    private void authorReleaseNotes(String locale, String notes) {
        put(
                adminToken,
                "/api/v1/distribution/play/release-notes",
                Map.of(
                        "organizationId",
                        org.toString(),
                        "applicationId",
                        app.toString(),
                        "locale",
                        locale,
                        "notes",
                        notes),
                200);
    }

    /** The production environment bound to the play channel, with the optional track override. */
    private UUID playEnvironment(String trackOverride) {
        UUID production = environmentByCode("production");
        patch(adminToken, "/api/v1/environments/" + production, Map.of("distributionChannel", "play"), 200);
        if (trackOverride != null) {
            put(
                    adminToken,
                    "/api/v1/environments/" + production + "/config",
                    Map.of("entries", Map.of("play-track", trackOverride)),
                    200);
        }
        put(
                adminToken,
                "/api/v1/environments/" + production + "/tenants",
                Map.of("tenantIds", List.of(tenantA.toString())),
                200);
        return production;
    }

    /** A fresh Android release walked DRAFT to ACTIVE through PRD 17's spine, targeting the Pilot group. */
    private UUID releaseWalkedToActive(UUID environmentId, String ring) {
        UUID release = releaseWalkedToDeploying(environmentId, ring, packageName, "AAB");
        post(
                managerToken,
                "/api/v1/releases/" + release + "/transitions",
                Map.of("applicationId", app.toString(), "to", "ACTIVE"),
                200);
        return release;
    }

    private UUID releaseWalkedToActiveWithFormat(UUID environmentId, String ring, String format) {
        UUID release = releaseWalkedToDeploying(environmentId, ring, packageName, format);
        post(
                managerToken,
                "/api/v1/releases/" + release + "/transitions",
                Map.of("applicationId", app.toString(), "to", "ACTIVE"),
                200);
        return release;
    }

    private UUID releaseWalkedToActiveWithRing(UUID environmentId, String ring) {
        return releaseWalkedToActive(environmentId, ring);
    }

    /** The byte-source fault's walk: the artifact row names a key no object lives at. */
    private UUID releaseWalkedToActiveWithoutObject(UUID environmentId, String ring) {
        UUID release = releaseWalkedToDeploying(environmentId, ring, packageName, "AAB", false);
        post(
                managerToken,
                "/api/v1/releases/" + release + "/transitions",
                Map.of("applicationId", app.toString(), "to", "ACTIVE"),
                200);
        return release;
    }

    private UUID releaseWalkedToActiveWithBundle(UUID environmentId, String extractedPackage) {
        UUID release = releaseWalkedToDeploying(environmentId, "qa", extractedPackage, "AAB");
        post(
                managerToken,
                "/api/v1/releases/" + release + "/transitions",
                Map.of("applicationId", app.toString(), "to", "ACTIVE"),
                200);
        return release;
    }

    /** The frozen-notes test's walk: DRAFT to DEPLOYING, the ACTIVE crossing left to the test. */
    private UUID releaseWalkedToDeploying(UUID environmentId, String ring) {
        return releaseWalkedToDeploying(environmentId, ring, packageName, "AAB");
    }

    private UUID releaseWalkedToDeploying(UUID environmentId, String ring, String extractedPackage, String format) {
        return releaseWalkedToDeploying(environmentId, ring, extractedPackage, format, true);
    }

    private UUID releaseWalkedToDeploying(
            UUID environmentId, String ring, String extractedPackage, String format, boolean store) {
        String name = String.format("%d.%d.%d", 4 + versionCounter / 10, versionCounter % 10, 2);
        versionCounter++;
        UUID version = createVersion(ownerToken, app, name);
        UUID artifact = insertArtifact(version, extractedPackage, format, store);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("applicationId", app.toString());
        body.put("versionId", version.toString());
        body.put("ring", ring);
        body.put("environmentId", environmentId.toString());
        body.put("artifacts", List.of(Map.of("platform", "ANDROID", "artifactId", artifact.toString())));
        body.put(
                "targets",
                List.of(Map.of("tenantId", tenantA.toString(), "testerGroupIds", List.of(pilotGroup.toString()))));
        UUID release = UUID.fromString(
                post(managerToken, "/api/v1/releases", body, 201).get("id").asText());
        for (String step : List.of("VALIDATING", "READY", "APPROVAL_PENDING", "APPROVED", "DEPLOYING")) {
            post(
                    managerToken,
                    "/api/v1/releases/" + release + "/transitions",
                    Map.of("applicationId", app.toString(), "to", step),
                    200);
        }
        return release;
    }

    private JsonNode recordOf(UUID releaseId) {
        JsonNode records = get(
                managerToken, "/api/v1/distribution/records?organizationId=" + org + "&releaseId=" + releaseId, 200);
        assertThat(records.get("items").size()).isEqualTo(1);
        return records.get("items").get(0);
    }

    private UUID environmentByCode(String code) {
        return jdbc.queryForObject(
                "SELECT id FROM environments WHERE organization_id = ?::uuid AND code = ?",
                UUID.class,
                org.toString(),
                code);
    }

    private UUID groupIdByName(UUID tenantId, String name) {
        return jdbc.queryForObject(
                "SELECT id FROM tester_groups WHERE tenant_id = ? AND name = ?", UUID.class, tenantId, name);
    }

    private UUID activeTester(String admin, UUID orgId, String email, String fullName) {
        JsonNode invited = post(
                admin,
                "/api/v1/testers",
                Map.of("organizationId", orgId.toString(), "email", email, "fullName", fullName),
                201);
        client.post()
                .uri("/api/v1/auth/register")
                .body(Map.of(
                        "invitationToken",
                        invited.get("invitation").get("invitationToken").asText(),
                        "email",
                        email,
                        "password",
                        PASSWORD,
                        "fullName",
                        fullName))
                .retrieve()
                .body(String.class);
        return UUID.fromString(invited.get("id").asText());
    }

    private void linkTesterToTenant(String bearer, UUID tenantId, UUID testerId) {
        post(bearer, "/api/v1/tenants/" + tenantId + "/testers", Map.of("testerId", testerId.toString()), 201);
    }

    /**
     * Inserts an artifact row directly (READY, AAB/APK) with the
     * extracted identity the connector matches on, and stores the
     * binary in object storage under the PRD 24 key — the upload
     * channel's byte source.
     */
    private UUID insertArtifact(UUID versionId, String extractedPackage, String format) {
        return insertArtifact(versionId, extractedPackage, format, true);
    }

    /** The byte-source fault's variant: the row names a key no object lives at. */
    private UUID insertArtifact(UUID versionId, String extractedPackage, String format, boolean store) {
        UUID artifactId = UUID.randomUUID();
        Map<String, Object> owner =
                jdbc.queryForMap("SELECT organization_id, tenant_id FROM versions WHERE id = ?", versionId);
        String storageKey = "%s/%s/%s/%s/%s/artifact"
                .formatted(owner.get("organization_id"), owner.get("tenant_id"), app, versionId, artifactId);
        if (store) {
            artifactStorage.put(storageKey, new ByteArrayInputStream(aabBytes), "application/octet-stream");
        }
        jdbc.update(
                "INSERT INTO artifacts (id, version_id, platform, file_name, storage_key, sha256, size, "
                        + "build_number, bundle_id, version_name, format, status, organization_id, tenant_id) "
                        + "VALUES (?, ?, ?::platform, ?, ?, ?, ?, ?, ?, ?, ?::varchar, ?::artifact_status, ?, ?)",
                artifactId,
                versionId,
                "ANDROID",
                "banking-" + versionCounter + ".aab",
                storageKey,
                "b1".repeat(32),
                (long) aabBytes.length,
                1L,
                extractedPackage,
                "1.0." + versionCounter,
                format,
                "READY",
                owner.get("organization_id"),
                owner.get("tenant_id"));
        return artifactId;
    }

    private UUID createVersion(String token, UUID applicationId, String versionName) {
        return UUID.fromString(post(
                        token,
                        "/api/v1/applications/" + applicationId + "/versions",
                        Map.of("versionName", versionName),
                        201)
                .get("id")
                .asText());
    }

    private UUID createAndroidApp(String token, UUID tenantId, String name, String androidPackage) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenantId", tenantId.toString());
        body.put("name", name);
        body.put("platforms", List.of("ANDROID"));
        body.put("packageName", androidPackage);
        return UUID.fromString(
                post(token, "/api/v1/applications", body, 201).get("id").asText());
    }

    private UUID createTenant(String token, UUID organizationId, String name, String externalId) {
        return UUID.fromString(post(
                        token,
                        "/api/v1/tenants",
                        Map.of("organizationId", organizationId.toString(), "name", name, "externalId", externalId),
                        201)
                .get("id")
                .asText());
    }

    /** Creates one organization per test; repeated calls for the same name are a fixture bug. */
    private UUID createOrg(String orgName) {
        String devToken = accessToken(login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD));
        return UUID.fromString(post(devToken, "/api/v1/organizations", Map.of("name", orgName), 201)
                .get("id")
                .asText());
    }

    /** Registers a user with the given role into an EXISTING organization; returns their token. */
    private String loginOfOrgRoleIn(UUID orgId, String slug, String role) {
        String email = slug + "@example.com";
        String devToken = accessToken(login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD));
        JsonNode invitation = post(
                devToken,
                "/api/v1/auth/invitations",
                Map.of("email", email, "organizationId", orgId.toString(), "role", role),
                201);
        client.post()
                .uri("/api/v1/auth/register")
                .body(Map.of(
                        "invitationToken",
                        invitation.get("invitationToken").asText(),
                        "email",
                        email,
                        "password",
                        PASSWORD,
                        "fullName",
                        "Play Conn " + role))
                .retrieve()
                .body(String.class);
        return accessToken(login(email, PASSWORD));
    }

    private String login(String email, String password) {
        return client.post()
                .uri("/api/v1/auth/login")
                .body(Map.of("email", email, "password", password))
                .retrieve()
                .body(String.class);
    }

    private String accessToken(String tokensJson) {
        try {
            return mapper.readTree(tokensJson).get("accessToken").asText();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode post(String bearer, String uri, Map<String, Object> body, int expectedStatus) {
        return exchange(uri, bearer, "POST", body).expect(expectedStatus);
    }

    private JsonNode put(String bearer, String uri, Map<String, Object> body, int expectedStatus) {
        return exchange(uri, bearer, "PUT", body).expect(expectedStatus);
    }

    private JsonNode patch(String bearer, String uri, Map<String, Object> body, int expectedStatus) {
        return exchange(uri, bearer, "PATCH", body).expect(expectedStatus);
    }

    private JsonNode get(String bearer, String uri, int expectedStatus) {
        return exchange(uri, bearer, "GET", null).expect(expectedStatus);
    }

    private Recording exchange(String uri, String bearer, String method, Map<String, Object> body) {
        RestClient.RequestBodySpec spec =
                client.method(HttpMethod.valueOf(method)).uri(uri);
        if (bearer != null) {
            spec.headers(headers -> headers.setBearerAuth(bearer));
        }
        if (body != null) {
            spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return spec.exchange((request, response) -> {
            byte[] bytes = response.getBody().readAllBytes();
            return new Recording(
                    response.getStatusCode().value(),
                    response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE),
                    bytes.length == 0 ? mapper.createObjectNode() : mapper.readTree(bytes));
        });
    }

    private record Recording(int status, String contentType, JsonNode body) {

        JsonNode expect(int expectedStatus) {
            assertThat(status).as("HTTP status for %s", body.toString()).isEqualTo(expectedStatus);
            return body;
        }
    }
}
