package com.shipperdog.api.distribution;

import static org.assertj.core.api.Assertions.assertThat;

import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.identity.AuditCapture;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The adapter framework's end-to-end proof (issue #61, S13-01): the
 * channel catalog, the credential-delegation matrix (AC2), the
 * never-material discipline (AC3), the adapter-driven records with
 * external ids and the taxonomy (AC4/AC5), and -- through the stub
 * channel registered ONLY by this suite's test configuration -- the
 * no-core-changes proof of adding an adapter (AC6). The Direct
 * channel's zero-portal-change half (AC1) is the artifact module's
 * untouched download suite beside the records this suite pins.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
            "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef"
        })
@TestPropertySource(properties = "shipperdog.distribution.adapters.stub-fixture.retry-backoff=PT0.05S")
@Testcontainers
@Import({AuditCapture.class, DistributionAdapterFrameworkIntegrationTest.StubAdapterConfiguration.class})
class DistributionAdapterFrameworkIntegrationTest {

    private static final String DEV_ADMIN_EMAIL = "dev@shipperdog.local";
    private static final String DEV_ADMIN_PASSWORD = "shipperdog-dev-admin";
    private static final String PASSWORD = "a-perfectly-valid-password";

    /** The stub channel's controllable behavior (the suite flips it per scenario). */
    enum StubMode {
        REPORT_EXTERNAL_IDS,
        TRANSIENT_TWICE_THEN_OK,
        FAIL_AUTHENTICATION,
        THROW_RAW
    }

    static final AtomicReference<StubMode> STUB_MODE = new AtomicReference<>(StubMode.REPORT_EXTERNAL_IDS);
    static final AtomicInteger STUB_CALLS = new AtomicInteger();
    static final AtomicReference<DistributionCommand> STUB_LAST_COMMAND = new AtomicReference<>();

    /**
     * The AC6 proof itself: a complete channel adapter as ONE test
     * bean. The registry discovers it, the catalog lists it, credential
     * resolution asks the chain for its provider, and dispatch runs it
     * under the sandbox -- zero core changes, exactly the story's
     * acceptance criterion.
     */
    static class StubChannelAdapter implements DistributionChannelAdapter {

        @Override
        public String channelId() {
            return "stub-fixture";
        }

        @Override
        public String displayName() {
            return "Stub Fixture Channel";
        }

        @Override
        public String description() {
            return "The framework proof's adapter: registered by a test configuration, discovered with zero core changes.";
        }

        @Override
        public java.util.Set<AdapterCapability> capabilities() {
            return java.util.Set.of(
                    AdapterCapability.BUILD_UPLOAD, AdapterCapability.BUILD_STATUS, AdapterCapability.ROLLOUT_CONTROL);
        }

        @Override
        public java.util.Set<String> credentialProviders() {
            return java.util.Set.of("stub");
        }

        @Override
        public List<AdapterOutcome> distribute(DistributionCommand command) {
            STUB_LAST_COMMAND.set(command);
            int call = STUB_CALLS.incrementAndGet();
            switch (STUB_MODE.get()) {
                case TRANSIENT_TWICE_THEN_OK -> {
                    if (call <= 2) {
                        throw new AdapterException(
                                AdapterException.Category.TRANSIENT,
                                channelId(),
                                "stub upstream hiccup (attempt " + call + ")");
                    }
                }
                case FAIL_AUTHENTICATION -> {
                    throw new AdapterException(
                            AdapterException.Category.AUTHENTICATION, channelId(), "stub credentials rejected");
                }
                case THROW_RAW -> {
                    // Not an AdapterException: a buggy connector's raw
                    // throwable, the sandbox's closed-taxonomy PERMANENT leg.
                    throw new IllegalStateException("stub connector bug: null upstream response");
                }
                default -> {}
            }
            List<AdapterOutcome> outcomes = new ArrayList<>();
            for (DistributionCommand.ArtifactSlot slot : command.artifacts()) {
                outcomes.add(new AdapterOutcome(
                        slot.artifactId(), AdapterOutcome.DISTRIBUTING, "stub-build-" + slot.artifactId(), null, null));
            }
            return outcomes;
        }

