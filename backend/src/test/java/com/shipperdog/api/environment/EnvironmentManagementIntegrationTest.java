package com.shipperdog.api.environment;

import static org.assertj.core.api.Assertions.assertThat;

import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.identity.AuditCapture;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * End-to-end acceptance flow for environment management (issue #58,
 * S9-04, FR-025, PRD 8.11/16/25): the seeded defaults and custom
 * environments with their lifecycle (AC1), the versioned configuration
 * snapshots with retrieval by version (AC2), the reference-only
 * credential bindings whose schema can never hold material (AC3), the
 * tenant-assignment gate on release creation (AC4), the audited
 * mutations (AC6), and the S9-03 manifest freeze this story feeds —
 * the environment's CODE and the RESOLVED configuration values, never
 * pointers (the #57 follow-up). The secret-resolution leg of AC5
 * (Production is not QA through the resolver) is the companion suite
 * {@code EnvironmentSecretResolutionIntegrationTest}; this suite pins
 * the default unbacked resolver's honest {@code resolved: false}.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
            "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef"
        })
@Testcontainers
@Import(AuditCapture.class)
class EnvironmentManagementIntegrationTest {

    private static final String DEV_ADMIN_EMAIL = "dev@shipperdog.local";
    private static final String DEV_ADMIN_PASSWORD = "shipperdog-dev-admin";
    private static final String PASSWORD = "a-perfectly-valid-password";

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

