package com.shipperdog.api.distribution;

import static org.assertj.core.api.Assertions.assertThat;

import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.distribution.internal.apple.AppleAppStoreConnectMock;
import com.shipperdog.api.identity.AuditCapture;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
import tools.jackson.databind.ObjectMapper;

/**
 * The Apple TestFlight connector's end-to-end proof (issue #62,
 * S13-02): the FIRST store adapter on the SPI, run entirely against
 * the contract-faithful mock ASC server — the ES256 API-key
 * conversation (AC1), the group mapping with create-on-demand and
 * idempotent sync (AC2), the ACTIVE-crossing build assignment with
 * the ASC build id as the record's external id (AC3), the beta-review
 * observation and refresh (AC4), the drift report of the safe re-sync
 * (AC5), and Apple's rate limits answered by the sandbox's retried
 * backoff with the quota telemetry (AC6). NO live Apple call anywhere:
 * the connector's base URL points at the mock, whose own JWT
 * verification is the auth model's proof.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
            "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef"
        })
@TestPropertySource(properties = "shipperdog.distribution.adapters.testflight.retry-backoff=PT0.05S")
@Testcontainers
@org.springframework.context.annotation.Import(com.shipperdog.api.identity.AuditCapture.class)
class AppleConnectorIntegrationTest {

    private static final String DEV_ADMIN_EMAIL = "dev@shipperdog.local";
    private static final String DEV_ADMIN_PASSWORD = "shipperdog-dev-admin";
    private static final String PASSWORD = "a-perfectly-valid-password";

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
    private ObjectMapper mapper;

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
    private String bundleId;
    private UUID pilotGroup;
    private UUID testerOne;
    private UUID testerTwo;
    private String appleAppId;
    private String buildId;
    private int versionCounter;

    @BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
        audit.reset();
        ASC.reset();

        slug = "apple-tf-" + UUID.randomUUID().toString().substring(0, 8);
        org = createOrg("Apple TF Corp " + slug);
        adminToken = loginOfOrgRoleIn(org, slug + "-admin", "ORG_ADMIN");
        ownerToken = loginOfOrgRoleIn(org, slug + "-owner", "APP_OWNER");
        managerToken = loginOfOrgRoleIn(org, slug + "-manager", "RELEASE_MANAGER");
        tenantA = createTenant(adminToken, org, "Apple TF Bank", "crm-" + slug);
        bundleId = "com." + slug.replace("-", "") + ".banking";
        app = createTenantApp(adminToken, tenantA, "Apple TF Banking", bundleId);
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

        // The mock's ASC state: the app of the bundle id, the CI-uploaded
        // build, and the same-named external beta group of "Pilot".
        appleAppId = ASC.registerApp(bundleId);
        buildId = ASC.registerBuild(appleAppId, "42", "WAITING_FOR_BETA_REVIEW");
        ASC.registerBuild(appleAppId, "43", "WAITING_FOR_BETA_REVIEW");
        ASC.registerGroup(appleAppId, "Pilot");

        // AC1: the org-stored API key — issuer id and key id in the
        // binding's metadata, the .p8 the material, the reference the
        // row keeps.
        Map<String, Object> binding = new LinkedHashMap<>();
        binding.put("organizationId", org.toString());
        binding.put("provider", "apple");
        binding.put("name", "Org Apple Key");
        binding.put("metadata", Map.of("issuerId", ASC.issuerId(), "keyId", ASC.keyId()));
        binding.put("secretMaterial", ASC.pem());
        put(adminToken, "/api/v1/distribution/credentials", binding, 200);
    }

    // -----------------------------------------------------------------------
    // AC2: the group mapping — create-on-demand, idempotent sync
    // -----------------------------------------------------------------------

    @Test
    void groupLinkBindsTheSameNamedBetaGroupAndPushesMembership() {
        // The link write (createOnDemand true; the same-named group wins).
        JsonNode linked = put(
                adminToken,
                "/api/v1/distribution/testflight/groups",
                Map.of(
                        "organizationId", org.toString(),
                        "applicationId", app.toString(),
                        "groupId", pilotGroup.toString(),
                        "createOnDemand", true),
                200);
        assertThat(linked.get("externalGroupName").asString()).isEqualTo("Pilot");
        assertThat(linked.get("lastSyncedAt").isNull()).isFalse();

        // The mapping view lists the link.
        JsonNode groups = get(adminToken, "/api/v1/distribution/testflight/groups?organizationId=" + org, 200);
        assertThat(groups.get("items").size()).isEqualTo(1);
        assertThat(groups.get("items").get(0).get("groupName").asString()).isEqualTo("Pilot");

        // The link's audit: DISTRIBUTION_GROUP_SYNCED.
        assertThat(audit.eventsOfType(AuditEventType.DISTRIBUTION_GROUP_SYNCED)).isNotEmpty();
    }

    @Test
    void createOnDemandMintsTheGroupWhenNoSameNamedOneExists() {
        // The UAT group has no same-named beta group in the mock.
        UUID uat = groupIdByName(tenantA, "UAT");
        JsonNode linked = put(
                adminToken,
                "/api/v1/distribution/testflight/groups",
                Map.of(
                        "organizationId", org.toString(),
                        "applicationId", app.toString(),
                        "groupId", uat.toString(),
                        "createOnDemand", true),
                200);
        assertThat(linked.get("externalGroupName").asString()).isEqualTo("UAT");
        // The mint is a real POST /v1/betaGroups on the wire.
        assertThat(ASC.callsOf("POST", "/v1/betaGroups")).hasSize(1);

        // Without the option and without a group: the honest 400.
        UUID other = createGroup(adminToken, tenantA, "No Apple Peer");
        put(
                adminToken,
                "/api/v1/distribution/testflight/groups",
                Map.of(
                        "organizationId", org.toString(),
                        "applicationId", app.toString(),
                        "groupId", other.toString(),
                        "createOnDemand", false),
                400);
    }

    @Test
    void syncIsIdempotentAndReportsDriftWithoutRemovingAnything() {
        JsonNode linked = linkPilot();
        UUID linkId = UUID.fromString(linked.get("id").asText());

        // Idempotent: the second sync pushes nothing (everyone is on
        // the group already).
        JsonNode second = post(
                adminToken,
                "/api/v1/distribution/testflight/groups/" + linkId + "/sync?organizationId=" + org,
                Map.of(),
                200);
        assertThat(second.get("pushedEmails").isEmpty()).isTrue();
        assertThat(second.get("driftTesterEmails").isEmpty()).isTrue();

        // Manual ASC work: a tester Apple has that ShipperDog does not.
        ASC.addTesterToGroup(externalGroupIdOf(linked), "manual-console@example.com");
        JsonNode drifted = post(
                adminToken,
                "/api/v1/distribution/testflight/groups/" + linkId + "/sync?organizationId=" + org,
                Map.of(),
                200);
        assertThat(drifted.get("driftTesterEmails").toString()).contains("manual-console@example.com");
        // Safe re-sync: the drift is reported, the tester STAYS on the
        // group (never force-removed), nothing was pushed.
        assertThat(drifted.get("pushedEmails").isEmpty()).isTrue();
        assertThat(ASC.callsOf("POST", "/relationships/betaTesters")).hasSize(1);
    }

    // -----------------------------------------------------------------------
    // AC3: promote-to-ring assigns the build to the mapped groups
    // -----------------------------------------------------------------------

    @Test
    void activeCrossingAssignsTheAscBuildToTheMappedGroupAndRecordsTheExternalId() {
        linkPilot();
        UUID release = releaseWalkedToActive(testflightEnvironment());

        JsonNode row = recordOf(release);
        assertThat(row.get("channel").asString()).isEqualTo("testflight");
        assertThat(row.get("status").asString()).isEqualTo("DISTRIBUTING");
        assertThat(row.get("externalId").asString()).isEqualTo(buildId);
        assertThat(row.get("failureCode").isNull()).isTrue();
        // The provider's own beta-review word rides the record (AC4's
        // first observation, from the dispatch itself).
        assertThat(row.get("providerStatus").asString()).isEqualTo("WAITING_FOR_BETA_REVIEW");

        // The assignment is on the wire: the build POSTed to the group.
        List<AppleAppStoreConnectMock.RecordedCall> assignments = ASC.callsOf("POST", "/relationships/builds");
        assertThat(assignments).hasSize(1);
        assertThat(assignments.get(0).body()).contains(buildId);
        assertThat(assignments.get(0).path()).contains(pilotGroupLinkExternalId());

        // The dispatch is audited (the framework's own event).
        assertThat(audit.eventsOfType(AuditEventType.DISTRIBUTION_DISPATCHED)).hasSize(1);
    }

    @Test
    void aReleaseWithoutGroupLinksFailsItsRecordsWithTheMappingGap() {
        // The Pilot group exists (auto-provisioned) but was never linked.
        UUID release = releaseWalkedToActive(testflightEnvironment());
        JsonNode row = recordOf(release);
        assertThat(row.get("status").asString()).isEqualTo("FAILED");
        assertThat(row.get("failureCode").asString()).isEqualTo("TESTFLIGHT_GROUPS_UNMAPPED");
        // The crossing still committed: store gaps never fail a release.
        assertThat(jdbc.queryForObject("SELECT status FROM releases WHERE id = ?", String.class, release))
                .isEqualTo("ACTIVE");
        // And the wire stayed quiet: no assignment call was made.
        assertThat(ASC.callsOf("POST", "/relationships/builds")).isEmpty();
    }

    @Test
    void aBuildAbsentFromAscFailsItsRecordWithTheHonestCode() {
        linkPilot();
        UUID release = releaseWalkedToActiveWithBuild(testflightEnvironment(), 999);
        JsonNode row = recordOf(release);
        assertThat(row.get("status").asString()).isEqualTo("FAILED");
        assertThat(row.get("failureCode").asString()).isEqualTo("TESTFLIGHT_BUILD_NOT_FOUND");
    }

    // -----------------------------------------------------------------------
    // AC4: the beta-review observation and refresh
    // -----------------------------------------------------------------------

    @Test
    void refreshSurfacesTheBetaReviewJourneyOnTheRecord() {
        linkPilot();
        UUID release = releaseWalkedToActive(testflightEnvironment());
        UUID recordId = UUID.fromString(recordOf(release).get("id").asText());

        // Apple finished the review: the refresh moves the record.
        ASC.setExternalBuildState(buildId, "READY_FOR_BETA_TESTING");
        JsonNode ready = post(
                managerToken,
                "/api/v1/distribution/records/" + recordId + "/refresh?organizationId=" + org,
                Map.of(),
                200);
        assertThat(ready.get("status").asString()).isEqualTo("DISTRIBUTED");
        assertThat(ready.get("providerStatus").asString()).isEqualTo("READY_FOR_BETA_TESTING");
        assertThat(ready.get("providerStatusAt").isNull()).isFalse();
        assertThat(audit.eventsOfType(AuditEventType.DISTRIBUTION_RECORD_REFRESHED))
                .hasSize(1);

        // The records view carries the observation for the dashboard.
        JsonNode records =
                get(managerToken, "/api/v1/distribution/records?organizationId=" + org + "&releaseId=" + release, 200);
        assertThat(records.get("items").get(0).get("providerStatus").asString()).isEqualTo("READY_FOR_BETA_TESTING");

        // A rejection later in the journey: the honest FAILED code.
        ASC.setExternalBuildState(buildId, "BETA_REJECTED");
        JsonNode rejected = post(
                managerToken,
                "/api/v1/distribution/records/" + recordId + "/refresh?organizationId=" + org,
                Map.of(),
                200);
        assertThat(rejected.get("status").asString()).isEqualTo("FAILED");
        assertThat(rejected.get("failureCode").asString()).isEqualTo("TESTFLIGHT_BUILD_REJECTED");
    }

    @Test
    void refreshDriftDetectsAManuallyUnassignedBuild() {
        JsonNode linked = linkPilot();
        UUID release = releaseWalkedToActive(testflightEnvironment());
        UUID recordId = UUID.fromString(recordOf(release).get("id").asText());

        // A manual ASC console removal: the build leaves the group.
        ASC.unassignBuild(externalGroupIdOf(linked), buildId);
        UUID linkId = UUID.fromString(linked.get("id").asText());
        JsonNode sync = post(
                adminToken,
                "/api/v1/distribution/testflight/groups/" + linkId + "/sync?organizationId=" + org,
                Map.of(),
                200);
        assertThat(sync.get("driftBuildIds").toString()).contains(buildId);
    }

    // -----------------------------------------------------------------------
    // AC1/AC6: the failure taxonomy against Apple's own answers
    // -----------------------------------------------------------------------

    @Test
    void rejectedCredentialsAreNeverRetriedAndFailTheRecord() {
        linkPilot();
        ASC.rejectNextToken();
        UUID release = releaseWalkedToActive(testflightEnvironment());
        JsonNode row = recordOf(release);
        assertThat(row.get("status").asString()).isEqualTo("FAILED");
        assertThat(row.get("failureCode").asString()).isEqualTo("ADAPTER_AUTHENTICATION_FAILED");
        assertThat(row.get("attempts").asInt()).isEqualTo(1);
    }

    @Test
    void rateLimitedCallsAreRetriedWithBackoffAndCounted() {
        linkPilot();
        UUID environment = testflightEnvironment();
        // The throttled walk: the first provider call answers Apple's
        // 429, the sandbox retries with backoff, the retry lands, the
        // record walks on -- and the quota telemetry counted the hit.
        double before = meters.counter(
                        com.shipperdog.api.config.MetricNames.ADAPTER_RATE_LIMIT, "channel", "testflight")
                .count();
        ASC.throttleNextCall();
        UUID release = releaseWalkedToActiveWithBuild(environment, 43);
        JsonNode row = recordOf(release);
        assertThat(row.get("status").asString()).isEqualTo("DISTRIBUTING");
        assertThat(row.get("externalId").asString()).startsWith("build-");
        assertThat(row.get("attempts").asInt()).isEqualTo(2);
        double after = meters.counter(com.shipperdog.api.config.MetricNames.ADAPTER_RATE_LIMIT, "channel", "testflight")
                .count();
        assertThat(after - before).isGreaterThanOrEqualTo(1.0);
    }

    @Test
    void anAppLevelBindingOverridesTheOrgRowForTestflightDispatches() {
        // The delegation matrix under a REAL store dispatch: an
        // application-level apple row with a WRONG issuer wins over the
        // organization row -- the 401 that follows is proof the override
        // was the credential the adapter signed with (the org row's
        // issuer is the mock's own and would have passed).
        linkPilot();
        Map<String, Object> override = new LinkedHashMap<>();
        override.put("organizationId", org.toString());
        override.put("applicationId", app.toString());
        override.put("provider", "apple");
        override.put("name", "App Apple Wrong Issuer");
        override.put("metadata", Map.of("issuerId", "iss-not-the-mock", "keyId", ASC.keyId()));
        override.put("secretMaterial", ASC.pem());
        put(adminToken, "/api/v1/distribution/credentials", override, 200);

        UUID release = releaseWalkedToActive(testflightEnvironment());
        JsonNode row = recordOf(release);
        assertThat(row.get("status").asString()).isEqualTo("FAILED");
        assertThat(row.get("failureCode").asString()).isEqualTo("ADAPTER_AUTHENTICATION_FAILED");
    }

    @Test
    void refreshWalksTheHonestProviderStatesIncludingNullAndExpired() {
        linkPilot();
        UUID release = releaseWalkedToActive(testflightEnvironment());
        UUID recordId = UUID.fromString(recordOf(release).get("id").asText());
        String refreshUri = "/api/v1/distribution/records/" + recordId + "/refresh?organizationId=" + org;

        // A null external state (internal-only testing): DISTRIBUTING
        // with the provider's own word carried from the internal leg.
        ASC.setExternalBuildState(buildId, null);
        JsonNode internal = post(managerToken, refreshUri, Map.of(), 200);
        assertThat(internal.get("status").asString()).isEqualTo("DISTRIBUTING");
        assertThat(internal.get("providerStatus").asString()).isEqualTo("WAITING_FOR_BETA_REVIEW");

        // The waiting-for-review word: still DISTRIBUTING (Apple owns
        // the next step).
        ASC.setExternalBuildState(buildId, "WAITING_FOR_BETA_REVIEW");
        JsonNode waiting = post(managerToken, refreshUri, Map.of(), 200);
        assertThat(waiting.get("status").asString()).isEqualTo("DISTRIBUTING");
        assertThat(waiting.get("providerStatus").asString()).isEqualTo("WAITING_FOR_BETA_REVIEW");

        // Expiry late in the journey: the honest FAILED code.
        ASC.setExternalBuildState(buildId, "EXPIRED");
        JsonNode expired = post(managerToken, refreshUri, Map.of(), 200);
        assertThat(expired.get("status").asString()).isEqualTo("FAILED");
        assertThat(expired.get("failureCode").asString()).isEqualTo("TESTFLIGHT_BUILD_EXPIRED");
    }

    @Test
    void exhaustedRateLimitsFailTheRecordWithTheTaxonomyCodeAndCountEveryHit() {
        linkPilot();
        UUID environment = testflightEnvironment();
        double before = meters.counter(
                        com.shipperdog.api.config.MetricNames.ADAPTER_RATE_LIMIT, "channel", "testflight")
                .count();
        // Every sandbox attempt's first provider call answers 429: the
        // exhaustion is the record's honest verdict, and every hit
        // counted (the quota telemetry of AC6).
        ASC.throttleNextCalls(3);
        UUID release = releaseWalkedToActiveWithBuild(environment, 42);
        JsonNode row = recordOf(release);
        assertThat(row.get("status").asString()).isEqualTo("FAILED");
        assertThat(row.get("failureCode").asString()).isEqualTo("ADAPTER_RATE_LIMITED");
        assertThat(row.get("attempts").asInt()).isEqualTo(3);
        double after = meters.counter(com.shipperdog.api.config.MetricNames.ADAPTER_RATE_LIMIT, "channel", "testflight")
                .count();
        assertThat(after - before).isGreaterThanOrEqualTo(3.0);
    }

    @Test
    void theKeyMaterialNeverSurfacesInAuditAnswersOrRecords() {
        linkPilot();
        UUID release = releaseWalkedToActive(testflightEnvironment());
        UUID recordId = UUID.fromString(recordOf(release).get("id").asText());
        post(
                managerToken,
                "/api/v1/distribution/records/" + recordId + "/refresh?organizationId=" + org,
                Map.of(),
                200);

        // The .p8's own base64 body (a distinctive fragment of the
        // generated key) appears in NO audit event, NO record view, and
        // NO mapping answer of the whole flow.
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
    void theNewRoutesConcealForeignOrganizations() {
        linkPilot();
        UUID release = releaseWalkedToActive(testflightEnvironment());
        UUID recordId = UUID.fromString(recordOf(release).get("id").asText());
        JsonNode groups = get(adminToken, "/api/v1/distribution/testflight/groups?organizationId=" + org, 200);
        UUID linkId = UUID.fromString(groups.get("items").get(0).get("id").asText());

        // A foreign organization's caller: every addressed surface
        // conceals exactly like an unknown id (the org standing).
        String foreignToken = loginOfOrgRoleIn(
                createOrg("Apple TF Foreign " + UUID.randomUUID()),
                "apple-tf-foreign-" + UUID.randomUUID().toString().substring(0, 8),
                "ORG_ADMIN");
        post(
                foreignToken,
                "/api/v1/distribution/records/" + recordId + "/refresh?organizationId=" + org,
                Map.of(),
                404);
        post(
                foreignToken,
                "/api/v1/distribution/testflight/groups/" + linkId + "/sync?organizationId=" + org,
                Map.of(),
                404);
        exchange(
                        "/api/v1/distribution/testflight/groups/" + linkId + "?organizationId=" + org,
                        foreignToken,
                        "DELETE",
                        null)
                .expect(404);
        // The link write with a foreign group: the 400 of the shape,
        // never a layer name.
        put(
                adminToken,
                "/api/v1/distribution/testflight/groups",
                Map.of(
                        "organizationId", org.toString(),
                        "applicationId", app.toString(),
                        "groupId", UUID.randomUUID().toString(),
                        "createOnDemand", true),
                400);
    }

    @Test
    void anIncompleteCredentialIsTheConfigurationFaultAndNeverDials() {
        // Material without the metadata triple (issuerId/keyId) is a
        // binding-gap, not a provider conversation: the record fails
        // PERMANENT without a single ASC call.
        Map<String, Object> binding = new LinkedHashMap<>();
        binding.put("organizationId", org.toString());
        binding.put("provider", "apple");
        binding.put("name", "Org Apple No Metadata");
        binding.put("secretMaterial", ASC.pem());
        put(adminToken, "/api/v1/distribution/credentials", binding, 200);
        // (Rotates the org row the fixture bound; the app itself has no
        // other binding, so the dispatch resolves this incomplete one.)

        UUID release = releaseWalkedToActive(testflightEnvironment());
        JsonNode row = recordOf(release);
        assertThat(row.get("status").asString()).isEqualTo("FAILED");
        assertThat(row.get("failureCode").asString()).isEqualTo("ADAPTER_FAILED");
        assertThat(row.get("attempts").asInt()).isEqualTo(1);
        assertThat(ASC.callsOf("GET", "/v1/apps"))
                .as("an incomplete credential never reaches the wire")
                .isEmpty();
    }

    @Test
    void refreshWithoutAResolvableCredentialAnswersTheChainProblemNeverTheProvider() {
        linkPilot();
        UUID release = releaseWalkedToActive(testflightEnvironment());
        UUID recordId = UUID.fromString(recordOf(release).get("id").asText());
        // The binding deleted after the dispatch: the refresh answers
        // the chain's own miss (404 DISTRIBUTION_CREDENTIAL_NOT_FOUND),
        // never a provider call and never a raw NPE.
        JsonNode credentials = get(adminToken, "/api/v1/distribution/credentials?organizationId=" + org, 200);
        UUID credentialId =
                UUID.fromString(credentials.get("items").get(0).get("id").asText());
        exchange(
                        "/api/v1/distribution/credentials/" + credentialId + "?organizationId=" + org,
                        adminToken,
                        "DELETE",
                        null)
                .expect(204);
        JsonNode problem = post(
                managerToken,
                "/api/v1/distribution/records/" + recordId + "/refresh?organizationId=" + org,
                Map.of(),
                404);
        assertThat(problem.get("code").asString()).isEqualTo("DISTRIBUTION_CREDENTIAL_NOT_FOUND");
    }

    @Test
    void anAppLevelOnlyBindingServesTheGroupSyncToo() {
        // PRD 25's matrix held on the SYNC surface: an organization
        // that binds apple only at the application level syncs its
        // links through that row (the reviewer's finding).
        JsonNode credentials = get(adminToken, "/api/v1/distribution/credentials?organizationId=" + org, 200);
        UUID orgCredentialId =
                UUID.fromString(credentials.get("items").get(0).get("id").asText());
        exchange(
                        "/api/v1/distribution/credentials/" + orgCredentialId + "?organizationId=" + org,
                        adminToken,
                        "DELETE",
                        null)
                .expect(204);
        Map<String, Object> appLevel = new LinkedHashMap<>();
        appLevel.put("organizationId", org.toString());
        appLevel.put("applicationId", app.toString());
        appLevel.put("provider", "apple");
        appLevel.put("name", "App Apple Only");
        appLevel.put("metadata", Map.of("issuerId", ASC.issuerId(), "keyId", ASC.keyId()));
        appLevel.put("secretMaterial", ASC.pem());
        put(adminToken, "/api/v1/distribution/credentials", appLevel, 200);

        JsonNode linked = linkPilot();
        assertThat(linked.get("externalGroupName").asString()).isEqualTo("Pilot");
        UUID linkId = UUID.fromString(linked.get("id").asText());
        JsonNode synced = post(
                adminToken,
                "/api/v1/distribution/testflight/groups/" + linkId + "/sync?organizationId=" + org,
                Map.of(),
                200);
        assertThat(synced.get("driftTesterEmails").isEmpty()).isTrue();
    }

    @Test
    void controlCharactersInTheBindingMetadataAreTheConfigurationFault() {
        // issuerId carrying a newline would corrupt the token's JSON
        // payload: the parse refuses it as incomplete, the dispatch
        // answers PERMANENT, and the wire stays quiet.
        Map<String, Object> binding = new LinkedHashMap<>();
        binding.put("organizationId", org.toString());
        binding.put("provider", "apple");
        binding.put("name", "Org Apple Malformed");
        binding.put("metadata", Map.of("issuerId", "iss-bad\nvalue", "keyId", ASC.keyId()));
        binding.put("secretMaterial", ASC.pem());
        put(adminToken, "/api/v1/distribution/credentials", binding, 200);

        UUID release = releaseWalkedToActive(testflightEnvironment());
        JsonNode row = recordOf(release);
        assertThat(row.get("status").asString()).isEqualTo("FAILED");
        assertThat(row.get("failureCode").asString()).isEqualTo("ADAPTER_FAILED");
        assertThat(ASC.callsOf("GET", "/v1/apps")).isEmpty();
    }

    // -----------------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------------

    private JsonNode linkPilot() {
        return put(
                adminToken,
                "/api/v1/distribution/testflight/groups",
                Map.of(
                        "organizationId", org.toString(),
                        "applicationId", app.toString(),
                        "groupId", pilotGroup.toString(),
                        "createOnDemand", true),
                200);
    }

    private String pilotGroupLinkExternalId() {
        return jdbc.queryForObject(
                "SELECT external_group_id FROM channel_group_links WHERE group_id = ?::uuid", String.class, pilotGroup);
    }

    /** The link view's external group id (the PUT answers the link; the sync the wrapper). */
    private String externalGroupIdOf(JsonNode linked) {
        return linked.get("link") == null || linked.get("link").isNull()
                ? linked.get("externalGroupId").asString()
                : linked.get("link").get("externalGroupId").asString();
    }

    private UUID testflightEnvironment() {
        UUID production = environmentByCode("production");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("distributionChannel", "testflight");
        patch(adminToken, "/api/v1/environments/" + production, body, 200);
        put(
                adminToken,
                "/api/v1/environments/" + production + "/tenants",
                Map.of("tenantIds", List.of(tenantA.toString())),
                200);
        return production;
    }

    /** A fresh release walked DRAFT to ACTIVE through PRD 17's spine, targeting the Pilot group. */
    private UUID releaseWalkedToActive(UUID environmentId) {
        return releaseWalkedToActiveWithBuild(environmentId, 42);
    }

    private UUID releaseWalkedToActiveWithBuild(UUID environmentId, long build) {
        String name = String.format("%d.%d.%d", 2 + versionCounter / 10, versionCounter % 10, 7);
        versionCounter++;
        UUID version = createVersion(ownerToken, app, name);
        UUID artifact = insertArtifact(version, "IOS", build);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("applicationId", app.toString());
        body.put("versionId", version.toString());
        body.put("ring", "qa");
        body.put("environmentId", environmentId.toString());
        body.put("artifacts", List.of(Map.of("platform", "IOS", "artifactId", artifact.toString())));
        body.put(
                "targets",
                List.of(Map.of("tenantId", tenantA.toString(), "testerGroupIds", List.of(pilotGroup.toString()))));
        UUID release = UUID.fromString(
                post(managerToken, "/api/v1/releases", body, 201).get("id").asText());
        for (String step : List.of("VALIDATING", "READY", "APPROVAL_PENDING", "APPROVED", "DEPLOYING", "ACTIVE")) {
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

    private UUID createGroup(String bearer, UUID tenantId, String name) {
        return UUID.fromString(
                post(bearer, "/api/v1/tester-groups", Map.of("tenantId", tenantId.toString(), "name", name), 201)
                        .get("id")
                        .asText());
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

    /** Inserts an artifact row directly (READY) with the extracted identity the connector matches on. */
    private UUID insertArtifact(UUID versionId, String platform, long build) {
        UUID artifactId = UUID.randomUUID();
        Map<String, Object> owner =
                jdbc.queryForMap("SELECT organization_id, tenant_id FROM versions WHERE id = ?", versionId);
        jdbc.update(
                "INSERT INTO artifacts (id, version_id, platform, file_name, storage_key, sha256, size, "
                        + "build_number, bundle_id, status, organization_id, tenant_id) "
                        + "VALUES (?, ?, ?::platform, ?, ?, ?, ?, ?, ?, ?::artifact_status, ?, ?)",
                artifactId,
                versionId,
                platform,
                platform.toLowerCase(Locale.ROOT) + "-" + build + ".ipa",
                "fixture/" + artifactId + "/artifact",
                "a1".repeat(32),
                1024L,
                build,
                bundleId,
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

    private UUID createTenantApp(String token, UUID tenantId, String name, String appId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenantId", tenantId.toString());
        body.put("name", name);
        body.put("platforms", List.of("IOS", "ANDROID"));
        body.put("bundleId", appId);
        body.put("packageName", appId);
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
                        "Apple TF " + role))
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