        @Override
        public AdapterOutcome status(
                UUID artifactId,
                String channel,
                String externalId,
                com.shipperdog.api.distribution.DistributionCommand.ResolvedCredential credential) {
            return new AdapterOutcome(artifactId, AdapterOutcome.DISTRIBUTED, externalId, null, null);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StubAdapterConfiguration {

        @Bean
        StubChannelAdapter stubChannelAdapter() {
            return new StubChannelAdapter();
        }
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

    private RestClient client;

    private String slug;

    private UUID org;
    private String adminToken;
    private String ownerToken;
    private String managerToken;
    private UUID tenantA;
    private UUID app;
    private int versionCounter;

    @BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
        audit.reset();
        STUB_MODE.set(StubMode.REPORT_EXTERNAL_IDS);
        STUB_CALLS.set(0);
        STUB_LAST_COMMAND.set(null);

        slug = "dist-adapter-" + UUID.randomUUID().toString().substring(0, 8);
        org = createOrg("Dist Adapter Corp " + slug);
        adminToken = loginOfOrgRoleIn(org, slug + "-admin", "ORG_ADMIN");
        ownerToken = loginOfOrgRoleIn(org, slug + "-owner", "APP_OWNER");
        managerToken = loginOfOrgRoleIn(org, slug + "-manager", "RELEASE_MANAGER");
        tenantA = createTenant(adminToken, org, "Dist Adapter Bank", "crm-" + slug + "-a");
        app = createTenantApp(adminToken, tenantA, "Dist Adapter Banking", "com." + slug.replace("-", "") + ".banking");
    }

    // -----------------------------------------------------------------------
    // AC6/AC1: the catalog -- registered adapters, capabilities, providers
    // -----------------------------------------------------------------------

    @Test
    void channelsCatalogExposesEveryRegisteredAdapterWithItsCapabilities() {
        JsonNode channels = get(adminToken, "/api/v1/distribution/channels?organizationId=" + org, 200);
        Map<String, JsonNode> byId = new LinkedHashMap<>();
        channels.get("channels")
                .forEach(channel -> byId.put(channel.get("channelId").asText(), channel));
        assertThat(byId).containsKeys("direct", "stub-fixture");
        assertThat(byId.get("direct").get("capabilities").toString()).contains("DIRECT_ISSUANCE");
        assertThat(byId.get("stub-fixture").get("credentialProviders").toString())
                .contains("stub");
        assertThat(byId.get("direct").get("credentialProviders").isEmpty()).isTrue();

        // The read is org-governed: an unknown organization conceals,
        // and a foreign organization's address conceals identically.
        get(adminToken, "/api/v1/distribution/channels?organizationId=" + UUID.randomUUID(), 404);
    }

    // -----------------------------------------------------------------------
    // AC2: the delegation matrix -- org inherited, app override, env override
    // -----------------------------------------------------------------------

    @Test
    void credentialDelegationMatrixIsFirstDefinedWinsPerProvider() {
        UUID production = environmentByCode("production");
        UUID development = environmentByCode("development");

        // Level 1: the organization default.
        JsonNode orgBinding = bind("apple", null, null, "Org Apple Account", "org-apple-key");
        assertThat(orgBinding.get("level").asText()).isEqualTo("organization");

        // Inherited: any scope without its own apple row resolves the org row.
        assertThat(effective(app, production, "apple").get("binding").get("id").asText())
                .isEqualTo(orgBinding.get("id").asText());

        // Level 2: the application override wins over the organization row.
        JsonNode appBinding = bind("apple", app, null, "App Apple Account", "app-apple-key");
        assertThat(effective(app, null, "apple").get("binding").get("id").asText())
                .isEqualTo(appBinding.get("id").asText());
        // ...but only for its own scope: the org-level answer is unchanged,
        // and a DIFFERENT application still inherits.
        UUID otherApp =
                createTenantApp(adminToken, tenantA, "Dist Adapter Other", "com." + slug.replace("-", "") + ".other");
        assertThat(effective(null, null, "apple").get("binding").get("id").asText())
                .isEqualTo(orgBinding.get("id").asText());
        assertThat(effective(otherApp, null, "apple").get("binding").get("id").asText())
                .isEqualTo(orgBinding.get("id").asText());

        // Level 3: the environment override wins over the application row.
        JsonNode envBinding = bind("apple", app, production, "Prod Apple Account", "prod-apple-key");
        assertThat(effective(app, production, "apple").get("binding").get("id").asText())
                .isEqualTo(envBinding.get("id").asText());
        // Production is not QA -- no, Production is not DEVELOPMENT:
        // the sibling environment still resolves the app row.
        assertThat(effective(app, development, "apple").get("binding").get("id").asText())
                .isEqualTo(appBinding.get("id").asText());
        // The override does not leak upward: the app-only scope keeps the
        // app row, the org-only scope the org row.
        assertThat(effective(app, null, "apple").get("binding").get("id").asText())
                .isEqualTo(appBinding.get("id").asText());

        // Per-provider independence: google-play bound only at the org is
        // inherited at every scope while apple's chain stays overridden.
        bind("google-play", null, null, "Org Play Account", "org-play-key");
        assertThat(effective(app, production, "google-play")
                        .get("binding")
                        .get("name")
                        .asText())
                .isEqualTo("Org Play Account");
        assertThat(effective(app, production, "apple")
                        .get("binding")
                        .get("name")
                        .asText())
                .isEqualTo("Prod Apple Account");
        // A provider bound nowhere answers the honest empty binding.
        assertThat(effective(app, production, "mdm-intune").get("binding").isNull())
                .isTrue();

        // Removing the app override falls back to the org row (the chain heals).
        delete(adminToken, UUID.fromString(appBinding.get("id").asText()));
        assertThat(effective(app, development, "apple").get("binding").get("id").asText())
                .isEqualTo(orgBinding.get("id").asText());
    }

    @Test
    void bindingValidatesItsLevelAndConcealsForeignParents() {
        // An environment binding must name its application (the chain's
        // levels are org, app, app+env -- never env alone).
        UUID production = environmentByCode("production");
        put(
                adminToken,
                "/api/v1/distribution/credentials",
                Map.of(
                        "organizationId", org.toString(),
                        "environmentId", production.toString(),
                        "provider", "apple",
                        "name", "No App",
                        "secretMaterial", "key"),
                400);

        // A foreign application conceals exactly like an unknown one.
        UUID foreignOrg = createOrg("Dist Adapter Foreign " + UUID.randomUUID());
        String foreignAdmin = loginOfOrgRoleIn(foreignOrg, slug + "-foreign", "ORG_ADMIN");
        UUID foreignTenant = createTenant(foreignAdmin, foreignOrg, "Foreign Bank", "crm-" + slug + "-foreign");
        UUID foreignApp = createTenantApp(
                foreignAdmin, foreignTenant, "Foreign App", "com." + slug.replace("-", "") + ".foreign");
        put(
                adminToken,
                "/api/v1/distribution/credentials",
                Map.of(
                        "organizationId", org.toString(),
                        "applicationId", foreignApp.toString(),
                        "provider", "apple",
                        "name", "Foreign App Bind",
                        "secretMaterial", "key"),
                404);
        // Blank material and a malformed provider are the 400s of the shape.
        put(
                adminToken,
                "/api/v1/distribution/credentials",
                Map.of(
                        "organizationId", org.toString(),
                        "provider", "apple",
                        "name", "No Material",
                        "secretMaterial", ""),
                400);
        put(
                adminToken,
                "/api/v1/distribution/credentials",
                Map.of(
                        "organizationId", org.toString(),
                        "provider", "Not Kebab",
                        "name", "Bad Provider",
                        "secretMaterial", "key"),
                400);
    }

    @Test
    void rotationIsAReplacementNeverAMerge() {
        JsonNode first = bind("apple", null, null, "Org Apple", "org-apple-key-1");
        JsonNode second = bind("apple", null, null, "Org Apple Rotated", "org-apple-key-2");
        assertThat(second.get("id").asText()).isEqualTo(first.get("id").asText());
        assertThat(second.get("name").asText()).isEqualTo("Org Apple Rotated");
        assertThat(second.get("secretReference").asText())
                .isNotEqualTo(first.get("secretReference").asText());
        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM store_accounts WHERE organization_id = ?::uuid AND provider = 'apple'",
                Integer.class,
                org.toString());
        assertThat(rows).isEqualTo(1);

        // Rotation hygiene: the replaced material's ciphertext row went
        // with its reference -- one binding, one stored secret, no
        // decryptable orphans.
        Integer stored = jdbc.queryForObject(
                "SELECT count(*) FROM managed_secrets WHERE secret_reference LIKE ?",
                Integer.class,
                "local-envelope://" + org + "/%");
        assertThat(stored).isEqualTo(1);
    }

