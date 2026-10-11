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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
@org.springframework.context.annotation.Import({
    com.shipperdog.api.identity.AuditCapture.class,
    GooglePlayConnectorIntegrationTest.RolloutMetricSource.class
})
class GooglePlayConnectorIntegrationTest {

    /**
     * The analytics stand-in for the rollout policy gate's end-to-end
     * proof (S13-05, AC4): the single registrant of the policy metric
     * seam, armed per release exactly the ReleasePolicy suite's shape.
     */
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class RolloutMetricSource implements com.shipperdog.api.release.ReleasePolicyMetricSource {

        static final java.util.Map<UUID, java.util.Map<com.shipperdog.api.release.PolicyMetric, Double>> VALUES =
                new java.util.concurrent.ConcurrentHashMap<>();

        static void arm(UUID releaseId, com.shipperdog.api.release.PolicyMetric metric, Double value) {
            VALUES.computeIfAbsent(releaseId, key -> new java.util.concurrent.ConcurrentHashMap<>())
                    .put(metric, value);
        }

        @Override
        public java.util.Optional<Double> metricOf(UUID releaseId, com.shipperdog.api.release.PolicyMetric metric) {
            return java.util.Optional.ofNullable(
                    VALUES.getOrDefault(releaseId, java.util.Map.of()).get(metric));
        }
    }

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
    void aTrackOverrideNamingProductionIsRefusedLoudly() {
        linkPilotToInternal();
        UUID environment = playEnvironment("production");
        PLAY.clearCalls();
        UUID release = releaseWalkedToActive(environment, "qa");
        JsonNode row = recordOf(release);
        assertThat(row.get("status").asString()).isEqualTo("FAILED");
        assertThat(row.get("failureCode").asString()).isEqualTo("ADAPTER_FAILED");
        assertThat(row.get("failureDetail").asString())
                .contains("production ring's own mapping")
                .contains("play-track");
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
    // S13-05 (#65): the production promotion and the staged rollout
    // -----------------------------------------------------------------------

    @Test
    void thePromotionWalkCarriesOntoTheProductionTrackAtItsConfiguredPercentage() {
        linkPilotToInternal();
        UUID environment = playEnvironment(null, "10");
        UUID release = releaseWalkedToActive(environment, "qa");
        // The walk: qa -> tenant-pilot -> uat -> production (the final
        // ring's landing fires the PROMOTED crossing, the dispatch
        // follows the walk onto the production track).
        for (String ring : List.of("tenant-pilot", "uat", "production")) {
            post(
                    managerToken,
                    "/api/v1/releases/" + release + "/promote",
                    Map.of("applicationId", app.toString(), "tenants", List.of(tenantA.toString())),
                    200);
        }
        assertThat(jdbc.queryForObject("SELECT status FROM releases WHERE id = ?", String.class, release))
                .isEqualTo("PROMOTED");

        // The record walked forward with the release: the production
        // track release, staged at the configured 10%.
        JsonNode row = recordOf(release);
        assertThat(row.get("channel").asString()).isEqualTo("play");
        assertThat(row.get("externalId").asString()).isEqualTo("production#2#" + packageName);
        assertThat(row.get("status").asString()).isEqualTo("DISTRIBUTING");
        assertThat(row.get("providerStatus").asString()).isEqualTo("inProgress");

        // The wire: the track write carried Play's userFraction.
        List<GooglePlayDeveloperApiMock.RecordedCall> writes = PLAY.callsOf("PUT", "/tracks/production");
        assertThat(writes).hasSize(1);
        assertThat(writes.get(0).body()).contains("\"userFraction\":0.1");

        // The rollout state (AC1): the intent at the configured
        // percentage, the START step on the timeline, the audit line.
        JsonNode rollout = rolloutView(release);
        assertThat(rollout.get("status").asString()).isEqualTo("ROLLING");
        assertThat(rollout.get("intendedPercent").asInt()).isEqualTo(10);
        assertThat(rollout.get("observedPercent").asInt()).isEqualTo(10);
        assertThat(rollout.get("drift").asBoolean()).isFalse();
        assertThat(rollout.get("steps").size()).isEqualTo(1);
        assertThat(rollout.get("steps").get(0).get("action").asString()).isEqualTo("START");
        assertThat(audit.eventsOfType(AuditEventType.DISTRIBUTION_ROLLOUT_CONTROLLED))
                .hasSize(1);
    }

    @Test
    void aProductionReleaseWithoutARolloutConfigIsTheFullList() {
        linkPilotToInternal();
        UUID release = releasePromotedToProduction(playEnvironment(null, null));
        // No curve configured: the full-list production release, the
        // rollout state honest at COMPLETE 100.
        List<GooglePlayDeveloperApiMock.RecordedCall> writes = PLAY.callsOf("PUT", "/tracks/production");
        assertThat(writes.get(0).body()).doesNotContain("userFraction");
        JsonNode rollout = rolloutView(release);
        assertThat(rollout.get("status").asString()).isEqualTo("COMPLETE");
        assertThat(rollout.get("intendedPercent").asInt()).isEqualTo(100);
    }

    @Test
    void percentageIncreasesApplyThroughThePlayApiAndReflectInShipperdog() {
        linkPilotToInternal();
        UUID release = releasePromotedToProduction(playEnvironment(null, "10"));
        JsonNode advanced = rolloutControl(release, "ADVANCE", 50, 200);
        assertThat(advanced.get("intendedPercent").asInt()).isEqualTo(50);
        assertThat(advanced.get("observedPercent").asInt()).isEqualTo(50);
        assertThat(advanced.get("status").asString()).isEqualTo("ROLLING");
        List<GooglePlayDeveloperApiMock.RecordedCall> writes = PLAY.callsOf("PUT", "/tracks/production");
        assertThat(writes).hasSize(2);
        assertThat(writes.get(1).body()).contains("\"userFraction\":0.5");
        assertThat(writes.get(1).body()).contains("\"status\":\"inProgress\"");
        // The curve at 100: the full list; Google's completion word
        // completes the rollout on the next observation.
        PLAY.clearCalls();
        rolloutControl(release, "ADVANCE", 100, 200);
        assertThat(PLAY.callsOf("PUT", "/tracks/production").get(0).body()).contains("\"userFraction\":1.0");
        PLAY.setTrackReleaseStatus(packageName, "production", 2, "completed");
        JsonNode done = rolloutView(release);
        assertThat(done.get("status").asString()).isEqualTo("COMPLETE");
    }

    @Test
    void advanceShapeAndStateFaultsAreRefusedLoudly() {
        linkPilotToInternal();
        UUID release = releasePromotedToProduction(playEnvironment(null, "10"));
        // An unknown vocabulary word.
        post(
                managerToken,
                "/api/v1/distribution/play/releases/" + release + "/rollout?organizationId=" + org,
                Map.of("action", "PAUSE"),
                400);
        // An advance without its percentage.
        rolloutControl(release, "ADVANCE", null, 400);
        // Out of range.
        rolloutControl(release, "ADVANCE", 0, 400);
        rolloutControl(release, "ADVANCE", 101, 400);
        // Not strictly greater.
        rolloutControl(release, "ADVANCE", 10, 400);
        rolloutControl(release, "ADVANCE", 5, 400);
        // A testing-track release carries no rollout at all: the honest miss.
        UUID testing = releaseWalkedToActive(playEnvironment(null), "qa");
        get(managerToken, "/api/v1/distribution/play/releases/" + testing + "/rollout?organizationId=" + org, 404);
    }

    @Test
    void haltStopsDistributionResumesAndBothAreVisibleOnTheTimeline() {
        linkPilotToInternal();
        UUID release = releasePromotedToProduction(playEnvironment(null, "10"));
        rolloutControl(release, "ADVANCE", 25, 200);
        JsonNode halted = rolloutControl(release, "HALT", null, 200);
        assertThat(halted.get("status").asString()).isEqualTo("HALTED");
        assertThat(PLAY.callsOf("PUT", "/tracks/production").get(2).body()).contains("\"status\":\"halted\"");
        // A halt of a halted rollout is the state fault.
        rolloutControl(release, "HALT", null, 400);
        // The resume continues from the same percentage.
        JsonNode resumed = rolloutControl(release, "RESUME", null, 200);
        assertThat(resumed.get("status").asString()).isEqualTo("ROLLING");
        assertThat(resumed.get("intendedPercent").asInt()).isEqualTo(25);
        assertThat(PLAY.callsOf("PUT", "/tracks/production").get(3).body()).contains("\"userFraction\":0.25");
        // AC3: the full timeline with actor and percentages.
        JsonNode view = rolloutView(release);
        assertThat(view.get("steps").size()).isEqualTo(4);
        assertThat(view.get("steps").get(1).get("action").asString()).isEqualTo("ADVANCE");
        assertThat(view.get("steps").get(1).get("fromPercent").asInt()).isEqualTo(10);
        assertThat(view.get("steps").get(1).get("toPercent").asInt()).isEqualTo(25);
        assertThat(view.get("steps").get(2).get("action").asString()).isEqualTo("HALT");
        assertThat(view.get("steps").get(3).get("action").asString()).isEqualTo("RESUME");
        for (JsonNode step : view.get("steps")) {
            if (!"START".equals(step.get("action").asText())) {
                assertThat(step.get("actorId").isNull()).isFalse();
            }
        }
        assertThat(audit.eventsOfType(AuditEventType.DISTRIBUTION_ROLLOUT_CONTROLLED))
                .hasSize(4);
    }

    @Test
    void policyRulesBlockIncreasesOnFailingMetricsAndAdmitSatisfiedOnes() {
        linkPilotToInternal();
        UUID release = releasePromotedToProduction(playEnvironment(null, "10"));
        // AC4's end-to-end example: a BLOCK rule on the production
        // ring, the metric answering a failing value.
        Map<String, Object> condition = new LinkedHashMap<>();
        condition.put("metric", "CRASH_FREE_RATE");
        condition.put("operator", "<");
        condition.put("value", 0.99);
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("organizationId", org.toString());
        rule.put("name", "production-crash-floor");
        rule.put("scope", "RING");
        rule.put("ring", "production");
        rule.put("action", "BLOCK");
        rule.put("position", 10);
        rule.put("condition", condition);
        JsonNode created = post(adminToken, "/api/v1/release-policies", rule, 201);
        RolloutMetricSource.arm(release, com.shipperdog.api.release.PolicyMetric.CRASH_FREE_RATE, 0.80);
        int evaluationsBefore = jdbc.queryForObject(
                "SELECT count(*) FROM release_policy_evaluations WHERE release_id = ?", Integer.class, release);

        JsonNode problem = post(
                managerToken,
                "/api/v1/distribution/play/releases/" + release + "/rollout?organizationId=" + org,
                Map.of("action", "ADVANCE", "percent", 50),
                409);
        assertThat(problem.get("code").asString()).isEqualTo("DISTRIBUTION_ROLLOUT_BLOCKED");
        assertThat(problem.get("detail").asString())
                .contains("production-crash-floor")
                .contains("0.8");
        // The refusal's evidence SURVIVED the 409's rollback (the #45
        // discipline, the review's round-1 finding): the consult runs
        // in its own transaction, so the blocked attempt's evaluation
        // row stands exactly a refused promotion's does.
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM release_policy_evaluations WHERE release_id = ? AND verdict = 'BLOCKED'",
                        Integer.class,
                        release))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM release_policy_evaluations WHERE release_id = ?", Integer.class, release))
                .isEqualTo(evaluationsBefore + 1);
        // HALT stays ungated: stopping distribution is always allowed.
        assertThat(rolloutControl(release, "HALT", null, 200).get("status").asString())
                .isEqualTo("HALTED");
        rolloutControl(release, "RESUME", null, 200);
        // The satisfied metric admits the increase.
        RolloutMetricSource.arm(release, com.shipperdog.api.release.PolicyMetric.CRASH_FREE_RATE, 0.998);
        assertThat(rolloutControl(release, "ADVANCE", 50, 200)
                        .get("intendedPercent")
                        .asInt())
                .isEqualTo(50);
        exchange(
                        "/api/v1/release-policies/" + created.get("id").asText() + "?organizationId=" + org,
                        adminToken,
                        "DELETE",
                        null)
                .expect(204);
    }

    @Test
    void reconciliationReportsManualConsoleChangesAsDriftAndReapplyIsExplicit() {
        linkPilotToInternal();
        UUID release = releasePromotedToProduction(playEnvironment(null, "10"));
        // The manual Play Console change: an operator moved the store's
        // fraction against the platform's intent.
        PLAY.setTrackReleaseUserFraction(packageName, "production", 2, 0.05);
        JsonNode drifted = rolloutView(release);
        assertThat(drifted.get("drift").asBoolean()).isTrue();
        assertThat(drifted.get("observedPercent").asInt()).isEqualTo(5);
        assertThat(drifted.get("intendedPercent").asInt()).isEqualTo(10);
        assertThat(audit.eventsOfType(AuditEventType.DISTRIBUTION_ROLLOUT_DRIFT))
                .hasSize(1);
        // The steady state renders without a new audit line per read.
        rolloutView(release);
        assertThat(audit.eventsOfType(AuditEventType.DISTRIBUTION_ROLLOUT_DRIFT))
                .hasSize(1);
        // REAPPLY: the explicit operator act, the wire re-writes intent.
        JsonNode reapplied = rolloutControl(release, "REAPPLY", null, 200);
        assertThat(reapplied.get("drift").asBoolean()).isFalse();
        assertThat(reapplied.get("observedPercent").asInt()).isEqualTo(10);
        assertThat(PLAY.callsOf("PUT", "/tracks/production").get(1).body()).contains("\"userFraction\":0.1");
        assertThat(reapplied.get("steps").get(1).get("action").asString()).isEqualTo("REAPPLY");
    }

    @Test
    void aRefusedProductionReleaseVetoesThePromotionAndTheReleaseStandsActive() {
        linkPilotToInternal();
        UUID environment = playEnvironment(null, "10");
        UUID release = releaseWalkedToActive(environment, "qa");
        for (String ring : List.of("tenant-pilot", "uat")) {
            post(
                    managerToken,
                    "/api/v1/releases/" + release + "/promote",
                    Map.of("applicationId", app.toString(), "tenants", List.of(tenantA.toString())),
                    200);
        }
        // The production leg's permanent refusal (Google's own words):
        // the submission-grade dispatch vetoes the PROMOTED crossing —
        // the hop rolls back, the release stands ACTIVE on uat, the
        // failure records land with the actionable reason.
        PLAY.rejectNextTrackWrite();
        JsonNode problem = post(
                managerToken,
                "/api/v1/releases/" + release + "/promote",
                Map.of("applicationId", app.toString(), "tenants", List.of(tenantA.toString())),
                502);
        assertThat(problem.get("code").asString()).isEqualTo("ADAPTER_FAILED");
        assertThat(problem.get("detail").asString()).contains("cannot go PROMOTED");
        assertThat(jdbc.queryForObject("SELECT status FROM releases WHERE id = ?", String.class, release))
                .isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject(
                        "SELECT r.code FROM release_targets t JOIN rings r ON r.id = t.ring_id"
                                + " WHERE t.release_id = ?",
                        String.class,
                        release))
                .isEqualTo("uat");
        JsonNode row = recordOf(release);
        assertThat(row.get("status").asString()).isEqualTo("FAILED");
        assertThat(row.get("failureDetail").asString()).contains("Release note text");
        // No rollout state exists: the landing never happened.
        get(managerToken, "/api/v1/distribution/play/releases/" + release + "/rollout?organizationId=" + org, 404);
    }

    @Test
    void aFailedControlWriteAnswersTheTaxonomyProblemAndTheCurvesIntentStandsUnmoved() {
        linkPilotToInternal();
        UUID release = releasePromotedToProduction(playEnvironment(null, "10"));
        // The provider refuses the control write (Google's own words):
        // the taxonomy problem answers, and the intent model does NOT
        // move — the write precedes every state change, so no
        // half-applied step ever lands on the timeline.
        PLAY.rejectNextTrackWrite();
        JsonNode problem = post(
                managerToken,
                "/api/v1/distribution/play/releases/" + release + "/rollout?organizationId=" + org,
                Map.of("action", "ADVANCE", "percent", 50),
                502);
        assertThat(problem.get("code").asString()).isEqualTo("ADAPTER_FAILED");
        assertThat(problem.get("detail").asString()).contains("Release note text");
        JsonNode after = rolloutView(release);
        assertThat(after.get("intendedPercent").asInt()).isEqualTo(10);
        assertThat(after.get("status").asString()).isEqualTo("ROLLING");
        assertThat(after.get("steps").size()).isEqualTo(1);
        assertThat(after.get("steps").get(0).get("action").asString()).isEqualTo("START");
        // The refused attempt left no audit line of a completed act.
        assertThat(audit.eventsOfType(AuditEventType.DISTRIBUTION_ROLLOUT_CONTROLLED))
                .hasSize(1);
        // The curve is not wedged: the next write proceeds.
        assertThat(rolloutControl(release, "ADVANCE", 50, 200)
                        .get("intendedPercent")
                        .asInt())
                .isEqualTo(50);
    }

    @Test
    void aBrokenReadAfterTheControlWriteNeverRollsBackAnActGoogleAlreadyApplied() {
        linkPilotToInternal();
        UUID release = releasePromotedToProduction(playEnvironment(null, "10"));
        // The review's round-1 finding: the store's LIVE read breaks
        // right after the control's write lands. The control answers
        // from the WRITE'S OWN ECHO — it performs no follow-up read —
        // so the applied act's platform state (the intent, the
        // observation, the timeline step) commits regardless; only a
        // control that re-read the live track inside its transaction
        // could fail here (the transient exhausting, the act Google
        // already applied rolled back).
        // Scope the wire assertions to the control's own calls (the
        // landing dispatch's refresh reads the live track too).
        PLAY.clearCalls();
        PLAY.failNextLiveTrackRead();
        JsonNode advanced = rolloutControl(release, "ADVANCE", 50, 200);
        assertThat(advanced.get("intendedPercent").asInt()).isEqualTo(50);
        assertThat(advanced.get("observedPercent").asInt()).isEqualTo(50);
        assertThat(advanced.get("providerStatus").asString()).isEqualTo("inProgress");
        assertThat(PLAY.callsOf("PUT", "/tracks/production")).hasSize(1);
        assertThat(PLAY.callsOf("PUT", "/tracks/production").get(0).body()).contains("\"userFraction\":0.5");
        // The control never re-read the live track: its only reads are
        // the edit model's own (inside /edits/), the answer came from
        // the write's echo.
        assertThat(PLAY.calls().stream()
                        .filter(call -> "GET".equals(call.method()))
                        .filter(call ->
                                call.path().contains("/tracks/") && !call.path().contains("/edits/"))
                        .count())
                .isZero();
        // The state stands and the read path recovers on its own
        // retries (the knob spends itself on the view's first
        // attempt): the timeline carries exactly the one ADVANCE.
        JsonNode after = rolloutView(release);
        assertThat(after.get("intendedPercent").asInt()).isEqualTo(50);
        assertThat(after.get("status").asString()).isEqualTo("ROLLING");
        assertThat(after.get("steps").size()).isEqualTo(2);
        assertThat(after.get("steps").get(1).get("action").asString()).isEqualTo("ADVANCE");
        assertThat(audit.eventsOfType(AuditEventType.DISTRIBUTION_ROLLOUT_CONTROLLED))
                .hasSize(2);
    }

    @Test
    void concurrentAdvancesProveExactlyOneWinnerAndTheLoserAnswersTheConflict() throws Exception {
        linkPilotToInternal();
        UUID release = releasePromotedToProduction(playEnvironment(null, "10"));
        // Two operators race an ADVANCE off a common barrier. The
        // version stamp (the #51 subscription precedent, the review's
        // round-1 finding) admits exactly ONE winner; the loser answers
        // the precise conflict 409 — or, when the barrier interleaves
        // loosely and the loser's read lands after the winner's
        // commit, the strict-increase guard's own refusal (still one
        // winner, still a single applied step — never a double-applied
        // advance, never a lowered curve, never a 5xx).
        int racers = 2;
        CyclicBarrier start = new CyclicBarrier(racers);
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        List<Recording> answers;
        try {
            List<Future<Recording>> attempts = new ArrayList<>();
            for (int i = 0; i < racers; i++) {
                attempts.add(pool.submit(() -> {
                    start.await(30, TimeUnit.SECONDS);
                    return exchange(
                            "/api/v1/distribution/play/releases/" + release + "/rollout?organizationId=" + org,
                            managerToken,
                            "POST",
                            Map.of("action", "ADVANCE", "percent", 50));
                }));
            }
            answers = new ArrayList<>();
            for (Future<Recording> attempt : attempts) {
                answers.add(attempt.get(60, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }
        int committed = 0;
        for (Recording answer : answers) {
            if (answer.status() == 200) {
                committed++;
            } else {
                // The loser's precise refusal: the version stamp's 409
                // conflict, or — when the loser's initial read landed
                // after the winner's commit — the strict-increase
                // guard's 400 off the moved state. Both prove one
                // winner; never a 5xx, never a double-applied step.
                assertThat(answer.status()).as("body %s", answer.body()).isIn(400, 409);
                assertThat(answer.body().get("code").asText())
                        .isIn("DISTRIBUTION_ROLLOUT_CONFLICT", "DISTRIBUTION_ROLLOUT_ACTION_INVALID");
            }
        }
        assertThat(committed).isEqualTo(1);
        // The curve moved exactly once: one ADVANCE step beside the
        // START, the intent at the winner's 50, one audit line of the
        // completed act (the loser's audit rolled back with its
        // transaction).
        JsonNode view = rolloutView(release);
        assertThat(view.get("intendedPercent").asInt()).isEqualTo(50);
        assertThat(view.get("status").asString()).isEqualTo("ROLLING");
        assertThat(view.get("steps").size()).isEqualTo(2);
        assertThat(view.get("steps").get(1).get("action").asString()).isEqualTo("ADVANCE");
        assertThat(audit.eventsOfType(AuditEventType.DISTRIBUTION_ROLLOUT_CONTROLLED))
                .hasSize(2);
    }

    @Test
    void aReleaseBornOnTheProductionRingDispatchesToTheProductionTrackAtTheActiveCrossing() {
        linkPilotToInternal();
        UUID environment = playEnvironment(null, "10");
        UUID release = releaseWalkedToActive(environment, "production");
        // The dispatch's other face: a release whose walk STARTS at
        // production addresses the production track at the ACTIVE
        // crossing itself — the same mapping, the same staged start.
        assertThat(jdbc.queryForObject("SELECT status FROM releases WHERE id = ?", String.class, release))
                .isEqualTo("ACTIVE");
        JsonNode row = recordOf(release);
        assertThat(row.get("externalId").asString()).isEqualTo("production#1#" + packageName);
        assertThat(row.get("status").asString()).isEqualTo("DISTRIBUTING");
        assertThat(row.get("providerStatus").asString()).isEqualTo("inProgress");
        assertThat(PLAY.callsOf("PUT", "/tracks/production").get(0).body()).contains("\"userFraction\":0.1");
        JsonNode rollout = rolloutView(release);
        assertThat(rollout.get("status").asString()).isEqualTo("ROLLING");
        assertThat(rollout.get("intendedPercent").asInt()).isEqualTo(10);
        assertThat(rollout.get("steps").get(0).get("action").asString()).isEqualTo("START");
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
        return playEnvironment(trackOverride, null);
    }

    /**
     * The production environment bound to the play channel, with the
     * optional track override and the optional staged rollout's
     * initial percentage (S13-05's configuration key).
     */
    private UUID playEnvironment(String trackOverride, String rolloutPercent) {
        UUID production = environmentByCode("production");
        patch(adminToken, "/api/v1/environments/" + production, Map.of("distributionChannel", "play"), 200);
        Map<String, String> entries = new LinkedHashMap<>();
        if (trackOverride != null) {
            entries.put("play-track", trackOverride);
        }
        if (rolloutPercent != null) {
            entries.put("play-rollout-percent", rolloutPercent);
        }
        if (!entries.isEmpty()) {
            put(adminToken, "/api/v1/environments/" + production + "/config", Map.of("entries", entries), 200);
        }
        put(
                adminToken,
                "/api/v1/environments/" + production + "/tenants",
                Map.of("tenantIds", List.of(tenantA.toString())),
                200);
        return production;
    }

    /**
     * The full promotion journey of S13-05 (AC1): a release walked to
     * ACTIVE on the qa ring, then promoted ring by ring onto the
     * production ring — the final landing fires the PROMOTED crossing
     * the production dispatch follows.
     */
    private UUID releasePromotedToProduction(UUID environmentId) {
        UUID release = releaseWalkedToActive(environmentId, "qa");
        for (String ring : List.of("tenant-pilot", "uat", "production")) {
            post(
                    managerToken,
                    "/api/v1/releases/" + release + "/promote",
                    Map.of("applicationId", app.toString(), "tenants", List.of(tenantA.toString())),
                    200);
        }
        return release;
    }

    /** The rollout view read (the dashboard's own route). */
    private JsonNode rolloutView(UUID releaseId) {
        return get(
                managerToken,
                "/api/v1/distribution/play/releases/" + releaseId + "/rollout?organizationId=" + org,
                200);
    }

    /** One rollout control action through the release-actions route. */
    private JsonNode rolloutControl(UUID releaseId, String action, Integer percent, int expected) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("action", action);
        if (percent != null) {
            body.put("percent", percent);
        }
        return post(
                managerToken,
                "/api/v1/distribution/play/releases/" + releaseId + "/rollout?organizationId=" + org,
                body,
                expected);
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
