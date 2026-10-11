package com.shipperdog.api.distribution;

import static org.assertj.core.api.Assertions.assertThat;

import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.distribution.internal.apple.AppleAppStoreConnectMock;
import com.shipperdog.api.identity.AuditCapture;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;

/**
 * The App Store production connector's end-to-end proof (issue #63,
 * S13-03): the ACTIVE crossing on an {@code appstore}-bound
 * environment creates the ASC version, writes the FROZEN manifest
 * metadata's locales, starts the configured phased-release curve, and
 * submits for review (AC1); the review journey surfaces through the
 * explicit refresh and the webhook-triggered sync (AC2); the curve's
 * progress, pause, resume, and halt ride the release actions (AC3);
 * a metadata-refused submission vetoes the crossing and lands the
 * release DEPLOYING to FAILED with Apple's own actionable words
 * (AC4); and the whole journey — submit through phased release start
 * — runs against the contract-faithful mock ASC (AC5). NO live Apple
 * call anywhere.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
            "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef",
            "shipperdog.webhooks.appstore-bundle-ids=com.example.fixed.app",
            "shipperdog.webhooks.appstore-trusted-roots=classpath:webhooks/appstore-test-root.crt"
        })
@TestPropertySource(properties = "shipperdog.distribution.adapters.appstore.retry-backoff=PT0.05S")
@Testcontainers
@org.springframework.context.annotation.Import(com.shipperdog.api.identity.AuditCapture.class)
class AppleSubmissionIntegrationTest {

    private static final String DEV_ADMIN_EMAIL = "dev@shipperdog.local";
    private static final String DEV_ADMIN_PASSWORD = "shipperdog-dev-admin";
    private static final String PASSWORD = "a-perfectly-valid-password";

    /** The fixed bundle the webhook allowlist admits (the webhook leg's fixture). */
    private static final String WEBHOOK_BUNDLE = "com.example.fixed.app";

    /** The mock ASC server: Apple's shapes, the mock's key, the mock's verdicts. */
    static final AppleAppStoreConnectMock ASC = new AppleAppStoreConnectMock();

    @DynamicPropertySource
    static void appleConnector(DynamicPropertyRegistry registry) {
        registry.add("shipperdog.distribution.apple.base-url", ASC::baseUrl);
    }

    @AfterAll
    static void stopMock() {
        ASC.stop();
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
    private com.shipperdog.api.distribution.internal.service.StoreStatusEventConsumer storeEventConsumer;

    @Autowired
    private tools.jackson.databind.ObjectMapper mapper;

    private RestClient client;

    private String slug;
    private UUID org;
    private String adminToken;
    private String ownerToken;
    private String managerToken;
    private UUID tenantA;
    private UUID app;
    private String bundleId;
    private String appleAppId;
    private int versionCounter;

    @BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
        audit.reset();
        ASC.reset();

        slug = "apple-as-" + UUID.randomUUID().toString().substring(0, 8);
        org = createOrg("Apple AS Corp " + slug);
        adminToken = loginOfOrgRoleIn(org, slug + "-admin", "ORG_ADMIN");
        ownerToken = loginOfOrgRoleIn(org, slug + "-owner", "APP_OWNER");
        managerToken = loginOfOrgRoleIn(org, slug + "-manager", "RELEASE_MANAGER");
        tenantA = createTenant(adminToken, org, "Apple AS Bank", "crm-" + slug);
        bundleId = "com." + slug.replace("-", "") + ".banking";
        app = createTenantApp(adminToken, tenantA, "Apple AS Banking", bundleId);
        appleAppId = ASC.registerApp(bundleId);
        bindAppleCredential();
    }

    // -----------------------------------------------------------------------
    // AC1 + AC3 + AC5: promotion submits the frozen metadata and starts
    // the phased release
    // -----------------------------------------------------------------------

    @Test
    void promotionCreatesTheVersionWritesTheFrozenLocalesAndSubmitsForReview() {
        putMetadata("en-US", "The banking app your team pilots with.", "banking,pilot", "Stability first.");
        UUID release = releaseWalkedToActive(appstoreEnvironment(true));

        JsonNode row = recordOf(release);
        assertThat(row.get("channel").asString()).isEqualTo("appstore");
        assertThat(row.get("status").asString()).isEqualTo("DISTRIBUTING");
        assertThat(row.get("externalId").asString()).startsWith("version-");
        assertThat(row.get("providerStatus").asString()).isEqualTo("WAITING_FOR_REVIEW");

        // The wire, in the submission's order: version create, locale
        // write, release type for the curve, curve enable, submission.
        assertThat(ASC.calls().stream()
                        .filter(call ->
                                call.method().equals("POST") && call.path().equals("/v1/appStoreVersions")))
                .hasSize(1);
        List<AppleAppStoreConnectMock.RecordedCall> localizations =
                ASC.callsOf("POST", "/appStoreVersionLocalizations");
        assertThat(localizations).hasSize(1);
        assertThat(localizations.get(0).body()).contains("The banking app your team pilots with.");
        assertThat(localizations.get(0).body()).contains("banking,pilot");
        assertThat(localizations.get(0).body()).contains("Stability first.");
        assertThat(ASC.callsOf("PATCH", "/v1/appStoreVersions/")).hasSize(1);
        assertThat(ASC.callsOf("POST", "/v1/appStoreVersionPhasedReleases")).hasSize(1);
        assertThat(ASC.callsOf("POST", "/v1/appStoreVersionSubmissions")).hasSize(1);

        // The manifest's store-submission block is the immutable copy
        // of what was sent (PRD 16): the frozen locale facts and the
        // phased decision, byte-for-byte what the wire carried.
        JsonNode manifest = get(managerToken, "/api/v1/releases/" + release + "/manifest?applicationId=" + app, 200);
        JsonNode block = manifest.path("manifest").path("storeSubmission");
        assertThat(block.path("channel").asString()).isEqualTo("appstore");
        assertThat(block.path("phasedRelease").asBoolean()).isTrue();
        assertThat(block.path("locales").get(0).path("description").asString())
                .isEqualTo("The banking app your team pilots with.");
        assertThat(block.path("locales").get(0).path("keywords").asString()).isEqualTo("banking,pilot");
        assertThat(block.path("locales").get(0).path("screenshotRefs").get(0).asString())
                .isEqualTo("asc-asset://screens/en-primary");

        // The audit trail: the authoring save and the dispatch.
        assertThat(audit.eventsOfType(AuditEventType.DISTRIBUTION_METADATA_SAVED))
                .hasSize(1);
        assertThat(audit.eventsOfType(AuditEventType.DISTRIBUTION_DISPATCHED)).hasSize(1);
    }

    @Test
    void aSecondLocaleRidesTheSameSubmissionAndTheWireAgreesWithTheManifest() {
        putMetadata("en-US", "The banking app your team pilots with.", "banking,pilot", "Stability first.");
        putMetadata("de-DE", "Die Banking-App fuer Ihr Team.", "banking,pilot-de", null);
        UUID release = releaseWalkedToActive(appstoreEnvironment(false));

        // phasedRelease false: MANUAL release type, no curve resource.
        assertThat(ASC.callsOf("PATCH", "/v1/appStoreVersions/")).hasSize(1);
        assertThat(ASC.callsOf("PATCH", "/v1/appStoreVersions/").get(0).body()).contains("MANUAL");
        assertThat(ASC.callsOf("POST", "/v1/appStoreVersionPhasedReleases")).isEmpty();
        assertThat(ASC.callsOf("POST", "/appStoreVersionLocalizations")).hasSize(2);
        JsonNode manifest = get(managerToken, "/api/v1/releases/" + release + "/manifest?applicationId=" + app, 200);
        assertThat(manifest.path("manifest")
                        .path("storeSubmission")
                        .path("locales")
                        .size())
                .isEqualTo(2);
        assertThat(manifest.path("manifest")
                        .path("storeSubmission")
                        .path("phasedRelease")
                        .asBoolean())
                .isFalse();
    }

    // -----------------------------------------------------------------------
    // AC4: a refused submission vetoes the crossing and lands the
    // release DEPLOYING to FAILED with actionable reasons
    // -----------------------------------------------------------------------

    @Test
    void aMetadataRejectedSubmissionVetoesTheCrossingAndFailsTheRelease() {
        putMetadata("en-US", "The banking app your team pilots with.", "banking,pilot", null);
        UUID release = releaseWalkedToDeploying(appstoreEnvironment(true));
        ASC.rejectNextSubmissionMetadata();

        // The ACTIVE crossing answers the provider's problem; the
        // release lands FAILED, never ACTIVE (the doctrine split).
        JsonNode problem = post(
                managerToken,
                "/api/v1/releases/" + release + "/transitions",
                Map.of("applicationId", app.toString(), "to", "ACTIVE"),
                502);
        assertThat(problem.get("code").asString()).isEqualTo("ADAPTER_FAILED");
        assertThat(problem.get("detail").asString()).contains("You must provide a value for the key");
        assertThat(jdbc.queryForObject("SELECT status FROM releases WHERE id = ?", String.class, release))
                .isEqualTo("FAILED");

        // The timeline row carries the actionable reason verbatim.
        String reason = jdbc.queryForObject(
                "SELECT reason FROM release_status_history WHERE release_id = ? AND to_status = 'FAILED'",
                String.class,
                release);
        assertThat(reason).contains("You must provide a value for the key");

        // The aftermath's records: FAILED with the provider's own words
        // beside the taxonomy code.
        JsonNode row = recordOf(release);
        assertThat(row.get("status").asString()).isEqualTo("FAILED");
        assertThat(row.get("failureCode").asString()).isEqualTo("ADAPTER_FAILED");
        assertThat(row.get("failureDetail").asString()).contains("You must provide a value for the key");
        assertThat(audit.eventsOfType(AuditEventType.DISTRIBUTION_DISPATCHED)).hasSize(1);
    }

    @Test
    void noFrozenMetadataIsTheActionableRefusalNeverASilentActive() {
        UUID release = releaseWalkedToDeploying(appstoreEnvironment(true));
        post(
                managerToken,
                "/api/v1/releases/" + release + "/transitions",
                Map.of("applicationId", app.toString(), "to", "ACTIVE"),
                502);
        assertThat(jdbc.queryForObject("SELECT status FROM releases WHERE id = ?", String.class, release))
                .isEqualTo("FAILED");
        JsonNode row = recordOf(release);
        assertThat(row.get("failureCode").asString()).isEqualTo("ADAPTER_FAILED");
        assertThat(row.get("failureDetail").asString()).contains("no store-submission metadata is frozen");
        // And the wire stayed quiet: nothing was created or submitted.
        assertThat(ASC.calls().stream()
                        .filter(call ->
                                call.method().equals("POST") && call.path().equals("/v1/appStoreVersions")))
                .isEmpty();
        assertThat(ASC.calls().stream()
                        .filter(call ->
                                call.method().equals("POST") && call.path().equals("/v1/appStoreVersionSubmissions")))
                .isEmpty();
    }

    // -----------------------------------------------------------------------
    // AC2: the review journey — explicit refresh, rejection reasons,
    // and the webhook-triggered sync
    // -----------------------------------------------------------------------

    @Test
    void theReviewJourneyWalksTheRecordThroughApplesOwnStates() {
        putMetadata("en-US", "The banking app your team pilots with.", "banking,pilot", null);
        UUID release = releaseWalkedToActive(appstoreEnvironment(true));
        UUID recordId = recordIdOf(release);
        String versionId = externalIdOf(release);
        String refreshUri = "/api/v1/distribution/records/" + recordId + "/refresh?organizationId=" + org;

        ASC.setVersionState(versionId, "IN_REVIEW");
        JsonNode inReview = post(managerToken, refreshUri, Map.of(), 200);
        assertThat(inReview.get("status").asString()).isEqualTo("DISTRIBUTING");
        assertThat(inReview.get("providerStatus").asString()).isEqualTo("IN_REVIEW");

        ASC.setVersionState(versionId, "READY_FOR_SALE");
        JsonNode ready = post(managerToken, refreshUri, Map.of(), 200);
        assertThat(ready.get("status").asString()).isEqualTo("DISTRIBUTED");

        ASC.setVersionState(versionId, "REJECTED", "Guideline 2.1 - Performance", "Metadata item missing");
        JsonNode rejected = post(managerToken, refreshUri, Map.of(), 200);
        assertThat(rejected.get("status").asString()).isEqualTo("FAILED");
        assertThat(rejected.get("failureCode").asString()).isEqualTo("APPSTORE_VERSION_REJECTED");
        assertThat(rejected.get("failureDetail").asString()).contains("Guideline 2.1");
    }

    @Test
    void aWebhookNotificationTriggersTheRecordRefresh() throws Exception {
        // The webhook leg's release rides the allowlisted fixed bundle.
        UUID webhookApp = createTenantApp(adminToken, tenantA, "Apple AS Webhook", WEBHOOK_BUNDLE);
        ASC.registerApp(WEBHOOK_BUNDLE);
        putMetadataFor(webhookApp, "en-US", "The banking app your team pilots with.", "banking", null);
        UUID release = releaseWalkedToActiveWith(appstoreEnvironment(true), webhookApp, WEBHOOK_BUNDLE);
        String versionId = externalIdOf(release);
        ASC.setVersionState(versionId, "IN_REVIEW");

        // Apple's notification, signed exactly as the receiver verifies.
        post(null, "/webhooks/appstore", Map.of("signedPayload", appStoreJws(WEBHOOK_BUNDLE)), 200);

        // The after-commit consumer runs off the receipt's thread; the
        // record's provider word is the sync's visible answer.
        awaitProviderStatus(release, "IN_REVIEW");
        assertThat(audit.eventsOfType(AuditEventType.STORE_EVENT_APPLIED)).isNotEmpty();
    }

    // -----------------------------------------------------------------------
    // AC3: the phased release — curve progress and the release actions
    // -----------------------------------------------------------------------

    @Test
    void theCurveProgressesAndPauseResumeHaltAreReleaseActions() {
        putMetadata("en-US", "The banking app your team pilots with.", "banking,pilot", null);
        UUID release = releaseWalkedToActive(appstoreEnvironment(true));
        String versionId = externalIdOf(release);
        String curveUri = "/api/v1/distribution/appstore/releases/" + release + "/phased-release?organizationId=" + org;

        // Before release: the curve exists, inactive, day 0.
        JsonNode initial = get(managerToken, curveUri, 200);
        assertThat(initial.get("phasedReleaseState").asString()).isEqualTo("INACTIVE");
        assertThat(initial.get("currentDay").asInt()).isZero();
        assertThat(initial.get("percentage").asInt()).isZero();

        // The version releases; the curve walks Apple's fixed 7-day
        // availability curve (1, 2, 5, 10, 20, 50, 100 percent).
        ASC.setVersionState(versionId, "READY_FOR_SALE");
        ASC.advancePhasedDay(versionId);
        assertThat(get(managerToken, curveUri, 200).get("percentage").asInt()).isEqualTo(1);
        ASC.advancePhasedDay(versionId);
        ASC.advancePhasedDay(versionId);
        JsonNode dayThree = get(managerToken, curveUri, 200);
        assertThat(dayThree.get("currentDay").asInt()).isEqualTo(3);
        assertThat(dayThree.get("percentage").asInt()).isEqualTo(5);

        // Pause holds the day; resume continues; halt completes the
        // curve at once (the documented rollback posture).
        JsonNode paused = post(managerToken, curveUri, Map.of("action", "PAUSE"), 200);
        assertThat(paused.get("phasedReleaseState").asString()).isEqualTo("PAUSED");
        assertThat(paused.get("currentDay").asInt()).isEqualTo(3);
        assertThat(ASC.phasedReleaseOf(versionId).state()).isEqualTo("PAUSED");

        JsonNode resumed = post(managerToken, curveUri, Map.of("action", "RESUME"), 200);
        assertThat(resumed.get("phasedReleaseState").asString()).isEqualTo("ACTIVE");

        JsonNode halted = post(managerToken, curveUri, Map.of("action", "HALT"), 200);
        assertThat(halted.get("phasedReleaseState").asString()).isEqualTo("COMPLETE");
        assertThat(halted.get("percentage").asInt()).isEqualTo(100);
        assertThat(ASC.phasedReleaseOf(versionId).state()).isEqualTo("COMPLETE");

        // An action outside the vocabulary is the honest 400.
        post(managerToken, curveUri, Map.of("action", "WARP"), 400);
        assertThat(audit.eventsOfType(AuditEventType.DISTRIBUTION_PHASED_RELEASE_CONTROLLED))
                .hasSize(3);
    }

    // -----------------------------------------------------------------------
    // The delegation matrix and the doctrine boundaries (the tester's
    // gap analysis against #62's pins)
    // -----------------------------------------------------------------------

    @Test
    void anEnvironmentLevelAppleOverrideServesTheDispatchAndAuthenticationKeepsTheRecordDoctrine() {
        // PRD 25's production posture: the production environment binds
        // its OWN apple key (an app+env-level row), and the dispatch
        // signs with THAT row -- a wrong issuer proves the override was
        // the credential, exactly as #62 pinned for the app level.
        putMetadata("en-US", "The banking app your team pilots with.", "banking", null);
        UUID environment = appstoreEnvironment(true);
        Map<String, Object> envOverride = new LinkedHashMap<>();
        envOverride.put("organizationId", org.toString());
        envOverride.put("applicationId", app.toString());
        envOverride.put("environmentId", environment.toString());
        envOverride.put("provider", "apple");
        envOverride.put("name", "Production Apple Wrong Issuer");
        envOverride.put("metadata", Map.of("issuerId", "iss-not-the-mock", "keyId", ASC.keyId()));
        envOverride.put("secretMaterial", ASC.pem());
        put(adminToken, "/api/v1/distribution/credentials", envOverride, 200);

        UUID release = releaseWalkedToActive(environment);
        JsonNode row = recordOf(release);
        // AUTHENTICATION keeps the never-fail doctrine (only a PERMANENT
        // refusal vetoes): the record fails honestly, the crossing
        // committed, and the wire saw the override's key.
        assertThat(row.get("status").asString()).isEqualTo("FAILED");
        assertThat(row.get("failureCode").asString()).isEqualTo("ADAPTER_AUTHENTICATION_FAILED");
        assertThat(jdbc.queryForObject("SELECT status FROM releases WHERE id = ?", String.class, release))
                .isEqualTo("ACTIVE");
    }

    @Test
    void exhaustedRateLimitsOnTheSubmissionKeepTheNeverFailDoctrine() {
        putMetadata("en-US", "The banking app your team pilots with.", "banking", null);
        UUID environment = appstoreEnvironment(true);
        // Every sandbox attempt's provider call answers 429: the record
        // fails with the taxonomy's throttle code and the crossing
        // STILL commits -- only a PERMANENT refusal vetoes (the AC4
        // doctrine's boundary, the #62 rule held on the new channel).
        ASC.throttleNextCalls(3);
        UUID release = releaseWalkedToActive(environment);
        JsonNode row = recordOf(release);
        assertThat(row.get("status").asString()).isEqualTo("FAILED");
        assertThat(row.get("failureCode").asString()).isEqualTo("ADAPTER_RATE_LIMITED");
        assertThat(row.get("attempts").asInt()).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT status FROM releases WHERE id = ?", String.class, release))
                .isEqualTo("ACTIVE");
    }

    @Test
    void keywordsOverTheProviderCapRefuseTheSubmissionWithTheProviderWords() {
        // The authoring surface accepts up to 128 characters; Apple's
        // own contract caps keywords at 100 -- the provider's refusal
        // is the metadata-rejected family, vetoed with Apple's words.
        putMetadata(
                "en-US", "The banking app your team pilots with.", "a-very-long-keyword-list-" + "x".repeat(100), null);
        UUID release = releaseWalkedToDeploying(appstoreEnvironment(true));
        post(
                managerToken,
                "/api/v1/releases/" + release + "/transitions",
                Map.of("applicationId", app.toString(), "to", "ACTIVE"),
                502);
        assertThat(jdbc.queryForObject("SELECT status FROM releases WHERE id = ?", String.class, release))
                .isEqualTo("FAILED");
        JsonNode row = recordOf(release);
        assertThat(row.get("failureDetail").asString()).contains("keywords must not exceed 100");
    }

    @Test
    void theAuthoringSurfaceValidatesItsShapeAndRotatesTheSameLocale() {
        Map<String, Object> badLocale = new LinkedHashMap<>();
        badLocale.put("organizationId", org.toString());
        badLocale.put("applicationId", app.toString());
        badLocale.put("locale", "not a locale");
        badLocale.put("description", "Valid description.");
        put(adminToken, "/api/v1/distribution/appstore/metadata", badLocale, 400);

        Map<String, Object> noDescription = new LinkedHashMap<>();
        noDescription.put("organizationId", org.toString());
        noDescription.put("applicationId", app.toString());
        noDescription.put("locale", "en-US");
        noDescription.put("description", "  ");
        put(adminToken, "/api/v1/distribution/appstore/metadata", noDescription, 400);

        Map<String, Object> unknownApplication = new LinkedHashMap<>();
        unknownApplication.put("organizationId", org.toString());
        unknownApplication.put("applicationId", UUID.randomUUID().toString());
        unknownApplication.put("locale", "en-US");
        unknownApplication.put("description", "Valid description.");
        put(adminToken, "/api/v1/distribution/appstore/metadata", unknownApplication, 400);

        // The rotation: a second write to the same locale replaces the
        // row whole -- ONE row, the second description.
        putMetadata("en-US", "First description.", "first", null);
        putMetadata("en-US", "Second description.", "second", null);
        JsonNode rows = get(adminToken, "/api/v1/distribution/appstore/metadata?organizationId=" + org, 200);
        assertThat(rows.get("items").size()).isEqualTo(1);
        assertThat(rows.get("items").get(0).get("description").asString()).isEqualTo("Second description.");
        assertThat(rows.get("items").get(0).get("keywords").asString()).isEqualTo("second");
    }

    @Test
    void theKeyMaterialNeverSurfacesInAuditAnswersOrRecords() {
        putMetadata("en-US", "The banking app your team pilots with.", "banking", null);
        UUID release = releaseWalkedToActive(appstoreEnvironment(true));
        UUID recordId = recordIdOf(release);
        post(
                managerToken,
                "/api/v1/distribution/records/" + recordId + "/refresh?organizationId=" + org,
                Map.of(),
                200);
        get(
                managerToken,
                "/api/v1/distribution/appstore/releases/" + release + "/phased-release?organizationId=" + org,
                200);

        // The .p8's own base64 body appears in NO audit event, NO
        // record view, and NO curve answer of the whole appstore flow.
        String fragment = ASC.pem()
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "")
                .trim();
        String needle = fragment.substring(40, 90);
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
    void aForeignBundleEventRefreshesNothingAndStillAuditsTheApplication() {
        putMetadata("en-US", "The banking app your team pilots with.", "banking", null);
        UUID release = releaseWalkedToActive(appstoreEnvironment(true));
        String versionId = externalIdOf(release);
        ASC.setVersionState(versionId, "IN_REVIEW");

        // A bundle no application of this platform owns: the consumer
        // resolves nothing to refresh, and the audit carries the honest
        // zero -- the polling pass remains the backstop for everything
        // else. (Driven on the deterministic apply() path; the async
        // wrapper only hands off to it.)
        storeEventConsumer.apply(new com.shipperdog.api.integration.StoreStatusEventMapped(
                "appstore", "APP_STORE_VERSION_STATE", "com.somebody.else.app", java.time.Instant.now()));
        JsonNode row = recordOf(release);
        assertThat(row.get("providerStatus").asString()).isEqualTo("WAITING_FOR_REVIEW");
        assertThat(audit.eventsOfType(AuditEventType.STORE_EVENT_APPLIED)).hasSize(1);
    }

    @Test
    void aReleaseWithoutACurveAnswersTheDomainMissNeverAProviderFault() {
        // phased-release=false: the submission sends MANUAL release
        // type and no curve resource exists store-side. The curve
        // surfaces answer the honest domain 404 verbatim — the sandbox
        // walls the provider call, it never rewrites the platform's
        // own problem (the reviewer's finding, pinned both ways).
        putMetadata("en-US", "The banking app your team pilots with.", "banking", null);
        UUID release = releaseWalkedToActive(appstoreEnvironment(false));
        String curveUri = "/api/v1/distribution/appstore/releases/" + release + "/phased-release?organizationId=" + org;
        JsonNode miss = get(managerToken, curveUri, 404);
        assertThat(miss.get("code").asString()).isEqualTo("DISTRIBUTION_PHASED_RELEASE_NOT_FOUND");
        JsonNode controlMiss = post(managerToken, curveUri, Map.of("action", "PAUSE"), 404);
        assertThat(controlMiss.get("code").asString()).isEqualTo("DISTRIBUTION_PHASED_RELEASE_NOT_FOUND");
        // And no provider call dialed for the control: the miss is the
        // platform's own answer.
        assertThat(ASC.callsOf("PATCH", "/v1/appStoreVersionPhasedReleases/")).isEmpty();
    }

    // -----------------------------------------------------------------------
    // Concealment: the new surfaces conceal foreign organizations
    // -----------------------------------------------------------------------

    @Test
    void theNewSurfacesConcealForeignOrganizations() {
        putMetadata("en-US", "The banking app your team pilots with.", "banking,pilot", null);
        UUID release = releaseWalkedToActive(appstoreEnvironment(true));
        UUID recordId = recordIdOf(release);
        String foreignToken = loginOfOrgRoleIn(
                createOrg("Apple AS Foreign " + UUID.randomUUID()),
                "apple-as-foreign-" + UUID.randomUUID().toString().substring(0, 8),
                "ORG_ADMIN");

        get(foreignToken, "/api/v1/distribution/appstore/metadata?organizationId=" + org, 404);
        get(
                foreignToken,
                "/api/v1/distribution/appstore/releases/" + release + "/phased-release?organizationId=" + org,
                404);
        post(
                foreignToken,
                "/api/v1/distribution/appstore/releases/" + release + "/phased-release?organizationId=" + org,
                Map.of("action", "PAUSE"),
                404);
        post(
                foreignToken,
                "/api/v1/distribution/records/" + recordId + "/refresh?organizationId=" + org,
                Map.of(),
                404);
        // The metadata write with a foreign application: the 400 of the
        // shape, never a layer name.
        Map<String, Object> foreignWrite = new LinkedHashMap<>();
        foreignWrite.put("organizationId", org.toString());
        foreignWrite.put("applicationId", app.toString());
        foreignWrite.put("locale", "fr-FR");
        foreignWrite.put("description", "foreign");
        put(adminToken, "/api/v1/distribution/appstore/metadata", foreignWrite, 200);
        UUID metadataId =
                UUID.fromString(get(adminToken, "/api/v1/distribution/appstore/metadata?organizationId=" + org, 200)
                        .get("items")
                        .get(0)
                        .get("id")
                        .asText());
        exchange(
                        "/api/v1/distribution/appstore/metadata/" + metadataId + "?organizationId=" + org,
                        foreignToken,
                        "DELETE",
                        null)
                .expect(404);
    }

    // -----------------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------------

    private void bindAppleCredential() {
        Map<String, Object> binding = new LinkedHashMap<>();
        binding.put("organizationId", org.toString());
        binding.put("provider", "apple");
        binding.put("name", "Org Apple Key");
        binding.put("metadata", Map.of("issuerId", ASC.issuerId(), "keyId", ASC.keyId()));
        binding.put("secretMaterial", ASC.pem());
        put(adminToken, "/api/v1/distribution/credentials", binding, 200);
    }

    private void putMetadata(String locale, String description, String keywords, String releaseNotes) {
        putMetadataFor(app, locale, description, keywords, releaseNotes);
    }

    private void putMetadataFor(
            UUID applicationId, String locale, String description, String keywords, String releaseNotes) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("organizationId", org.toString());
        body.put("applicationId", applicationId.toString());
        body.put("locale", locale);
        body.put("description", description);
        if (keywords != null) {
            body.put("keywords", keywords);
        }
        if (releaseNotes != null) {
            body.put("releaseNotes", releaseNotes);
        }
        body.put("screenshotRefs", List.of("asc-asset://screens/en-primary"));
        put(adminToken, "/api/v1/distribution/appstore/metadata", body, 200);
    }

    /** Binds the production environment to the appstore channel with the phased-release decision. */
    private UUID appstoreEnvironment(boolean phasedRelease) {
        UUID production = environmentByCode("production");
        patch(adminToken, "/api/v1/environments/" + production, Map.of("distributionChannel", "appstore"), 200);
        put(
                adminToken,
                "/api/v1/environments/" + production + "/config",
                Map.of("entries", Map.of("phased-release", String.valueOf(phasedRelease))),
                200);
        put(
                adminToken,
                "/api/v1/environments/" + production + "/tenants",
                Map.of("tenantIds", List.of(tenantA.toString())),
                200);
        return production;
    }

    /** A fresh release walked DRAFT to ACTIVE through PRD 17's spine. */
    private UUID releaseWalkedToActive(UUID environmentId) {
        return releaseWalkedToActiveWith(environmentId, app, bundleId);
    }

    private UUID releaseWalkedToActiveWith(UUID environmentId, UUID applicationId, String releaseBundleId) {
        UUID release = releaseWalkedToDeployingWith(environmentId, applicationId, releaseBundleId);
        post(
                managerToken,
                "/api/v1/releases/" + release + "/transitions",
                Map.of("applicationId", applicationId.toString(), "to", "ACTIVE"),
                200);
        return release;
    }

    /** The veto tests' walk: DRAFT to DEPLOYING, the ACTIVE crossing left to the test. */
    private UUID releaseWalkedToDeploying(UUID environmentId) {
        return releaseWalkedToDeployingWith(environmentId, app, bundleId);
    }

    private UUID releaseWalkedToDeployingWith(UUID environmentId, UUID applicationId, String releaseBundleId) {
        String name = String.format("%d.%d.%d", 3 + versionCounter / 10, versionCounter % 10, 7);
        versionCounter++;
        UUID version = createVersion(ownerToken, applicationId, name);
        UUID artifact = insertArtifact(version, releaseBundleId, name);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("applicationId", applicationId.toString());
        body.put("versionId", version.toString());
        body.put("ring", "qa");
        body.put("environmentId", environmentId.toString());
        body.put("artifacts", List.of(Map.of("platform", "IOS", "artifactId", artifact.toString())));
        body.put("targets", List.of(Map.of("tenantId", tenantA.toString(), "testerGroupIds", List.of())));
        UUID release = UUID.fromString(
                post(managerToken, "/api/v1/releases", body, 201).get("id").asText());
        for (String step : List.of("VALIDATING", "READY", "APPROVAL_PENDING", "APPROVED", "DEPLOYING")) {
            post(
                    managerToken,
                    "/api/v1/releases/" + release + "/transitions",
                    Map.of("applicationId", applicationId.toString(), "to", step),
                    200);
        }
        return release;
    }

    /** Inserts an artifact row directly (READY) with the extracted identity the submission matches on. */
    private UUID insertArtifact(UUID versionId, String artifactBundleId, String versionName) {
        UUID artifactId = UUID.randomUUID();
        Map<String, Object> owner =
                jdbc.queryForMap("SELECT organization_id, tenant_id FROM versions WHERE id = ?", versionId);
        jdbc.update(
                "INSERT INTO artifacts (id, version_id, platform, file_name, storage_key, sha256, size, "
                        + "build_number, bundle_id, version_name, status, organization_id, tenant_id) "
                        + "VALUES (?, ?, ?::platform, ?, ?, ?, ?, ?, ?, ?, ?::artifact_status, ?, ?)",
                artifactId,
                versionId,
                "IOS",
                "ios-" + versionName + ".ipa",
                "fixture/" + artifactId + "/artifact",
                "b2".repeat(32),
                2048L,
                51L,
                artifactBundleId,
                versionName,
                "READY",
                owner.get("organization_id"),
                owner.get("tenant_id"));
        return artifactId;
    }

    private JsonNode recordOf(UUID releaseId) {
        JsonNode records = get(
                managerToken, "/api/v1/distribution/records?organizationId=" + org + "&releaseId=" + releaseId, 200);
        assertThat(records.get("items").size()).isEqualTo(1);
        return records.get("items").get(0);
    }

    private UUID recordIdOf(UUID releaseId) {
        return UUID.fromString(recordOf(releaseId).get("id").asText());
    }

    private String externalIdOf(UUID releaseId) {
        return recordOf(releaseId).get("externalId").asString();
    }

    private void awaitProviderStatus(UUID releaseId, String expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            JsonNode row = recordOf(releaseId);
            if (expected.equals(row.path("providerStatus").asString(null))) {
                return;
            }
            Thread.sleep(100);
        }
        assertThat(recordOf(releaseId).path("providerStatus").asString(null))
                .as("the webhook-triggered sync should have refreshed the record to %s", expected)
                .isEqualTo(expected);
    }

    private UUID environmentByCode(String code) {
        return jdbc.queryForObject(
                "SELECT id FROM environments WHERE organization_id = ?::uuid AND code = ?",
                UUID.class,
                org.toString(),
                code);
    }

    /** The App Store notification's JWS, signed by the fixture leaf key the receiver's root trusts. */
    private String appStoreJws(String bundleId) throws Exception {
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        X509Certificate leaf;
        X509Certificate root;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("webhooks/appstore-test-leaf.crt")) {
            leaf = (X509Certificate) factory.generateCertificate(in);
        }
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("webhooks/appstore-test-root.crt")) {
            root = (X509Certificate) factory.generateCertificate(in);
        }
        PrivateKey key = ecKey("webhooks/appstore-test-leaf.key");
        String header = "{\"alg\":\"ES256\",\"x5c\":[\""
                + Base64.getEncoder().encodeToString(leaf.getEncoded()) + "\",\""
                + Base64.getEncoder().encodeToString(root.getEncoded()) + "\"]}";
        long now = System.currentTimeMillis() / 1000;
        String payload = "{\"notificationType\":\"APP_STORE_VERSION_STATE\",\"notificationUUID\":\"as-sub-"
                + UUID.randomUUID() + "\",\"signedDate\":" + (now * 1000)
                + ",\"data\":{\"bundleId\":\"" + bundleId + "\"}}";
        Base64.Encoder url = Base64.getUrlEncoder().withoutPadding();
        String encodedHeader = url.encodeToString(header.getBytes(StandardCharsets.UTF_8));
        String encodedPayload = url.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        Signature signer = Signature.getInstance("SHA256withECDSAinP1363Format");
        signer.initSign(key);
        signer.update((encodedHeader + "." + encodedPayload).getBytes(StandardCharsets.US_ASCII));
        return encodedHeader + "." + encodedPayload + "." + url.encodeToString(signer.sign());
    }

    private PrivateKey ecKey(String resource) throws Exception {
        String pem = java.nio.file.Files.readString(java.nio.file.Path.of(
                getClass().getClassLoader().getResource(resource).toURI()));
        String base64 = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        return KeyFactory.getInstance("EC")
                .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
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

    private UUID createTenantApp(String token, UUID tenantId, String name, String appBundleId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenantId", tenantId.toString());
        body.put("name", name);
        body.put("platforms", List.of("IOS", "ANDROID"));
        body.put("bundleId", appBundleId);
        body.put("packageName", appBundleId);
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
    private String loginOfOrgRoleIn(UUID orgId, String loginSlug, String role) {
        String email = loginSlug + "@example.com";
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
                        "Apple AS " + role))
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