    // -----------------------------------------------------------------------
    // AC3 + the #58 wiring: references only, and the probe that resolves
    // -----------------------------------------------------------------------

    @Test
    void verifyProbeResolvesThroughTheSelectedStoreAndTheEnvironmentPortIsNowBacked() {
        JsonNode binding = bind("apple", null, null, "Org Apple", "probe-material-value");
        String reference = binding.get("secretReference").asText();

        // The distribution probe answers resolved for the live reference.
        JsonNode verify = post(
                adminToken,
                "/api/v1/distribution/credentials/" + binding.get("id").asText() + "/verify" + "?organizationId=" + org,
                Map.of(),
                200);
        assertThat(verify.get("resolved").asBoolean()).isTrue();

        // The environment module's port (#58's honest stub) now resolves
        // real references: bind the SAME reference as an environment
        // credential and the environment's own probe answers true.
        UUID qa = environmentByCode("qa");
        JsonNode bound = put(
                adminToken,
                "/api/v1/environments/" + qa + "/credentials",
                Map.of("credentials", List.of(Map.of("name", "apple-store", "secretReference", reference))),
                200);
        UUID envCredentialId =
                UUID.fromString(bound.get("credentials").get(0).get("id").asText());
        JsonNode envVerify = post(
                adminToken,
                "/api/v1/environments/" + qa + "/credentials/" + envCredentialId + "/verify",
                Map.of(),
                200);
        assertThat(envVerify.get("resolved").asBoolean()).isTrue();

        // An unknown reference answers false everywhere, indistinguishable
        // from an access-denied one (the #58 doctrine).
        JsonNode rebound = put(
                adminToken,
                "/api/v1/environments/" + qa + "/credentials",
                Map.of(
                        "credentials",
                        List.of(Map.of("name", "apple-store", "secretReference", "local-envelope://nope"))),
                200);
        UUID missCredentialId =
                UUID.fromString(rebound.get("credentials").get(0).get("id").asText());
        JsonNode envMiss = post(
                adminToken,
                "/api/v1/environments/" + qa + "/credentials/" + missCredentialId + "/verify",
                Map.of(),
                200);
        assertThat(envMiss.get("resolved").asBoolean()).isFalse();
    }

