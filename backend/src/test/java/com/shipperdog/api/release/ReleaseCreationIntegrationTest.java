package com.shipperdog.api.release;

import static org.assertj.core.api.Assertions.assertThat;

import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.identity.AuditCapture;
import com.shipperdog.api.tenant.TenantScopeExecutor;
import com.shipperdog.api.version.VersionDistributionGuard;
import com.shipperdog.api.version.VersionException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * End-to-end acceptance flow for the release model, creation, and
 * targets (issue #55, S9-01, FR-015, PRD 8.6/8.10/13): a DRAFT release
 * with per-platform READY artifacts and a quotable id (AC1), the 422
 * per-tenant target validation against the S3-03 effective-visibility
 * rule (AC2), tester-group validation against each target tenant (AC3),
 * ring validation against the seeded ring definitions (AC4), the
 * filtered listing and detail reads (AC5), and the audited creation with
 * the full target set (AC6) — plus the two distribution probes this
 * landing binds: INV-004's artifact-set freeze through the version
 * module's contributor seam (issue #19), and the application registry's
 * zero-distributions precondition on the ownership-scope transition
 * (issue #14), in both directions.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
            "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef"
        })
@Testcontainers
@Import(AuditCapture.class)
class ReleaseCreationIntegrationTest {

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

    @Autowired
    private TenantScopeExecutor scopeExecutor;

    @Autowired
    private VersionDistributionGuard distributionGuard;

    private RestClient client;