    @BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
        audit.reset();
    }

    // -----------------------------------------------------------------------
    // AC1: seeded defaults, custom environments, the lifecycle
    // -----------------------------------------------------------------------

    @Test
    void organizationCreationSeedsTheFiveDefaultsInProgressionOrder() throws Exception {
        World world = newWorld("env-seed");

        JsonNode page = get(world.adminToken, "/api/v1/environments?organizationId=" + world.org, 200);
        assertThat(page.get("items").size()).isEqualTo(5);
        List<String> codes = new ArrayList<>();
        List<Integer> positions = new ArrayList<>();
        page.get("items").forEach(item -> {
            codes.add(item.get("code").asText());
            positions.add(item.get("position").asInt());
            assertThat(item.get("status").asText()).isEqualTo("ACTIVE");
            assertThat(item.get("configVersion").asLong()).isZero();
        });
        assertThat(codes).containsExactly("development", "qa", "uat", "pilot", "production");
        assertThat(positions).containsExactly(0, 1, 2, 3, 4);
        // The rows are real and organization-scoped (V27 + the
        // provisioning callback, not just a rendered list).
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM environments WHERE organization_id = ?", Integer.class, world.org))
                .isEqualTo(5);
    }

    @Test
    void customEnvironmentCrudAndLifecycle() throws Exception {
        World world = newWorld("env-crud");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("organizationId", world.org.toString());
        body.put("code", "Staging");
        body.put("name", "Staging");
        body.put("position", 5);
        body.put("distributionChannel", "play-internal");
        body.put("approvalPolicy", "two-person");
        JsonNode created = post(world.ownerToken, "/api/v1/environments", body, 201);
        UUID environmentId = UUID.fromString(created.get("id").asText());
        assertThat(created.get("code").asText())
                .as("the code normalizes to kebab")
                .isEqualTo("staging");
        assertThat(created.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(created.get("distributionChannel").asText()).isEqualTo("play-internal");
        assertThat(created.get("approvalPolicy").asText()).isEqualTo("two-person");

        // One code and one name per organization.
        Map<String, Object> sameCode = new LinkedHashMap<>(body);
        sameCode.put("name", "Another Staging");
        assertThat(post(world.ownerToken, "/api/v1/environments", sameCode, 409)
                        .get("code")
                        .asText())
                .isEqualTo("ENVIRONMENT_ALREADY_EXISTS");
        Map<String, Object> sameName = new LinkedHashMap<>(body);
        sameName.put("code", "staging-eu");
        assertThat(post(world.ownerToken, "/api/v1/environments", sameName, 409)
                        .get("code")
                        .asText())
                .isEqualTo("ENVIRONMENT_ALREADY_EXISTS");
        Map<String, Object> badCode = new LinkedHashMap<>(body);
        badCode.put("code", "Not A Code!");
        assertThat(post(world.ownerToken, "/api/v1/environments", badCode, 400)
                        .get("code")
                        .asText())
                .isEqualTo("INVALID_REQUEST");

        // The PATCH moves the mutable facts and audits the movement.
        JsonNode patched = patch(
                world.ownerToken,
                "/api/v1/environments/" + environmentId,
                Map.of("name", "Staging EU", "position", 6, "approvalPolicy", "three-person"),
                200);
        assertThat(patched.get("name").asText()).isEqualTo("Staging EU");
        assertThat(patched.get("position").asInt()).isEqualTo(6);
        assertThat(patched.get("approvalPolicy").asText()).isEqualTo("three-person");
        assertThat(patched.get("code").asText()).isEqualTo("staging");
        assertThat(audit.eventsOfType(AuditEventType.ENVIRONMENT_UPDATED)).anySatisfy(event -> {
            assertThat(event.detail()).containsEntry("name", "Staging EU");
            assertThat(event.detail()).containsEntry("position", "6");
        });

        // The lifecycle park: DISABLED refuses every other mutation.
        JsonNode parked = put(
                world.ownerToken,
                "/api/v1/environments/" + environmentId + "/status",
                Map.of("status", "DISABLED", "reason", "retired until the eu rollout"),
                200);
        assertThat(parked.get("status").asText()).isEqualTo("DISABLED");
        assertThat(audit.eventsOfType(AuditEventType.ENVIRONMENT_STATUS_CHANGED))
                .anySatisfy(event -> {
                    assertThat(event.detail()).containsEntry("from", "ACTIVE");
                    assertThat(event.detail()).containsEntry("to", "DISABLED");
                    assertThat(event.detail()).containsKey("reason");
                });
        assertThat(patch(world.ownerToken, "/api/v1/environments/" + environmentId, Map.of("name", "Nope"), 409)
                        .get("code")
                        .asText())
                .isEqualTo("ENVIRONMENT_DISABLED");
        assertThat(put(
                                world.ownerToken,
                                "/api/v1/environments/" + environmentId + "/config",
                                Map.of("entries", Map.of()),
                                409)
                        .get("code")
                        .asText())
                .isEqualTo("ENVIRONMENT_DISABLED");

        // The self-crossing is the precise conflict; reactivation works.
        assertThat(put(
                                world.ownerToken,
                                "/api/v1/environments/" + environmentId + "/status",
                                Map.of("status", "DISABLED"),
                                409)
                        .get("errors")
                        .get("current")
                        .asText())
                .isEqualTo("DISABLED");
        assertThat(put(
                                world.ownerToken,
                                "/api/v1/environments/" + environmentId + "/status",
                                Map.of("status", "ACTIVE"),
                                200)
                        .get("status")
                        .asText())
                .isEqualTo("ACTIVE");
    }

    // -----------------------------------------------------------------------
    // AC2: versioned configuration snapshots
    // -----------------------------------------------------------------------

    @Test
    void configurationSnapshotsAppendAndResolveByVersion() throws Exception {
        World world = newWorld("env-config");
        UUID environmentId = environmentByCode(world.adminToken, world.org, "pilot");

        // Never configured: version 0, the honest empty.
        JsonNode empty = get(world.adminToken, "/api/v1/environments/" + environmentId + "/config", 200);
        assertThat(empty.get("version").asLong()).isZero();
        assertThat(empty.get("entries").size()).isZero();

        JsonNode v1 = put(
                world.ownerToken,
                "/api/v1/environments/" + environmentId + "/config",
                Map.of("entries", Map.of("api.endpoint", "https://pilot.api", "flag.dark-mode", "true")),
                200);
        assertThat(v1.get("version").asLong()).isEqualTo(1L);
        assertThat(v1.get("entries").get("api.endpoint").asText()).isEqualTo("https://pilot.api");

        JsonNode v2 = put(
                world.ownerToken,
                "/api/v1/environments/" + environmentId + "/config",
                Map.of(
                        "entries",
                        Map.of("api.endpoint", "https://pilot.api", "flag.dark-mode", "false", "flag.search", "on")),
                200);
        assertThat(v2.get("version").asLong()).isEqualTo(2L);

        // The current read answers v2; the by-version read answers the
        // frozen v1 snapshot (AC2's retrieval).
        assertThat(get(world.ownerToken, "/api/v1/environments/" + environmentId + "/config", 200)
                        .get("version")
                        .asLong())
                .isEqualTo(2L);
        JsonNode snapshotOne =
                get(world.ownerToken, "/api/v1/environments/" + environmentId + "/config?version=1", 200);
        assertThat(snapshotOne.get("version").asLong()).isEqualTo(1L);
        assertThat(snapshotOne.get("entries").get("flag.dark-mode").asText()).isEqualTo("true");
        assertThat(snapshotOne.get("entries").has("flag.search")).isFalse();

        // Unknown version: the honest state-dependent miss.
        assertThat(get(world.ownerToken, "/api/v1/environments/" + environmentId + "/config?version=3", 404)
                        .get("code")
                        .asText())
                .isEqualTo("ENVIRONMENT_CONFIG_VERSION_NOT_FOUND");

        // The ledger: chronological, one row per write.
        JsonNode ledger = get(world.adminToken, "/api/v1/environments/" + environmentId + "/config/versions", 200);
        assertThat(ledger.get("items").size()).isEqualTo(2);
        assertThat(ledger.get("items").get(0).get("version").asLong()).isEqualTo(1L);
        assertThat(ledger.get("items").get(0).get("keyCount").asInt()).isEqualTo(2);
        assertThat(ledger.get("items").get(1).get("keyCount").asInt()).isEqualTo(3);

        // Shape violations refuse with the 400.
        assertThat(put(
                                world.ownerToken,
                                "/api/v1/environments/" + environmentId + "/config",
                                Map.of("entries", Map.of("Bad Key", "x")),
                                400)
                        .get("code")
                        .asText())
                .isEqualTo("INVALID_REQUEST");

        // The audit carries the per-key movement of the last write.
        assertThat(audit.eventsOfType(AuditEventType.ENVIRONMENT_CONFIG_UPDATED))
                .anySatisfy(event -> {
                    assertThat(event.detail()).containsEntry("version", "2");
                    assertThat(event.detail().get("added")).contains("flag.search");
                    assertThat(event.detail().get("changed")).contains("flag.dark-mode");
                });
    }

    // -----------------------------------------------------------------------
    // AC3: reference-only credential bindings
    // -----------------------------------------------------------------------

    @Test
    void credentialBindingsStoreReferencesOnlyAndVerifyHonestly() throws Exception {
        World world = newWorld("env-creds");
        UUID environmentId = environmentByCode(world.adminToken, world.org, "production");

        JsonNode bound = put(
                world.ownerToken,
                "/api/v1/environments/" + environmentId + "/credentials",
                Map.of(
                        "credentials",
                        List.of(
                                Map.of("name", "apple-store", "secretReference", "projects/demo/secrets/prod-appstore"),
                                Map.of(
                                        "name",
                                        "api-signing",
                                        "secretReference",
                                        "projects/demo/secrets/prod-signing"))),
                200);
        assertThat(bound.get("credentials").size()).isEqualTo(2);
        // The view is name-ordered; find the binding by its label.
        JsonNode appleStore = null;
        for (JsonNode binding : bound.get("credentials")) {
            if ("apple-store".equals(binding.get("name").asText())) {
                appleStore = binding;
            }
        }
        assertThat(appleStore).isNotNull();
        assertThat(appleStore.get("secretReference").asText()).isEqualTo("projects/demo/secrets/prod-appstore");

        // The schema-review half of AC3: the binding rows carry ONLY the
        // reference — no column exists that could hold material.
        List<String> columns = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = 'environment_credentials'",
                String.class);
        assertThat(columns)
                .containsExactlyInAnyOrder(
                        "id", "environment_id", "name", "secret_reference", "created_at", "updated_at");
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT secret_reference FROM environment_credentials "
                        + "WHERE environment_id = ? AND name = 'apple-store'",
                environmentId);
        assertThat((String) row.get("secret_reference")).isEqualTo("projects/demo/secrets/prod-appstore");

        // A rotation keeps the slot and re-points the reference.
        JsonNode rotated = put(
                world.ownerToken,
                "/api/v1/environments/" + environmentId + "/credentials",
                Map.of(
                        "credentials",
                        List.of(
                                Map.of(
                                        "name",
                                        "apple-store",
                                        "secretReference",
                                        "projects/demo/secrets/prod-appstore-v2"),
                                Map.of(
                                        "name",
                                        "api-signing",
                                        "secretReference",
                                        "projects/demo/secrets/prod-signing"))),
                200);
        assertThat(rotated.get("credentials").size()).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM environment_credentials WHERE environment_id = ?",
                        Integer.class,
                        environmentId))
                .isEqualTo(2);

        // Shape violations: bad names, whitespace-bearing references.
        assertThat(put(
                                world.ownerToken,
                                "/api/v1/environments/" + environmentId + "/credentials",
                                Map.of("credentials", List.of(Map.of("name", "Bad Name", "secretReference", "x"))),
                                400)
                        .get("code")
                        .asText())
                .isEqualTo("INVALID_REQUEST");
        assertThat(put(
                                world.ownerToken,
                                "/api/v1/environments/" + environmentId + "/credentials",
                                Map.of(
                                        "credentials",
                                        List.of(Map.of("name", "ok-name", "secretReference", "has space"))),
                                400)
                        .get("code")
                        .asText())
                .isEqualTo("INVALID_REQUEST");

        // The verify probe through the DEFAULT resolver: wired, honestly
        // unbacked — and never a word about material.
        UUID credentialId = jdbc.queryForObject(
                "SELECT id FROM environment_credentials WHERE environment_id = ? AND name = 'apple-store'",
                UUID.class,
                environmentId);
        JsonNode verified = post(
                world.ownerToken,
                "/api/v1/environments/" + environmentId + "/credentials/" + credentialId + "/verify",
                Map.of(),
                200);
        assertThat(verified.get("resolved").asBoolean()).isFalse();
        assertThat(verified.toString()).doesNotContain("material");

        assertThat(audit.eventsOfType(AuditEventType.ENVIRONMENT_CREDENTIALS_BOUND))
                .anySatisfy(event -> assertThat(event.detail().get("repointed")).contains("apple-store"));
        assertThat(audit.eventsOfType(AuditEventType.ENVIRONMENT_CREDENTIAL_VERIFIED))
                .anySatisfy(event -> {
                    assertThat(event.detail()).containsEntry("name", "apple-store");
                    assertThat(event.detail()).containsEntry("resolved", "false");
                });
    }

    // -----------------------------------------------------------------------
    // AC4: the assignment gate on release creation
    // -----------------------------------------------------------------------

    @Test
    void releaseCreationValidatesEnvironmentAndAssignments() throws Exception {
        // The shared world: both tenants HOLD the application through
        // ACTIVE authorizations (S3-03), so the only remaining gate on
        // tenantA2 is the environment assignment itself.
        World world = newWorld("env-release");
        authorize(world.adminToken, world.app, world.tenantA);
        authorize(world.adminToken, world.app, world.tenantA2);
        UUID pilotId = environmentByCode(world.adminToken, world.org, "pilot");
        put(
                world.ownerToken,
                "/api/v1/environments/" + pilotId + "/tenants",
                Map.of("tenantIds", List.of(world.tenantA.toString())),
                200);

        // Assigned tenant: creation carries the environment end to end.
        JsonNode created = post(
                world.managerToken,
                "/api/v1/releases",
                releaseBody(world, pilotId, List.of(targetOf(world.tenantA))),
                201);
        assertThat(created.get("environment").asText()).isEqualTo("pilot");
        assertThat(created.get("environmentName").asText()).isEqualTo("Pilot");
        assertThat(UUID.fromString(created.get("environmentId").asText())).isEqualTo(pilotId);
        assertThat(audit.eventsOfType(AuditEventType.RELEASE_CREATED))
                .anySatisfy(event -> assertThat(event.detail()).containsEntry("environment", "pilot"));
        JsonNode listed = get(world.managerToken, "/api/v1/releases?applicationId=" + world.app, 200);
        assertThat(listed.get("items").get(0).get("environment").asText()).isEqualTo("pilot");

        // Unassigned tenant: the per-tenant 422 naming the environment gate.
        JsonNode refused = post(
                world.managerToken,
                "/api/v1/releases",
                releaseBody(world, pilotId, List.of(targetOf(world.tenantA2))),
                422);
        assertThat(refused.get("code").asText()).isEqualTo("RELEASE_TARGETS_INVALID");
        assertThat(refused.get("errors").get(world.tenantA2.toString()).asText())
                .contains("not assigned to the release environment");

        // A foreign organization's environment conceals exactly like an
        // unknown id (byte-identical modulo the trace id).
        World foreign = newWorld("env-release-foreign");
        UUID foreignPilot = environmentByCode(foreign.adminToken, foreign.org, "pilot");
        JsonNode foreignMiss = post(
                world.managerToken,
                "/api/v1/releases",
                releaseBody(world, foreignPilot, List.of(targetOf(world.tenantA))),
                404);
        JsonNode unknownMiss = post(
                world.managerToken,
                "/api/v1/releases",
                releaseBody(world, UUID.randomUUID(), List.of(targetOf(world.tenantA))),
                404);
        assertThat(foreignMiss.get("code").asText()).isEqualTo("ENVIRONMENT_NOT_FOUND");
        assertThat(problemOf(foreignMiss)).isEqualTo(problemOf(unknownMiss));

        // A disabled environment may not be named.
        put(world.adminToken, "/api/v1/environments/" + pilotId + "/status", Map.of("status", "DISABLED"), 200);
        UUID otherVersion = createVersion(world.ownerToken, world.app, "2.0.0");
        insertArtifact(otherVersion, "IOS", 3, "READY");
        Map<String, Object> body = releaseBody(world, pilotId, List.of(targetOf(world.tenantA)));
        body.put("versionId", otherVersion.toString());
        assertThat(post(world.managerToken, "/api/v1/releases", body, 409)
                        .get("code")
                        .asText())
                .isEqualTo("ENVIRONMENT_DISABLED");
    }

    @Test
    void tenantAssignmentsReplaceTheSetAndRefuseForeignTenants() throws Exception {
        World world = newWorld("env-tenants");
        UUID qaId = environmentByCode(world.adminToken, world.org, "qa");

        JsonNode assigned = put(
                world.ownerToken,
                "/api/v1/environments/" + qaId + "/tenants",
                Map.of("tenantIds", List.of(world.tenantA.toString())),
                200);
        assertThat(assigned.get("tenantIds").size()).isEqualTo(1);

        JsonNode view = get(world.adminToken, "/api/v1/environments/" + qaId + "/tenants", 200);
        assertThat(view.get("items").size()).isEqualTo(1);
        assertThat(view.get("items").get(0).get("tenantId").asText()).isEqualTo(world.tenantA.toString());
        assertThat(view.get("items").get(0).get("name").asText()).isNotBlank();

        // The replace-set swap: tenantA out, tenantA2 in.
        put(
                world.ownerToken,
                "/api/v1/environments/" + qaId + "/tenants",
                Map.of("tenantIds", List.of(world.tenantA2.toString())),
                200);
        assertThat(jdbc.queryForList(
                        "SELECT tenant_id FROM environment_tenants WHERE environment_id = ? ORDER BY tenant_id",
                        UUID.class,
                        qaId))
                .containsExactly(world.tenantA2);

        // Unknown and foreign tenants refuse with the IDENTICAL message
        // (the refusal never confirms existence), one 422 for the set.
        UUID foreignTenant = createTenantInForeignOrganization("env-tenants");
        JsonNode refused = put(
                world.ownerToken,
                "/api/v1/environments/" + qaId + "/tenants",
                Map.of("tenantIds", List.of(UUID.randomUUID().toString(), foreignTenant.toString())),
                422);
        assertThat(refused.get("code").asText()).isEqualTo("ENVIRONMENT_TENANTS_INVALID");
        List<String> messages = new ArrayList<>();
        refused.get("errors")
                .properties()
                .forEach(entry -> messages.add(entry.getValue().asText()));
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0)).isEqualTo(messages.get(1));

        assertThat(audit.eventsOfType(AuditEventType.ENVIRONMENT_TENANTS_ASSIGNED))
                .isNotEmpty();
    }

    // -----------------------------------------------------------------------
    // The manifest freeze: resolved values, never pointers
    // -----------------------------------------------------------------------

    @Test
    void manifestFreezesEnvironmentCodeAndResolvedConfiguration() throws Exception {
        World world = newWorld("env-manifest");
        authorize(world.adminToken, world.app, world.tenantA);
        UUID pilotId = environmentByCode(world.adminToken, world.org, "pilot");
        put(
                world.ownerToken,
                "/api/v1/environments/" + pilotId + "/config",
                Map.of("entries", Map.of("api.endpoint", "https://pilot.api", "flag.dark-mode", "true")),
                200);
        put(
                world.ownerToken,
                "/api/v1/environments/" + pilotId + "/config",
                Map.of("entries", Map.of("api.endpoint", "https://pilot.api/v2", "flag.dark-mode", "false")),
                200);
        put(
                world.ownerToken,
                "/api/v1/environments/" + pilotId + "/tenants",
                Map.of("tenantIds", List.of(world.tenantA.toString())),
                200);

        UUID releaseId = releaseIdOf(post(
                world.managerToken,
                "/api/v1/releases",
                releaseBody(world, pilotId, List.of(targetOf(world.tenantA))),
                201));
        walkToApproved(world.managerToken, world.app, releaseId);

        JsonNode manifest =
                get(world.managerToken, "/api/v1/releases/" + releaseId + "/manifest?applicationId=" + world.app, 200);
        JsonNode document = manifest.get("manifest");
        assertThat(document.get("environment").asText()).isEqualTo("pilot");
        JsonNode configuration = document.get("configuration");
        assertThat(configuration.get("configVersion").asLong()).isEqualTo(2L);
        assertThat(configuration.get("featureFlags").get("api.endpoint").asText())
                .isEqualTo("https://pilot.api/v2");
        assertThat(configuration.get("featureFlags").get("flag.dark-mode").asText())
                .isEqualTo("false");
        assertThat(configuration.get("tenantConfig").isNull()).isTrue();

        // The stored document is verified-and-served (the digest holds)
        // and renders as YAML with the same frozen content.
        String yaml = client.get()
                .uri("/api/v1/releases/" + releaseId + "/manifest?applicationId=" + world.app + "&format=yaml")
                .headers(headers -> headers.setBearerAuth(world.managerToken))
                .retrieve()
                .body(String.class);
        assertThat(yaml).contains("environment: \"pilot\"");

        // A post-freeze config drift never rewrites the frozen document.
        put(
                world.ownerToken,
                "/api/v1/environments/" + pilotId + "/config",
                Map.of("entries", Map.of("api.endpoint", "https://pilot.api/v3")),
                200);
        JsonNode stillFrozen =
                get(world.managerToken, "/api/v1/releases/" + releaseId + "/manifest?applicationId=" + world.app, 200);
        assertThat(stillFrozen
                        .get("manifest")
                        .get("configuration")
                        .get("configVersion")
                        .asLong())
                .isEqualTo(2L);

        // A release naming no environment freezes the honest nulls.
        UUID otherVersion = createVersion(world.ownerToken, world.app, "2.0.0");
        UUID otherArtifact = insertArtifact(otherVersion, "IOS", 4, "READY");
        Map<String, Object> noEnvBody = releaseBody(world, null, List.of(targetOf(world.tenantA)));
        noEnvBody.put("versionId", otherVersion.toString());
        noEnvBody.put("artifacts", List.of(Map.of("platform", "IOS", "artifactId", otherArtifact.toString())));
        UUID plainReleaseId = releaseIdOf(post(world.managerToken, "/api/v1/releases", noEnvBody, 201));
        walkToApproved(world.managerToken, world.app, plainReleaseId);
        JsonNode plainManifest = get(
                world.managerToken, "/api/v1/releases/" + plainReleaseId + "/manifest?applicationId=" + world.app, 200);
        assertThat(plainManifest.get("manifest").get("environment").isNull()).isTrue();
        assertThat(plainManifest
                        .get("manifest")
                        .get("configuration")
                        .get("configVersion")
                        .isNull())
                .isTrue();
    }

    // -----------------------------------------------------------------------
    // Concealment: the item routes' foreign shapes
    // -----------------------------------------------------------------------

    @Test
    void foreignEnvironmentsConcealExactlyLikeUnknownOnes() throws Exception {
        World world = newWorld("env-conceal");
        World foreign = newWorld("env-conceal-foreign");
        // WORLD's pilot, probed by the FOREIGN organization's admin.
        UUID worldPilot = environmentByCode(world.adminToken, world.org, "pilot");

        // A foreign organization's admin reads another organization's
        // environment: the concealed denial is byte-identical to the
        // genuine miss on every read surface.
        for (String suffix : new String[] {"", "/config", "/config/versions", "/tenants", "/credentials"}) {
            JsonNode foreignAnswer = get(foreign.adminToken, "/api/v1/environments/" + worldPilot + suffix, 404);
            JsonNode unknownAnswer = get(foreign.adminToken, "/api/v1/environments/" + UUID.randomUUID() + suffix, 404);
            assertThat(foreignAnswer.get("code").asText())
                    .as("surface " + suffix)
                    .isEqualTo("ENVIRONMENT_NOT_FOUND");
            assertThat(problemOf(foreignAnswer)).as("surface " + suffix).isEqualTo(problemOf(unknownAnswer));
        }

        // The collection route conceals at the organization (the
        // testers-module shape), and the tenant persona conceals too.
        assertThat(get(foreign.adminToken, "/api/v1/environments?organizationId=" + world.org, 404)
                        .get("code")
                        .asText())
                .isEqualTo("ORGANIZATION_NOT_FOUND");
        String tenantAdmin = loginOfTenantAdminRoleIn(world.org, world.tenantA, "env-conceal-tenant-admin");
        assertThat(get(tenantAdmin, "/api/v1/environments?organizationId=" + world.org, 404)
                        .get("code")
                        .asText())
                .isEqualTo("ORGANIZATION_NOT_FOUND");
    }

    // -----------------------------------------------------------------------
    // Fixture world
    // -----------------------------------------------------------------------

    private static final class World {
        UUID org;
        String adminToken;
        String ownerToken;
        String managerToken;
        UUID tenantA;
        UUID tenantA2;
        UUID app;
        UUID version;
    }

    /** One organization with the seeded environments, a tenant-owned application, and a releasable version. */
    private World newWorld(String slug) throws Exception {
        World world = new World();
        world.org = createOrg("Env " + slug + " Corp");
        world.adminToken = loginOfOrgRoleIn(world.org, slug + "-admin", "ORG_ADMIN");
        world.ownerToken = loginOfOrgRoleIn(world.org, slug + "-owner", "APP_OWNER");
        world.managerToken = loginOfOrgRoleIn(world.org, slug + "-manager", "RELEASE_MANAGER");
        world.tenantA = createTenant(world.adminToken, world.org, "Env " + slug + " Bank", "crm-" + slug + "-a");
        world.tenantA2 = createTenant(world.adminToken, world.org, "Env " + slug + " Bank 2", "crm-" + slug + "-a2");
        Map<String, Object> body = new HashMap<>();
        body.put("ownershipScope", "ORGANIZATION");
        body.put("organizationId", world.org.toString());
        body.put("name", "Env " + slug + " Banking");
        body.put("platforms", List.of("IOS", "ANDROID"));
        body.put("bundleId", "com." + slug.replace("-", "") + ".banking");
        body.put("packageName", "com." + slug.replace("-", "") + ".banking");
        world.app = UUID.fromString(post(world.adminToken, "/api/v1/applications", body, 201)
                .get("id")
                .asText());
        world.version = createVersion(world.ownerToken, world.app, "1.0.0");
        insertArtifact(world.version, "IOS", 1, "READY");
        return world;
    }

    /** The seeded environment's id by code (the defaults exist per organization). */
    private UUID environmentByCode(String token, UUID organizationId, String code) {
        return jdbc.queryForObject(
                "SELECT id FROM environments WHERE organization_id = ? AND code = ?", UUID.class, organizationId, code);
    }

    private Map<String, Object> releaseBody(World world, UUID environmentId, List<Map<String, Object>> targets) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("applicationId", world.app.toString());
        body.put("versionId", world.version.toString());
        body.put("ring", "qa");
        if (environmentId != null) {
            body.put("environmentId", environmentId.toString());
        }
        body.put(
                "artifacts",
                List.of(Map.of(
                        "platform",
                        "IOS",
                        "artifactId",
                        artifactOf(world.version).toString())));
        body.put("targets", new ArrayList<>(targets));
        return body;
    }

    private Map<String, Object> targetOf(UUID tenantId) {
        return Map.of("tenantId", tenantId.toString(), "testerGroupIds", List.of());
    }

    private UUID artifactOf(UUID versionId) {
        return jdbc.queryForObject(
                "SELECT id FROM artifacts WHERE version_id = ? ORDER BY build_number LIMIT 1", UUID.class, versionId);
    }

    private UUID releaseIdOf(JsonNode created) {
        return UUID.fromString(created.get("id").asText());
    }

    /** Walks a DRAFT release along PRD 17's spine to APPROVED (the freeze crossing). */
    private void walkToApproved(String token, UUID applicationId, UUID releaseId) {
        for (String state : List.of("VALIDATING", "READY", "APPROVAL_PENDING", "APPROVED")) {
            post(
                    token,
                    "/api/v1/releases/" + releaseId + "/transitions",
                    Map.of("applicationId", applicationId.toString(), "to", state),
                    200);
        }
    }

    /**
     * The problem envelope minus the per-request members (trace id and
     * instance URI — both differ between two calls by design); the
     * byte-parity comparator of the concealment probes.
     */
    private Map<String, Object> problemOf(JsonNode problem) {
        Map<String, Object> withoutVolatile = new LinkedHashMap<>();
        problem.properties().forEach(entry -> {
            if (!"trace_id".equals(entry.getKey()) && !"instance".equals(entry.getKey())) {
                withoutVolatile.put(entry.getKey(), entry.getValue().toString());
            }
        });
        return withoutVolatile;
    }

    /** Inserts an artifact row directly (READY) — the release fixture's binary-free shortcut. */
    private UUID insertArtifact(UUID versionId, String platform, long build, String status) {
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
                "b2".repeat(32),
                1024L,
                build,
                status,
                owner.get("organization_id"),
                owner.get("tenant_id"));
        return artifactId;
    }

    /** Grants the tenant an ACTIVE application authorization (S3-03's effective visibility). */
    private void authorize(String token, UUID applicationId, UUID tenantId) {
        post(token, "/api/v1/applications/" + applicationId + "/tenants/" + tenantId, Map.of(), 201);
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

    private UUID createTenantInForeignOrganization(String slug) {
        UUID foreignOrg = createOrg("Env " + slug + " Foreign Corp");
        String foreignAdmin = loginOfOrgRoleIn(foreignOrg, slug + "-foreign-admin", "ORG_ADMIN");
        return createTenant(foreignAdmin, foreignOrg, "Env Foreign Bank", "crm-" + slug + "-foreign");
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

    private JsonNode decode(String token) {
        String claims = token.split("\\.")[1];
        return mapper.readTree(Base64.getUrlDecoder().decode(claims));
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
                        "Environment " + role))
                .retrieve()
                .body(String.class);
        return accessToken(login(email, PASSWORD));
    }

    /** Registers a TENANT_ADMIN of the given tenant (the tenant persona of the concealment probes). */
    private String loginOfTenantAdminRoleIn(UUID orgId, UUID tenantId, String slug) {
        String email = slug + "@example.com";
        String devToken = accessToken(login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD));
        JsonNode invitation = post(
                devToken, "/api/v1/auth/invitations", Map.of("email", email, "organizationId", orgId.toString()), 201);
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
                        "Environment Tenant Admin"))
                .retrieve()
                .body(String.class);
        String userToken = accessToken(login(email, PASSWORD));
        String orgAdminToken = loginOfOrgRoleIn(orgId, slug + "-orgadmin", "ORG_ADMIN");
        post(
                orgAdminToken,
                "/api/v1/tenants/" + tenantId + "/members",
                Map.of("userId", userIdOf(userToken).toString(), "role", "TENANT_ADMIN"),
                201);
        return userToken;
    }

    private UUID userIdOf(String bearer) {
        return UUID.fromString(decode(bearer).get("sub").asText());
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
        spec.headers(headers -> headers.setBearerAuth(bearer));
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

    private static final class Recording {
        final int status;
        final String contentType;
        final JsonNode body;

        Recording(int status, String contentType, JsonNode body) {
            this.status = status;
            this.contentType = contentType;
            this.body = body;
        }

        JsonNode expect(int expectedStatus) {
            assertThat(status).as("status of %s", body).isEqualTo(expectedStatus);
            return body;
        }
    }
}