    @Test
    void materialNeverSurfacesInAnswersAuditOrColumns() {
        String material = "super-secret-adapter-material";
        JsonNode binding = bind("apple", null, null, "Org Apple", material);

        // No answer echoes it.
        JsonNode credentials = get(adminToken, "/api/v1/distribution/credentials?organizationId=" + org, 200);
        JsonNode effective = get(adminToken, "/api/v1/distribution/credentials/effective?organizationId=" + org, 200);
        post(
                adminToken,
                "/api/v1/distribution/credentials/" + binding.get("id").asText() + "/verify" + "?organizationId=" + org,
                Map.of(),
                200);
        assertThat(credentials.toString()).doesNotContain(material);
        assertThat(effective.toString()).doesNotContain(material);

        // No audit event carries it.
        for (AuditEvent event : audit.events()) {
            assertThat(event.toString()).doesNotContain(material);
        }

        // No store_accounts column value is it (the schema's own proof,
        // at the data level: the row holds the reference only).
        Integer matches = jdbc.queryForObject(
                "SELECT count(*) FROM store_accounts WHERE provider = 'apple' AND ("
                        + " secret_reference = ? OR name = ? )",
                Integer.class,
                material,
                material);
        assertThat(matches).isZero();

        // The envelope store's row is ciphertext, never plaintext.
        String reference = binding.get("secretReference").asText();
        String ciphertext = jdbc.queryForObject(
                "SELECT ciphertext FROM managed_secrets WHERE secret_reference = ?", String.class, reference);
        assertThat(ciphertext).isNotEqualTo(material);
        assertThat(ciphertext).doesNotContain(material);
    }