    @BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
        audit.reset();
    }

    // -----------------------------------------------------------------------
    // AC1: creation — DRAFT, READY per-platform artifacts, quotable id
    // -----------------------------------------------------------------------

    @Test
    void creationMakesADraftReleaseWithQuotableIdAndAudit() throws Exception {
        World world = tenantOwnedWorld("rel-create");
        UUID groupId = createGroup(world.adminToken, world.tenantA, "Pilot Group");

        JsonNode created = post(
                world.managerToken,
                "/api/v1/releases",
                releaseBody(
                        world.app,
                        world.version,
                        "tenant-pilot",
                        List.of(
                                Map.of("platform", "IOS", "artifactId", world.iosArtifact.toString()),
                                Map.of("platform", "ANDROID", "artifactId", world.androidArtifact.toString())),
                        List.of(Map.of(
                                "tenantId", world.tenantA.toString(), "testerGroupIds", List.of(groupId.toString())))),
                201);
        UUID releaseId = UUID.fromString(created.get("id").asText());
        assertThat(created.get("status").asText())
                .as("a release is born DRAFT (the state machine is S9-02)")
                .isEqualTo("DRAFT");
        assertThat(created.get("releaseRef").asText())
                .as("the quotable id is R-<number> (AC5)")
                .matches("R-[0-9]+");
        assertThat(created.get("ring").asText()).isEqualTo("tenant-pilot");
        assertThat(created.get("ringName").asText()).isEqualTo("Tenant Pilot");
        assertThat(created.get("artifacts").size()).isEqualTo(2);
        assertThat(created.get("targets").size()).isEqualTo(1);
        assertThat(created.get("targets").get(0).get("testerGroupIds").get(0).asText())
                .isEqualTo(groupId.toString());
        assertThat(UUID.fromString(created.get("createdBy").asText())).isEqualTo(userIdOf(world.managerToken));

        // The ownership snapshot, the sequence-backed number, and the
        // DRAFT status are stored on the row (V24 shape).
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT organization_id, tenant_id, status, release_number FROM releases WHERE id = ?", releaseId);
        assertThat(row.get("organization_id")).isEqualTo(world.org);
        assertThat(row.get("tenant_id")).isEqualTo(world.tenantA);
        assertThat(row.get("status")).isEqualTo("DRAFT");
        assertThat(((Number) row.get("release_number")).longValue()).isGreaterThanOrEqualTo(1001L);

        // The ring reference is real: the target row points at the seeded
        // tenant-pilot ring, and the group row hangs off its target.
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM release_targets WHERE release_id = ? AND ring_id = ?",
                        Integer.class,
                        releaseId,
                        ringIdOf("tenant-pilot")))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM release_target_groups WHERE release_id = ? AND group_id = ?",
                        Integer.class,
                        releaseId,
                        groupId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM release_artifacts WHERE release_id = ?", Integer.class, releaseId))
                .isEqualTo(2);

        // AC6: creation audited with the full target set.
        List<AuditEvent> events = audit.eventsOfType(AuditEventType.RELEASE_CREATED);
        assertThat(events).hasSize(1);
        AuditEvent event = events.get(0);
        assertThat(event.applicationId()).isEqualTo(world.app);
        assertThat(event.versionId()).isEqualTo(world.version);
        assertThat(event.tenantId()).isEqualTo(world.tenantA);
        assertThat(event.detail()).containsEntry("status", "DRAFT");
        assertThat(event.detail()).containsEntry("ring", "tenant-pilot");
        assertThat(event.detail().get("targets")).contains(world.tenantA.toString());
        assertThat(event.detail().get("artifacts")).contains("IOS", "ANDROID");
    }

    @Test
    void sharedApplicationTargetsAuthorizedTenantsAcrossTheOrganization() throws Exception {
        World world = sharedWorld("rel-shared");
        // Grants for A and B; C holds nothing.
        authorize(world.ownerToken, world.app, world.tenantA);
        authorize(world.ownerToken, world.app, world.tenantB);

        JsonNode created = post(
                world.managerToken,
                "/api/v1/releases",
                releaseBody(
                        world.app,
                        world.version,
                        "qa",
                        List.of(
                                Map.of("platform", "IOS", "artifactId", world.iosArtifact.toString()),
                                Map.of("platform", "ANDROID", "artifactId", world.androidArtifact.toString())),
                        List.of(
                                Map.of("tenantId", world.tenantA.toString(), "testerGroupIds", List.of()),
                                Map.of("tenantId", world.tenantB.toString(), "testerGroupIds", List.of()))),
                201);
        // The owning dimension of a shared application is the
        // organization itself: null tenant, organization set (V24 shape).
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT tenant_id, organization_id FROM releases WHERE id = ?",
                UUID.fromString(created.get("id").asText()));
        assertThat(row.get("tenant_id")).isNull();
        assertThat(row.get("organization_id")).isEqualTo(world.org);
        assertThat(created.get("targets").size()).isEqualTo(2);
    }

    @Test
    void notReadyArtifactsAreOne422PerPlatform() throws Exception {
        World world = tenantOwnedWorld("rel-notready");
        UUID validating = insertArtifact(world.version, "IOS", 91, "VALIDATING");

        JsonNode rejected = exchange(
                        "/api/v1/releases",
                        world.managerToken,
                        "POST",
                        releaseBody(
                                world.app,
                                world.version,
                                "qa",
                                List.of(
                                        Map.of("platform", "IOS", "artifactId", validating.toString()),
                                        Map.of("platform", "ANDROID", "artifactId", world.androidArtifact.toString())),
                                List.of(Map.of("tenantId", world.tenantA.toString(), "testerGroupIds", List.of()))),
                        null)
                .expect(422);
        assertThat(rejected.get("code").asText()).isEqualTo("RELEASE_ARTIFACT_NOT_READY");
        assertThat(rejected.get("errors").get("IOS").asText()).contains(validating.toString());
        assertThat(rejected.get("errors").has("ANDROID"))
                .as("READY artifacts are not offenses")
                .isFalse();
        assertThat(releaseCount(world.app)).isEqualTo(0);
    }

    @Test
    void artifactsOfOtherVersionsOrApplicationsConcealAsTheArtifactMiss() throws Exception {
        World world = tenantOwnedWorld("rel-artifact-miss");
        UUID otherVersion = createVersion(world.ownerToken, world.app, "2.0.0");
        UUID foreignArtifact = insertArtifact(otherVersion, "IOS", 5, "READY");

        for (UUID artifactId : List.of(foreignArtifact, UUID.randomUUID())) {
            JsonNode rejected = exchange(
                            "/api/v1/releases",
                            world.managerToken,
                            "POST",
                            releaseBody(
                                    world.app,
                                    world.version,
                                    "qa",
                                    List.of(Map.of("platform", "IOS", "artifactId", artifactId.toString())),
                                    List.of(Map.of("tenantId", world.tenantA.toString(), "testerGroupIds", List.of()))),
                            null)
                    .expect(404);
            assertThat(rejected.get("code").asText()).isEqualTo("ARTIFACT_NOT_FOUND");
        }
        assertThat(releaseCount(world.app)).isEqualTo(0);
    }

    @Test
    void malformedSelectionsAre400AndNothingIsPersisted() throws Exception {
        World world = tenantOwnedWorld("rel-malformed");
        String tenant = world.tenantA.toString();
        List<Map<String, Object>> bodies = List.of(
                releaseBody(
                        world.app,
                        world.version,
                        "qa",
                        List.of(),
                        List.of(Map.of("tenantId", tenant, "testerGroupIds", List.of()))),
                releaseBody(
                        world.app,
                        world.version,
                        "qa",
                        List.of(
                                Map.of("platform", "IOS", "artifactId", world.iosArtifact.toString()),
                                Map.of("platform", "IOS", "artifactId", world.androidArtifact.toString())),
                        List.of(Map.of("tenantId", tenant, "testerGroupIds", List.of()))),
                releaseBody(
                        world.app,
                        world.version,
                        "qa",
                        List.of(
                                Map.of("platform", "IOS", "artifactId", world.iosArtifact.toString()),
                                Map.of("platform", "ANDROID", "artifactId", world.androidArtifact.toString())),
                        List.of(
                                Map.of("tenantId", tenant, "testerGroupIds", List.of()),
                                Map.of("tenantId", tenant, "testerGroupIds", List.of()))),
                releaseBody(
                        world.app,
                        world.version,
                        "qa",
                        List.of(Map.of("platform", "WINDOWS", "artifactId", world.iosArtifact.toString())),
                        List.of(Map.of("tenantId", tenant, "testerGroupIds", List.of()))),
                releaseBody(world.app, null, "qa", List.of(), List.of()),
                // An empty target set is a malformed request, not a 422:
                // a release with nobody to serve has no semantic shape.
                releaseBody(
                        world.app,
                        world.version,
                        "qa",
                        List.of(Map.of("platform", "IOS", "artifactId", world.iosArtifact.toString())),
                        List.of()),
                // A slot whose platform label contradicts the artifact's
                // own platform (declared IOS, carrying the ANDROID build).
                releaseBody(
                        world.app,
                        world.version,
                        "qa",
                        List.of(Map.of("platform", "IOS", "artifactId", world.androidArtifact.toString())),
                        List.of(Map.of("tenantId", tenant, "testerGroupIds", List.of()))),
                // The same artifact selected twice under two labels.
                releaseBody(
                        world.app,
                        world.version,
                        "qa",
                        List.of(
                                Map.of("platform", "IOS", "artifactId", world.iosArtifact.toString()),
                                Map.of("platform", "ANDROID", "artifactId", world.iosArtifact.toString())),
                        List.of(Map.of("tenantId", tenant, "testerGroupIds", List.of()))));
        for (Map<String, Object> body : bodies) {
            JsonNode rejected = exchange("/api/v1/releases", world.managerToken, "POST", body, null)
                    .expect(400);
            assertThat(rejected.get("code").asText()).isEqualTo("INVALID_REQUEST");
        }
        assertThat(releaseCount(world.app)).isEqualTo(0);
    }

    @Test
    void archivedAndRevokedVersionsCannotBeReleased() throws Exception {
        World world = tenantOwnedWorld("rel-version-walls");
        UUID archived = createVersion(world.ownerToken, world.app, "2.0.0");
        post(
                world.ownerToken,
                "/api/v1/applications/" + world.app + "/versions/" + archived + "/archive",
                Map.of(),
                200);
        UUID revoked = createVersion(world.ownerToken, world.app, "3.0.0");
        post(
                world.ownerToken,
                "/api/v1/applications/" + world.app + "/versions/" + revoked + "/revoke-distribution",
                Map.of("reason", "pulled from the field for the release wall test"),
                200);

        for (Map.Entry<UUID, String> wall :
                List.of(Map.entry(archived, "VERSION_ARCHIVED"), Map.entry(revoked, "VERSION_REVOKED"))) {
            JsonNode rejected = exchange(
                            "/api/v1/releases",
                            world.managerToken,
                            "POST",
                            releaseBody(
                                    world.app,
                                    wall.getKey(),
                                    "qa",
                                    List.of(Map.of("platform", "IOS", "artifactId", world.iosArtifact.toString())),
                                    List.of(Map.of("tenantId", world.tenantA.toString(), "testerGroupIds", List.of()))),
                            null)
                    .expect(409);
            assertThat(rejected.get("code").asText()).isEqualTo(wall.getValue());
        }
    }

    @Test
    void duplicateReleasePerApplicationAndVersionConflicts() throws Exception {
        World world = tenantOwnedWorld("rel-duplicate");
        Map<String, Object> body = baseRelease(world);

        post(world.managerToken, "/api/v1/releases", body, 201);
        JsonNode duplicate = exchange("/api/v1/releases", world.managerToken, "POST", body, null)
                .expect(409);
        assertThat(duplicate.get("code").asText()).isEqualTo("RELEASE_ALREADY_EXISTS");

        // Uniqueness is per application + version: a sibling application
        // of the same tenant releases its own version freely.
        UUID otherApp = createTenantApp(world.ownerToken, world.tenantA, "Rel Dup Other", "com.reldup.other");
        UUID otherVersion = createVersion(world.ownerToken, otherApp, "1.0.0");
        post(
                world.managerToken,
                "/api/v1/releases",
                releaseBody(
                        otherApp,
                        otherVersion,
                        "qa",
                        List.of(Map.of(
                                "platform",
                                "IOS",
                                "artifactId",
                                insertArtifact(otherVersion, "IOS", 1, "READY").toString())),
                        List.of(Map.of("tenantId", world.tenantA.toString(), "testerGroupIds", List.of()))),
                201);
    }

    // -----------------------------------------------------------------------
    // AC2/AC3: the 422 target validation, per tenant and per group
    // -----------------------------------------------------------------------

    @Test
    void unauthorizedTenantsAnswerOne422WithPerTenantErrors() throws Exception {
        World world = sharedWorld("rel-targets");
        authorize(world.ownerToken, world.app, world.tenantB);
        UUID foreignTenant = createTenantInForeignOrganization("rel-targets-foreign");

        JsonNode rejected = exchange(
                        "/api/v1/releases",
                        world.managerToken,
                        "POST",
                        releaseBody(
                                world.app,
                                world.version,
                                "qa",
                                List.of(Map.of("platform", "IOS", "artifactId", world.iosArtifact.toString())),
                                List.of(
                                        Map.of("tenantId", world.tenantA.toString(), "testerGroupIds", List.of()),
                                        Map.of("tenantId", world.tenantB.toString(), "testerGroupIds", List.of()),
                                        Map.of("tenantId", world.tenantC.toString(), "testerGroupIds", List.of()),
                                        Map.of("tenantId", foreignTenant.toString(), "testerGroupIds", List.of()))),
                        null)
                .expect(422);
        assertThat(rejected.get("code").asText()).isEqualTo("RELEASE_TARGETS_INVALID");
        assertThat(rejected.get("errors").get(world.tenantA.toString()).asText())
                .as("an ungranted sibling tenant does not hold a shared application")
                .contains("does not hold");
        assertThat(rejected.get("errors").get(world.tenantC.toString()).asText())
                .contains("does not hold");
        assertThat(rejected.get("errors").get(foreignTenant.toString()).asText())
                .as("a foreign organization's tenant can never hold it")
                .contains("does not hold");
        assertThat(rejected.get("errors").has(world.tenantB.toString()))
                .as("an ACTIVE authorization holds it (S3-03)")
                .isFalse();
        assertThat(releaseCount(world.app)).isEqualTo(0);
    }

    @Test
    void groupsNotOfTheirTargetTenantAnswer422() throws Exception {
        World world = sharedWorld("rel-groups");
        authorize(world.ownerToken, world.app, world.tenantA);
        UUID groupOfB = createGroup(world.adminToken, world.tenantB, "Group of B");

        JsonNode rejected = exchange(
                        "/api/v1/releases",
                        world.managerToken,
                        "POST",
                        releaseBody(
                                world.app,
                                world.version,
                                "qa",
                                List.of(Map.of("platform", "IOS", "artifactId", world.iosArtifact.toString())),
                                List.of(Map.of(
                                        "tenantId",
                                        world.tenantA.toString(),
                                        "testerGroupIds",
                                        List.of(
                                                groupOfB.toString(),
                                                UUID.randomUUID().toString())))),
                        null)
                .expect(422);
        assertThat(rejected.get("code").asText()).isEqualTo("RELEASE_TARGETS_INVALID");
        assertThat(rejected.get("errors").get(groupOfB.toString()).asText()).contains("does not belong");
        assertThat(rejected.get("errors").size()).isEqualTo(2);
        assertThat(releaseCount(world.app)).isEqualTo(0);

        // A group repeated within ONE target is a malformed selection of
        // that target, answered by the same 422 keyed by the tenant.
        UUID groupOfA = createGroup(world.adminToken, world.tenantA, "Group of A");
        JsonNode duplicated = exchange(
                        "/api/v1/releases",
                        world.managerToken,
                        "POST",
                        releaseBody(
                                world.app,
                                world.version,
                                "qa",
                                List.of(Map.of("platform", "IOS", "artifactId", world.iosArtifact.toString())),
                                List.of(Map.of(
                                        "tenantId",
                                        world.tenantA.toString(),
                                        "testerGroupIds",
                                        List.of(groupOfA.toString(), groupOfA.toString())))),
                        null)
                .expect(422);
        assertThat(duplicated.get("code").asText()).isEqualTo("RELEASE_TARGETS_INVALID");
        assertThat(duplicated.get("errors").get(world.tenantA.toString()).asText())
                .contains("distinct");
        assertThat(duplicated.get("errors").size()).isEqualTo(1);
        assertThat(releaseCount(world.app)).isEqualTo(0);
    }

    // -----------------------------------------------------------------------
    // AC4: ring validation against the definitions
    // -----------------------------------------------------------------------

    @Test
    void unknownRingsAreRejectedAndEverySeededRingResolves() throws Exception {
        World world = tenantOwnedWorld("rel-ring");
        String tenant = world.tenantA.toString();

        JsonNode unknown = exchange(
                        "/api/v1/releases",
                        world.managerToken,
                        "POST",
                        releaseBody(
                                world.app,
                                world.version,
                                "canary",
                                List.of(Map.of("platform", "IOS", "artifactId", world.iosArtifact.toString())),
                                List.of(Map.of("tenantId", tenant, "testerGroupIds", List.of()))),
                        null)
                .expect(400);
        assertThat(unknown.get("code").asText()).isEqualTo("RELEASE_RING_UNKNOWN");
        for (String blank : List.of("", "   ")) {
            JsonNode rejected = exchange(
                            "/api/v1/releases",
                            world.managerToken,
                            "POST",
                            releaseBody(
                                    world.app,
                                    world.version,
                                    blank,
                                    List.of(Map.of("platform", "IOS", "artifactId", world.iosArtifact.toString())),
                                    List.of(Map.of("tenantId", tenant, "testerGroupIds", List.of()))),
                            null)
                    .expect(400);
            assertThat(rejected.get("code").asText()).isEqualTo("INVALID_REQUEST");
        }

        // Every FR-017 system ring resolves, and surrounding case and
        // whitespace normalize to the stored code.
        List<String> rings = List.of("engineering", "qa", "tenant-pilot", "uat", "production");
        for (int i = 0; i < rings.size(); i++) {
            String ring = rings.get(i);
            UUID version = createVersion(world.ownerToken, world.app, (10 + i) + ".0.0");
            UUID artifact = insertArtifact(version, "IOS", 3, "READY");
            JsonNode created = post(
                    world.managerToken,
                    "/api/v1/releases",
                    releaseBody(
                            world.app,
                            version,
                            (i % 2 == 0 ? " " : "")
                                    + ring.toUpperCase(Locale.ROOT).charAt(0)
                                    + ring.substring(1),
                            List.of(Map.of("platform", "IOS", "artifactId", artifact.toString())),
                            List.of(Map.of("tenantId", tenant, "testerGroupIds", List.of()))),
                    201);
            assertThat(created.get("ring").asText())
                    .as("ring '%s' normalizes to its stored code", ring)
                    .isEqualTo(ring);
        }
    }

    // -----------------------------------------------------------------------
    // AC5: the listing and the detail
    // -----------------------------------------------------------------------

    @Test
    void listFiltersByApplicationVersionStatusAndTenantAndPagesNewestFirst() throws Exception {
        World world = sharedWorld("rel-list");
        authorize(world.ownerToken, world.app, world.tenantA);
        authorize(world.ownerToken, world.app, world.tenantB);
        UUID versionOne = createVersion(world.ownerToken, world.app, "1.1.0");
        UUID versionTwo = createVersion(world.ownerToken, world.app, "2.0.0");
        UUID artifactOne = insertArtifact(versionOne, "IOS", 1, "READY");
        UUID artifactTwo = insertArtifact(versionTwo, "IOS", 2, "READY");

        JsonNode first = post(
                world.managerToken,
                "/api/v1/releases",
                releaseBody(
                        world.app,
                        versionOne,
                        "qa",
                        List.of(Map.of("platform", "IOS", "artifactId", artifactOne.toString())),
                        List.of(Map.of("tenantId", world.tenantA.toString(), "testerGroupIds", List.of()))),
                201);
        JsonNode second = post(
                world.managerToken,
                "/api/v1/releases",
                releaseBody(
                        world.app,
                        versionTwo,
                        "uat",
                        List.of(Map.of("platform", "IOS", "artifactId", artifactTwo.toString())),
                        List.of(Map.of("tenantId", world.tenantB.toString(), "testerGroupIds", List.of()))),
                201);
        UUID otherApp = createTenantApp(world.ownerToken, world.tenantA, "Rel List Other", "com.rellist.other");
        UUID otherVersion = createVersion(world.ownerToken, otherApp, "1.0.0");
        post(
                world.managerToken,
                "/api/v1/releases",
                releaseBody(
                        otherApp,
                        otherVersion,
                        "qa",
                        List.of(Map.of(
                                "platform",
                                "IOS",
                                "artifactId",
                                insertArtifact(otherVersion, "IOS", 1, "READY").toString())),
                        List.of(Map.of("tenantId", world.tenantA.toString(), "testerGroupIds", List.of()))),
                201);

        // By application: this app's two releases, newest (number) first.
        JsonNode byApp = get(world.managerToken, "/api/v1/releases?applicationId=" + world.app, 200);
        assertThat(byApp.get("items").size()).isEqualTo(2);
        assertThat(byApp.get("items").get(0).get("releaseRef").asText())
                .isEqualTo(second.get("releaseRef").asText());
        assertThat(byApp.get("items").get(0).get("targetCount").asInt()).isEqualTo(1);

        // By version.
        JsonNode byVersion = get(
                world.managerToken, "/api/v1/releases?applicationId=" + world.app + "&versionId=" + versionOne, 200);
        assertThat(byVersion.get("items").size()).isEqualTo(1);
        assertThat(byVersion.get("items").get(0).get("releaseRef").asText())
                .isEqualTo(first.get("releaseRef").asText());

        // By status (DRAFT is the only value this story writes).
        assertThat(get(world.managerToken, "/api/v1/releases?applicationId=" + world.app + "&status=DRAFT", 200)
                        .get("items")
                        .size())
                .isEqualTo(2);
        assertThat(get(world.managerToken, "/api/v1/releases?applicationId=" + world.app + "&status=ACTVE", 400)
                        .get("code")
                        .asText())
                .isEqualTo("INVALID_REQUEST");

        // By target tenant: only the release that targets tenantB.
        JsonNode byTenant = get(
                world.managerToken, "/api/v1/releases?applicationId=" + world.app + "&tenantId=" + world.tenantB, 200);
        assertThat(byTenant.get("items").size()).isEqualTo(1);
        assertThat(byTenant.get("items").get(0).get("releaseRef").asText())
                .isEqualTo(second.get("releaseRef").asText());

        // Pagination: limit 1, follow the cursor.
        JsonNode pageOne = get(world.managerToken, "/api/v1/releases?applicationId=" + world.app + "&limit=1", 200);
        assertThat(pageOne.get("items").size()).isEqualTo(1);
        assertThat(pageOne.get("next_cursor")).isNotNull();
        JsonNode pageTwo = get(
                world.managerToken,
                "/api/v1/releases?applicationId=" + world.app + "&limit=1&cursor="
                        + pageOne.get("next_cursor").asText(),
                200);
        assertThat(pageTwo.get("items").size()).isEqualTo(1);
        assertThat(pageTwo.get("items").get(0).get("releaseRef").asText())
                .isEqualTo(first.get("releaseRef").asText());
        get(world.managerToken, "/api/v1/releases?applicationId=" + world.app + "&limit=0", 400);
        get(world.managerToken, "/api/v1/releases?applicationId=" + world.app + "&limit=101", 400);
        get(world.managerToken, "/api/v1/releases?applicationId=" + world.app + "&cursor=garbage", 400);

        // The application filter is required (it is the permission target).
        get(world.managerToken, "/api/v1/releases", 400);
    }

    @Test
    void detailCarriesTheFullRecordAndConcealsForeignAddresses() throws Exception {
        World world = tenantOwnedWorld("rel-detail");
        UUID group = createGroup(world.adminToken, world.tenantA, "Detail Group");
        JsonNode created = post(
                world.managerToken,
                "/api/v1/releases",
                releaseBody(
                        world.app,
                        world.version,
                        "tenant-pilot",
                        List.of(Map.of("platform", "IOS", "artifactId", world.iosArtifact.toString())),
                        List.of(Map.of(
                                "tenantId", world.tenantA.toString(), "testerGroupIds", List.of(group.toString())))),
                201);
        UUID releaseId = UUID.fromString(created.get("id").asText());

        JsonNode detail = get(world.managerToken, "/api/v1/releases/" + releaseId + "?applicationId=" + world.app, 200);
        assertThat(detail.get("releaseRef").asText())
                .isEqualTo(created.get("releaseRef").asText());
        assertThat(detail.get("artifacts").get(0).get("platform").asText()).isEqualTo("IOS");
        assertThat(detail.get("targets").get(0).get("tenantId").asText()).isEqualTo(world.tenantA.toString());
        assertThat(detail.get("targets").get(0).get("testerGroupIds").get(0).asText())
                .isEqualTo(group.toString());

        // An unknown release under the caller's own application: the miss.
        JsonNode miss =
                get(world.managerToken, "/api/v1/releases/" + UUID.randomUUID() + "?applicationId=" + world.app, 404);
        // The same release under a different application of the same
        // organization (the caller CAN read): the same miss shape,
        // not a forbidden (concealment, byte parity on code and detail).
        UUID otherApp = createTenantApp(world.ownerToken, world.tenantA, "Rel Detail Other", "com.reldetail.other");
        JsonNode foreign = get(world.managerToken, "/api/v1/releases/" + releaseId + "?applicationId=" + otherApp, 404);
        assertThat(miss.get("code").asText()).isEqualTo("RELEASE_NOT_FOUND");
        assertThat(foreign.get("code").asText()).isEqualTo("RELEASE_NOT_FOUND");
        assertThat(foreign.get("detail").asText()).isEqualTo(miss.get("detail").asText());

        // A caller without app:releases:read (the tenant persona of the
        // owning tenant) conceals through the application-grain check.
        String tenantAdminToken = loginOfTenantAdminRoleIn(world.org, world.tenantA, "rel-detail-tenant-admin");
        assertThat(get(tenantAdminToken, "/api/v1/releases/" + releaseId + "?applicationId=" + world.app, 404)
                        .get("code")
                        .asText())
                .isEqualTo("APPLICATION_NOT_FOUND");

        // A caller with read but not manage cannot create: the concealed
        // application miss, identical to the unknown-application probe.
        String developerToken = loginOfOrgRoleIn(world.org, "rel-detail-dev", "DEVELOPER");
        JsonNode manageDenied = exchange(
                        "/api/v1/releases",
                        developerToken,
                        "POST",
                        releaseBody(
                                world.app,
                                world.version,
                                "qa",
                                List.of(Map.of("platform", "IOS", "artifactId", world.iosArtifact.toString())),
                                List.of(Map.of("tenantId", world.tenantA.toString(), "testerGroupIds", List.of()))),
                        null)
                .expect(404);
        JsonNode unknownApp = exchange(
                        "/api/v1/releases",
                        world.managerToken,
                        "POST",
                        releaseBody(
                                UUID.randomUUID(),
                                world.version,
                                "qa",
                                List.of(Map.of("platform", "IOS", "artifactId", world.iosArtifact.toString())),
                                List.of(Map.of("tenantId", world.tenantA.toString(), "testerGroupIds", List.of()))),
                        null)
                .expect(404);
        assertThat(manageDenied.get("code").asText())
                .isEqualTo(unknownApp.get("code").asText())
                .isEqualTo("APPLICATION_NOT_FOUND");
        assertThat(manageDenied.get("detail").asText())
                .isEqualTo(unknownApp.get("detail").asText());
    }

    @Test
    void creationHonorsIdempotencyKeys() throws Exception {
        World world = tenantOwnedWorld("rel-idempotent");
        Map<String, Object> body = baseRelease(world);
        String key = UUID.randomUUID().toString();

        JsonNode first = exchange("/api/v1/releases", world.managerToken, "POST", body, key)
                .expect(201);
        JsonNode replay = exchange("/api/v1/releases", world.managerToken, "POST", body, key)
                .expect(201);
        assertThat(replay.get("id").asText()).isEqualTo(first.get("id").asText());
        assertThat(releaseCount(world.app)).isEqualTo(1);
        assertThat(audit.eventsOfType(AuditEventType.RELEASE_CREATED))
                .as("a replay is not a second creation event")
                .hasSize(1);
    }

    @Test
    void concurrentCreationsOfOneApplicationAndVersionLeaveExactlyOneRelease() throws Exception {
        World world = tenantOwnedWorld("rel-race");
        Map<String, Object> body = baseRelease(world);
        // No idempotency keys: the database's uq_releases_application_
        // version must be the only referee when two creations race.
        CyclicBarrier start = new CyclicBarrier(2);
        ExecutorService racers = Executors.newFixedThreadPool(2);
        try {
            List<Future<Recording>> runs = List.of(
                    racers.submit(() -> racePost(world.managerToken, body, start)),
                    racers.submit(() -> racePost(world.managerToken, body, start)));
            Recording first = runs.get(0).get();
            Recording second = runs.get(1).get();
            // Exactly one creation wins; the loser answers the same 409
            // the pre-check serves — the lost-race translation must hold,
            // never a 500 leaking a constraint violation.
            List<Integer> statuses = List.of(first.status(), second.status());
            assertThat(statuses).containsExactlyInAnyOrder(201, 409);
            Recording loser = first.status() == 409 ? first : second;
            assertThat(loser.body().get("code").asText()).isEqualTo("RELEASE_ALREADY_EXISTS");
            assertThat(releaseCount(world.app)).isEqualTo(1);
            assertThat(audit.eventsOfType(AuditEventType.RELEASE_CREATED))
                    .as("the loser never audits a creation")
                    .hasSize(1);
        } finally {
            racers.shutdownNow();
        }
    }

    @Test
    void aReleasedVersionsArtifactSetRefusesLaterUploads() throws Exception {
        World world = tenantOwnedWorld("rel-freeze-upload");
        post(world.managerToken, "/api/v1/releases", baseRelease(world), 201);

        // The freeze is user-visible on the real upload route, not just
        // the version module's guard seam: a developer's later upload of
        // the released version answers VERSION_IMMUTABLE before any byte
        // reaches storage, and the pinned artifact set does not grow.
        String developerToken = loginOfOrgRoleIn(world.org, "rel-freeze-upload-dev", "DEVELOPER");
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("version_id", world.version.toString());
        parts.add("platform", "IOS");
        parts.add("file", new ByteArrayResource("a late upload that must not join".getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return "late-upload.ipa";
            }
        });
        JsonNode rejected = postMultipart(developerToken, "/api/v1/artifacts", parts, 409);
        assertThat(rejected.get("code").asText()).isEqualTo("VERSION_IMMUTABLE");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM release_artifacts ra JOIN releases r ON r.id = ra.release_id "
                                + "WHERE r.application_id = ? AND r.version_id = ?",
                        Integer.class,
                        world.app,
                        world.version))
                .as("the frozen artifact set stays the two pinned slots")
                .isEqualTo(2);
    }

    @Test
    void anOrganizationsOwnRingShadowsTheSystemDefaultForItsReleases() throws Exception {
        World world = tenantOwnedWorld("rel-org-ring");
        // The organization defines its own meaning for 'qa'; a foreign
        // organization defines the same code for itself (uq_rings_scope_
        // code scopes the vocabulary per organization).
        UUID orgRing = insertRing(world.org, "qa", "Org QA Ring");
        UUID foreignOrg = createOrg("Rel Org Ring Foreign Corp");
        insertRing(foreignOrg, "qa", "Foreign QA Ring");

        JsonNode created = post(world.managerToken, "/api/v1/releases", baseRelease(world), 201);
        UUID releaseId = UUID.fromString(created.get("id").asText());
        // Resolution prefers the organization's ring over the system
        // default and never sees the foreign organization's code.
        assertThat(created.get("ringName").asText()).isEqualTo("Org QA Ring");
        assertThat(jdbc.queryForObject("SELECT starting_ring_id FROM releases WHERE id = ?", UUID.class, releaseId))
                .isEqualTo(orgRing);
        assertThat(jdbc.queryForObject(
                        "SELECT ring_id FROM release_targets WHERE release_id = ?", UUID.class, releaseId))
                .as("the target rows carry the same resolved ring reference (the FK's integrity)")
                .isEqualTo(orgRing);
    }

    // -----------------------------------------------------------------------
    // The reserved probes: INV-004 freeze (issue #19) and the scope
    // transition precondition (issue #14), both directions
    // -----------------------------------------------------------------------

    @Test
    void aReleaseFreezesTheVersionsArtifactSet() throws Exception {
        World world = tenantOwnedWorld("rel-freeze");
        UUID actor = userIdOf(world.managerToken);
        scopeExecutor.inScope(world.tenantA, actor, () -> {
            distributionGuard.requireArtifactsMutable(world.app, world.version);
            return null;
        });

        post(world.managerToken, "/api/v1/releases", baseRelease(world), 201);

        assertThatThrownByIsFrozen(world, actor);
    }

    private void assertThatThrownByIsFrozen(World world, UUID actor) {
        try {
            scopeExecutor.inScope(world.tenantA, actor, () -> {
                distributionGuard.requireArtifactsMutable(world.app, world.version);
                return null;
            });
            throw new AssertionError("the version's artifact set must be frozen once released");
        } catch (VersionException e) {
            assertThat(e.code()).isEqualTo(VersionException.Code.VERSION_IMMUTABLE);
            assertThat(e.getMessage()).contains("distribution records");
        }
    }

    @Test
    void releasesBlockTheOwnershipScopeTransitionInBothDirections() throws Exception {
        // TENANT -> ORGANIZATION: a release anchors the application to
        // its tenant (issue #14's zero-distributions precondition).
        World owned = tenantOwnedWorld("rel-transition-owned");
        post(owned.managerToken, "/api/v1/releases", baseRelease(owned), 201);
        assertThat(patch(
                                owned.adminToken,
                                "/api/v1/applications/" + owned.app,
                                Map.of("ownershipScope", "ORGANIZATION"),
                                409)
                        .get("code")
                        .asText())
                .isEqualTo("APPLICATION_RELEASES_PRESENT");

        // ORGANIZATION -> TENANT: a release of a shared application
        // targeted authorized tenants a conversion would cut off. The
        // authorization itself is revoked first (revoked history does not
        // block the S3-03 precondition) so THIS story's precondition is
        // the one that answers.
        World shared = sharedWorld("rel-transition-shared");
        authorize(shared.adminToken, shared.app, shared.tenantB);
        authorize(shared.adminToken, shared.app, shared.tenantA);
        post(shared.managerToken, "/api/v1/releases", baseRelease(shared), 201);
        exchange(
                "/api/v1/applications/" + shared.app + "/tenants/" + shared.tenantB,
                shared.adminToken,
                "DELETE",
                Map.of("reason", "revoked so the release precondition is the wall under test"),
                null);
        exchange(
                "/api/v1/applications/" + shared.app + "/tenants/" + shared.tenantA,
                shared.adminToken,
                "DELETE",
                Map.of("reason", "revoked so the release precondition is the wall under test"),
                null);
        assertThat(patch(
                                shared.adminToken,
                                "/api/v1/applications/" + shared.app,
                                Map.of("ownershipScope", "TENANT", "tenantId", shared.tenantA.toString()),
                                409)
                        .get("code")
                        .asText())
                .isEqualTo("APPLICATION_RELEASES_PRESENT");

        // Without releases the transition still proceeds (the
        // precondition is this story's addition, not a wall).
        World free = tenantOwnedWorld("rel-transition-free");
        patch(free.adminToken, "/api/v1/applications/" + free.app, Map.of("ownershipScope", "ORGANIZATION"), 200);
    }

    // -----------------------------------------------------------------------
    // Fixture world
    // -----------------------------------------------------------------------

    /**
     * One organization with a tenant-owned application under tenantA: the
     * owning tenant holds the application by ownership; tenantA2 is a
     * sibling tenant of the same organization that holds nothing.
     */
    private World tenantOwnedWorld(String slug) throws Exception {
        World world = new World();
        world.org = createOrg("Rel " + slug + " Corp");
        world.adminToken = loginOfOrgRoleIn(world.org, slug + "-admin", "ORG_ADMIN");
        world.ownerToken = loginOfOrgRoleIn(world.org, slug + "-owner", "APP_OWNER");
        world.managerToken = loginOfOrgRoleIn(world.org, slug + "-manager", "RELEASE_MANAGER");
        world.tenantA = createTenant(world.adminToken, world.org, "Rel " + slug + " Bank", "crm-" + slug + "-a");
        world.tenantA2 = createTenant(world.adminToken, world.org, "Rel " + slug + " Bank 2", "crm-" + slug + "-a2");
        world.app = createTenantApp(
                world.adminToken,
                world.tenantA,
                "Rel " + slug + " Banking",
                "com." + slug.replace("-", "") + ".banking");
        world.version = createVersion(world.ownerToken, world.app, "1.0.0");
        world.iosArtifact = insertArtifact(world.version, "IOS", 1, "READY");
        world.androidArtifact = insertArtifact(world.version, "ANDROID", 2, "READY");
        return world;
    }

    /**
     * One organization with an ORGANIZATION-shared application: no tenant
     * holds it until the authorization surface grants (S3-03 — shared
     * visibility is opt-in); tenantA, tenantB, and tenantC are sibling
     * tenants for the grant matrix. The owning-organization scope serves
     * the release rows (null tenant).
     */
    private World sharedWorld(String slug) throws Exception {
        World world = new World();
        world.org = createOrg("Rel " + slug + " Corp");
        world.adminToken = loginOfOrgRoleIn(world.org, slug + "-admin", "ORG_ADMIN");
        world.ownerToken = loginOfOrgRoleIn(world.org, slug + "-owner", "APP_OWNER");
        world.managerToken = loginOfOrgRoleIn(world.org, slug + "-manager", "RELEASE_MANAGER");
        world.tenantA = createTenant(world.adminToken, world.org, "Rel " + slug + " Bank A", "crm-" + slug + "-a");
        world.tenantB = createTenant(world.adminToken, world.org, "Rel " + slug + " Bank B", "crm-" + slug + "-b");
        world.tenantC = createTenant(world.adminToken, world.org, "Rel " + slug + " Bank C", "crm-" + slug + "-c");
        Map<String, Object> body = new HashMap<>();
        body.put("ownershipScope", "ORGANIZATION");
        body.put("organizationId", world.org.toString());
        body.put("name", "Rel " + slug + " Shared");
        body.put("platforms", List.of("IOS", "ANDROID"));
        body.put("bundleId", "com." + slug.replace("-", "") + ".shared");
        body.put("packageName", "com." + slug.replace("-", "") + ".shared");
        world.app = UUID.fromString(post(world.adminToken, "/api/v1/applications", body, 201)
                .get("id")
                .asText());
        world.version = createVersion(world.ownerToken, world.app, "1.0.0");
        world.iosArtifact = insertArtifact(world.version, "IOS", 1, "READY");
        world.androidArtifact = insertArtifact(world.version, "ANDROID", 2, "READY");
        return world;
    }

    private static final class World {
        UUID org;
        String adminToken;
        String ownerToken;
        String managerToken;
        UUID tenantA;
        UUID tenantA2;
        UUID tenantB;
        UUID tenantC;
        UUID app;
        UUID version;
        UUID iosArtifact;
        UUID androidArtifact;
    }

    /** A canonical two-platform release body of the world (the first holdable tenant, no groups). */
    private Map<String, Object> baseRelease(World world) {
        return releaseBody(
                world.app,
                world.version,
                "qa",
                List.of(
                        Map.of("platform", "IOS", "artifactId", world.iosArtifact.toString()),
                        Map.of("platform", "ANDROID", "artifactId", world.androidArtifact.toString())),
                List.of(Map.of("tenantId", world.tenantA.toString(), "testerGroupIds", List.of())));
    }

    private static Map<String, Object> releaseBody(
            UUID applicationId,
            UUID versionId,
            String ring,
            List<Map<String, Object>> artifacts,
            List<Map<String, Object>> targets) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("applicationId", applicationId.toString());
        body.put("versionId", versionId == null ? null : versionId.toString());
        body.put("ring", ring);
        body.put("artifacts", new ArrayList<>(artifacts));
        body.put("targets", new ArrayList<>(targets));
        return body;
    }

    /** Inserts an artifact row directly (READY unless asked otherwise) — the release fixture's binary-free shortcut. */
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
                "a1".repeat(32),
                1024L,
                build,
                status,
                owner.get("organization_id"),
                owner.get("tenant_id"));
        return artifactId;
    }

    private UUID ringIdOf(String code) {
        return jdbc.queryForObject("SELECT id FROM rings WHERE code = ? AND organization_id IS NULL", UUID.class, code);
    }

    /** Inserts an organization-scoped ring (E10's writer is not here yet; the reference row is the fixture). */
    private UUID insertRing(UUID organizationId, String code, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO rings (id, organization_id, code, name, position) VALUES (?, ?, ?, ?, 90)",
                id,
                organizationId,
                code,
                name);
        return id;
    }

    /** One racer's creation attempt, released simultaneously with its peer by the barrier. */
    private Recording racePost(String bearer, Map<String, Object> body, CyclicBarrier start) {
        try {
            start.await(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return exchange("/api/v1/releases", bearer, "POST", body, null);
    }

    /** Posts a multipart body (the artifact upload route's shape) and asserts the status. */
    private JsonNode postMultipart(String bearer, String uri, MultiValueMap<String, Object> parts, int expectedStatus) {
        Recording recorded = client.post()
                .uri(uri)
                .headers(headers -> headers.setBearerAuth(bearer))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(parts)
                .exchange((request, response) -> {
                    byte[] bytes = response.getBody().readAllBytes();
                    return new Recording(
                            response.getStatusCode().value(),
                            response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE),
                            bytes.length == 0 ? mapper.createObjectNode() : mapper.readTree(bytes));
                });
        return recorded.expect(expectedStatus);
    }

    private int releaseCount(UUID applicationId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM releases WHERE application_id = ?", Integer.class, applicationId);
    }

    private void authorize(String token, UUID applicationId, UUID tenantId) {
        post(token, "/api/v1/applications/" + applicationId + "/tenants/" + tenantId, Map.of(), 201);
    }

    private UUID createGroup(String token, UUID tenantId, String name) {
        return UUID.fromString(
                post(token, "/api/v1/tester-groups", Map.of("tenantId", tenantId.toString(), "name", name), 201)
                        .get("id")
                        .asText());
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
        Map<String, Object> body = new HashMap<>();
        body.put("tenantId", tenantId.toString());
        body.put("name", name);
        body.put("platforms", List.of("IOS", "ANDROID"));
        body.put("bundleId", bundleId);
        body.put("packageName", bundleId);
        return UUID.fromString(
                post(token, "/api/v1/applications", body, 201).get("id").asText());
    }

    private UUID createTenantInForeignOrganization(String slug) {
        UUID foreignOrg = createOrg("Rel " + slug + " Foreign Corp");
        String foreignAdmin = loginOfOrgRoleIn(foreignOrg, slug + "-foreign-admin", "ORG_ADMIN");
        return createTenant(foreignAdmin, foreignOrg, "Rel Foreign Bank", "crm-" + slug + "-foreign");
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

    private UUID userIdOf(String bearer) {
        return UUID.fromString(decode(bearer).get("sub").asText());
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
                        "Release " + role))
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
                        "Release Tenant Admin"))
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
        return exchange(uri, bearer, "POST", body, null).expect(expectedStatus);
    }

    private JsonNode patch(String bearer, String uri, Map<String, Object> body, int expectedStatus) {
        return exchange(uri, bearer, "PATCH", body, null).expect(expectedStatus);
    }

    private JsonNode get(String bearer, String uri, int expectedStatus) {
        return exchange(uri, bearer, "GET", null, null).expect(expectedStatus);
    }

    private Recording exchange(
            String uri, String bearer, String method, Map<String, Object> body, String idempotencyKey) {
        RestClient.RequestBodySpec spec =
                client.method(HttpMethod.valueOf(method)).uri(uri);
        if (bearer != null) {
            spec.headers(headers -> headers.setBearerAuth(bearer));
        }
        if (idempotencyKey != null) {
            spec.headers(headers -> headers.set("Idempotency-Key", idempotencyKey));
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