    // -----------------------------------------------------------------------
    // AC4/AC5: the dispatch -- records, external ids, taxonomy, sandbox
    // -----------------------------------------------------------------------

    @Test
    void activeCrossingDrivesTheDirectChannelAndRecordsIt() {
        UUID release = releaseWalkedToActive(null);

        JsonNode records =
                get(managerToken, "/api/v1/distribution/records?organizationId=" + org + "&releaseId=" + release, 200);
        assertThat(records.get("items").size()).isEqualTo(1);
        JsonNode row = records.get("items").get(0);
        assertThat(row.get("channel").asText()).isEqualTo("direct");
        assertThat(row.get("status").asText()).isEqualTo("DISTRIBUTED");
        assertThat(row.get("attempts").asInt()).isEqualTo(1);
        assertThat(row.get("externalId").isNull()).isTrue();
        assertThat(row.get("failureCode").isNull()).isTrue();
        assertThat(audit.eventsOfType(AuditEventType.DISTRIBUTION_DISPATCHED)).hasSize(1);
    }

    @Test
    void stubAdapterDispatchesWithExternalIdsAndTheResolvedCredential() {
        // The stub channel needs its credential: bind it at the org level
        // and let the dispatch resolve the chain (the AC2 wiring under a
        // real crossing).
        bind("stub", null, null, "Org Stub Account", "stub-secret-material");

        UUID production = environmentByCode("production");
        setEnvironmentChannel(production, "stub-fixture");
        UUID release = releaseWalkedToActive(production);

        JsonNode records =
                get(managerToken, "/api/v1/distribution/records?organizationId=" + org + "&releaseId=" + release, 200);
        assertThat(records.get("items").size()).isEqualTo(1);
        JsonNode row = records.get("items").get(0);
        assertThat(row.get("channel").asText()).isEqualTo("stub-fixture");
        assertThat(row.get("status").asText()).isEqualTo("DISTRIBUTING");
        assertThat(row.get("externalId").asText()).startsWith("stub-build-");
        assertThat(row.get("failureCode").isNull()).isTrue();

        // The command the adapter received: complete, self-contained, and
        // carrying the credential the chain resolved -- material present,
        // redacted in any dump.
        DistributionCommand command = STUB_LAST_COMMAND.get();
        assertThat(command).isNotNull();
        assertThat(command.credential()).isNotNull();
        assertThat(command.credential().material().value()).isEqualTo("stub-secret-material");
        assertThat(command.credential().toString()).doesNotContain("stub-secret-material");
        assertThat(command.artifacts()).hasSize(1);
        assertThat(command.environmentCode()).isEqualTo("production");
        assertThat(command.targetTenantIds()).contains(tenantA);
        assertThat(command.attempt()).isEqualTo(1);
    }

    @Test
    void transientFailuresAreRetriedAndAuthenticationFailuresAreNot() {
        bind("stub", null, null, "Org Stub Account", "stub-secret-material");
        UUID production = environmentByCode("production");
        setEnvironmentChannel(production, "stub-fixture");

        // TRANSIENT twice: the default sandbox (2 retries) lands the
        // third attempt -- attempts counts every sandboxed call.
        STUB_MODE.set(StubMode.TRANSIENT_TWICE_THEN_OK);
        UUID retried = releaseWalkedToActive(production);
        JsonNode retriedRow = recordOf(retried);
        assertThat(retriedRow.get("status").asText()).isEqualTo("DISTRIBUTING");
        assertThat(retriedRow.get("attempts").asInt()).isEqualTo(3);
        assertThat(STUB_CALLS.get()).isEqualTo(3);

        // AUTHENTICATION: never retried (retrying a bad credential is a
        // lockout generator), the record carries the taxonomy code.
        STUB_CALLS.set(0);
        STUB_MODE.set(StubMode.FAIL_AUTHENTICATION);
        UUID rejected = releaseWalkedToActive(production);
        JsonNode rejectedRow = recordOf(rejected);
        assertThat(rejectedRow.get("status").asText()).isEqualTo("FAILED");
        assertThat(rejectedRow.get("failureCode").asText()).isEqualTo("ADAPTER_AUTHENTICATION_FAILED");
        assertThat(rejectedRow.get("attempts").asInt()).isEqualTo(1);
        assertThat(STUB_CALLS.get()).isEqualTo(1);
    }

    @Test
    void anAdapterThrowingARawBugNeverFailsTheCrossingAndIsNeverRetried() {
        bind("stub", null, null, "Org Stub Account", "stub-secret-material");
        UUID production = environmentByCode("production");
        setEnvironmentChannel(production, "stub-fixture");

        // A connector bug outside the taxonomy is PERMANENT: one attempt,
        // FAILED records with the taxonomy's closed code, and the crossing
        // still commits -- the store's bug is not the release's failure.
        STUB_MODE.set(StubMode.THROW_RAW);
        UUID release = releaseWalkedToActive(production);
        assertThat(jdbc.queryForObject("SELECT status FROM releases WHERE id = ?", String.class, release))
                .isEqualTo("ACTIVE");
        JsonNode row = recordOf(release);
        assertThat(row.get("status").asText()).isEqualTo("FAILED");
        assertThat(row.get("failureCode").asText()).isEqualTo("ADAPTER_FAILED");
        assertThat(row.get("attempts").asInt()).isEqualTo(1);
        assertThat(STUB_CALLS.get()).isEqualTo(1);
    }

    @Test
    void anAdapterDeclaringProvidersWithoutABindingFailsItsRecordsWithTheConfigFault() {
        UUID production = environmentByCode("production");
        setEnvironmentChannel(production, "stub-fixture");
        // No stub binding exists: the chain resolves nothing.
        UUID release = releaseWalkedToActive(production);
        JsonNode row = recordOf(release);
        assertThat(row.get("status").asText()).isEqualTo("FAILED");
        assertThat(row.get("failureCode").asText()).isEqualTo("CREDENTIAL_UNRESOLVED");
    }

    @Test
    void unknownChannelFailsTheRecordsButNeverTheCrossing() {
        UUID production = environmentByCode("production");
        setEnvironmentChannel(production, "play");
        UUID release = releaseWalkedToActive(production);
        assertThat(jdbc.queryForObject("SELECT status FROM releases WHERE id = ?", String.class, release))
                .isEqualTo("ACTIVE");
        JsonNode row = recordOf(release);
        assertThat(row.get("status").asText()).isEqualTo("FAILED");
        assertThat(row.get("failureCode").asText()).isEqualTo("CHANNEL_UNKNOWN");
    }

    @Test
    void recordsReadIsOrganizationGoverned() {
        UUID release = releaseWalkedToActive(null);
        // A foreign organization's caller conceals; an unknown release
        // inside the own organization answers the honest empty page.
        String foreignToken = loginOfOrgRoleIn(
                createOrg("Dist Reader Foreign " + UUID.randomUUID()),
                "dist-reader-" + UUID.randomUUID().toString().substring(0, 8),
                "ORG_ADMIN");
        get(foreignToken, "/api/v1/distribution/records?organizationId=" + org + "&releaseId=" + release, 404);
        JsonNode empty = get(
                managerToken,
                "/api/v1/distribution/records?organizationId=" + org + "&releaseId=" + UUID.randomUUID(),
                200);
        assertThat(empty.get("items").isEmpty()).isTrue();
    }

    // -----------------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------------

    private JsonNode recordOf(UUID releaseId) {
        JsonNode records = get(
                managerToken, "/api/v1/distribution/records?organizationId=" + org + "&releaseId=" + releaseId, 200);
        assertThat(records.get("items").size()).isEqualTo(1);
        return records.get("items").get(0);
    }

    private JsonNode effective(UUID applicationId, UUID environmentId, String provider) {
        String uri = "/api/v1/distribution/credentials/effective?organizationId=" + org;
        if (applicationId != null) {
            uri += "&applicationId=" + applicationId;
        }
        if (environmentId != null) {
            uri += "&environmentId=" + environmentId;
        }
        JsonNode chain = get(adminToken, uri, 200);
        for (JsonNode binding : chain.get("chain")) {
            if (provider.equals(binding.get("provider").asText())) {
                return binding;
            }
        }
        // A provider the chain never bound: the honest null-binding row.
        return mapper.createObjectNode().put("binding", (String) null);
    }

    private JsonNode bind(String provider, UUID applicationId, UUID environmentId, String name, String material) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("organizationId", org.toString());
        if (applicationId != null) {
            body.put("applicationId", applicationId.toString());
        }
        if (environmentId != null) {
            body.put("environmentId", environmentId.toString());
        }
        body.put("provider", provider);
        body.put("name", name);
        body.put("secretMaterial", material);
        return put(adminToken, "/api/v1/distribution/credentials", body, 200);
    }

    private void delete(String bearer, UUID credentialId) {
        exchange("/api/v1/distribution/credentials/" + credentialId + "?organizationId=" + org, bearer, "DELETE", null)
                .expect(204);
    }

    private void setEnvironmentChannel(UUID environmentId, String channel) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("distributionChannel", channel);
        patch(adminToken, "/api/v1/environments/" + environmentId, body, 200);
        // The environment's tenant assignments gate release creation that
        // names it (AC4 deny-by-default): admit this world's tenant.
        put(
                adminToken,
                "/api/v1/environments/" + environmentId + "/tenants",
                Map.of("tenantIds", List.of(tenantA.toString())),
                200);
    }

    /** A fresh release walked DRAFT to ACTIVE through PRD 17's spine. */
    private UUID releaseWalkedToActive(UUID environmentId) {
        String name = String.format("%d.%d.%d", 1 + versionCounter / 10, versionCounter % 10, 7);
        versionCounter++;
        UUID version = createVersion(ownerToken, app, name);
        UUID artifact = insertArtifact(version, "IOS", versionCounter);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("applicationId", app.toString());
        body.put("versionId", version.toString());
        body.put("ring", "qa");
        if (environmentId != null) {
            body.put("environmentId", environmentId.toString());
        }
        body.put("artifacts", List.of(Map.of("platform", "IOS", "artifactId", artifact.toString())));
        body.put("targets", List.of(Map.of("tenantId", tenantA.toString(), "testerGroupIds", List.of())));
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

    private UUID environmentByCode(String code) {
        return jdbc.queryForObject(
                "SELECT id FROM environments WHERE organization_id = ?::uuid AND code = ?",
                UUID.class,
                org.toString(),
                code);
    }

    /** Inserts an artifact row directly (READY) -- the release fixture's binary-free shortcut. */
    private UUID insertArtifact(UUID versionId, String platform, long build) {
        UUID artifactId = UUID.randomUUID();
        Map<String, Object> owner =
                jdbc.queryForMap("SELECT organization_id, tenant_id FROM versions WHERE id = ?", versionId);
        String extension = "ANDROID".equals(platform) ? "apk" : "ipa";
        jdbc.update(
                "INSERT INTO artifacts (id, version_id, platform, file_name, storage_key, sha256, size, "
                        + "build_number, status, organization_id, tenant_id) "
                        + "VALUES (?, ?, ?::platform, ?, ?, ?, ?, ?, ?::artifact_status, ?, ?)",
                artifactId,
                versionId,
                platform,
                platform.toLowerCase(Locale.ROOT) + "-" + build + "." + extension,
                "fixture/" + artifactId + "/artifact",
                "a1".repeat(32),
                1024L,
                build,
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

    private UUID createTenantApp(String token, UUID tenantId, String name, String bundleId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenantId", tenantId.toString());
        body.put("name", name);
        body.put("platforms", List.of("IOS", "ANDROID"));
        body.put("bundleId", bundleId);
        body.put("packageName", bundleId);
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
                        "Dist " + role))
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

    /** Decode helper parity with the release suites (unused claims assertions live there). */
    private JsonNode decode(String token) {
        String claims = token.split("\\.")[1];
        return mapper.readTree(Base64.getUrlDecoder().decode(claims));
    }
}
