package com.shipperdog.api.device;

import static org.assertj.core.api.Assertions.assertThat;

import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.identity.AuditCapture;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * End-to-end acceptance flow for the device surface (issue #48, S11-03,
 * FR-028, PRD 8.12): registration with the tenant-scoped identifier
 * uniqueness (AC1 — the duplicate rejected), the console's listing and
 * management actions with the owner join (AC2), the provisioning CSV
 * export with its admin-only download authority (AC3), the
 * registered-device context on feedback (AC4 — the submission link
 * validated, the detail's auto-context), and the history posture (AC5
 * — deactivating the tester keeps every device record).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
            "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef",
            "shipperdog.feedback.storage.max-screenshot-bytes=1024",
            "shipperdog.feedback.storage.max-log-bytes=1024",
            "shipperdog.feedback.storage.max-video-bytes=1024"
        })
@Testcontainers
@Import(AuditCapture.class)
class DeviceIntegrationTest {

    private static final String DEV_ADMIN_EMAIL = "dev@shipperdog.local";
    private static final String DEV_ADMIN_PASSWORD = "shipperdog-dev-admin";
    private static final String PASSWORD = "a-perfectly-valid-password";

    /** The compose GCS emulator image, in a container (the feedback suite's fixture — the store stays wired). */
    @Container
    static final GenericContainer<?> GCS = new GenericContainer<>(
                    DockerImageName.parse("fsouza/fake-gcs-server:1.56.1"))
            .withCommand("-scheme", "http", "-host", "0.0.0.0", "-port", "4443")
            .withExposedPorts(4443);

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));

    @DynamicPropertySource
    static void feedbackStorage(DynamicPropertyRegistry registry) {
        registry.add(
                "shipperdog.feedback.storage.host", () -> "http://" + GCS.getHost() + ":" + GCS.getMappedPort(4443));
    }

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
    // AC1: registration and the tenant-scoped duplicate guard
    // -----------------------------------------------------------------------

    @Test
    void registrationLandsActiveAndTheDuplicateIdentifierIsRejected() throws Exception {
        World world = world("dv-reg");
        JsonNode registered = register(
                world.tenantAdminToken,
                world.tenantA,
                world.testerId,
                "IOS",
                "iPhone 15 Pro",
                "18.2.1",
                "00008101-0A1B2C3D4E5F6789",
                201);
        assertThat(registered.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(registered.get("platform").asText()).isEqualTo("IOS");
        assertThat(registered.get("identifier").asText()).isEqualTo("00008101-0A1B2C3D4E5F6789");
        assertThat(registered.get("ownerEmail").asText()).isEqualTo(world.testerEmail);
        assertThat(UUID.fromString(registered.get("id").asText())).isNotNull();

        // The duplicate per tenant (AC1): the same identifier refuses,
        // for the same tester or any other.
        register(
                world.tenantAdminToken,
                world.tenantA,
                world.testerId,
                "IOS",
                "iPhone 15 Pro",
                "18.2.1",
                "00008101-0A1B2C3D4E5F6789",
                409);

        // The same identifier in ANOTHER tenant is not a duplicate (the
        // uniqueness is tenant-scoped, V1's own constraint grain) — but
        // this tester holds no membership row there, so the owner guard
        // fires first (the 422, not the 409).
        JsonNode crossTenant = register(
                world.tenantBAdminToken,
                world.tenantB,
                world.testerId,
                "IOS",
                "iPhone 15 Pro",
                "18.2.1",
                "00008101-0A1B2C3D4E5F6789",
                422);
        assertThat(crossTenant.get("errors").get("tester_id").asString()).contains("membership");

        // Syntactic refusals (AC1): the platform vocabulary, the
        // required identifier — one pass, keyed by field.
        JsonNode invalid =
                register(world.tenantAdminToken, world.tenantA, world.testerId, "HARMONY", "", "", "  ", 400);
        assertThat(invalid.get("errors").get("platform").asString()).contains("HARMONY");
        assertThat(invalid.get("errors").get("identifier").asString()).isEqualTo("identifier is required");

        // The registration audit: the identifier's VALUE never reaches
        // the trail (the privacy note); the platform and owner do.
        var events = audit.eventsOfType(AuditEventType.DEVICE_REGISTERED);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).tenantId()).isEqualTo(world.tenantA);
        assertThat(events.get(0).detail().get("platform")).isEqualTo("IOS");
        assertThat(events.get(0).detail().toString()).doesNotContain("00008101-0A1B2C3D4E5F6789");
    }

    @Test
    void registrationGuardsTheOwnerAndTheTenantPosture() throws Exception {
        World world = world("dv-guard");

        // A tester of another organization: the concealed miss, never a
        // hint which id failed.
        JsonNode foreign = register(
                world.tenantAdminToken, world.tenantA, foreignTester(), "IOS", "iPad", "18.1", "SERF-001", 404);
        assertThat(foreign.get("code").asText()).isEqualTo("TESTER_NOT_FOUND");

        // An unlinked tester of the OWN organization: refused — the
        // device belongs to a tester linked to the tenant.
        JsonNode refused = register(
                world.tenantAdminToken,
                world.tenantA,
                unlinkedTester(world),
                "IOS",
                "iPad",
                "18.1",
                "UNLINKED-001",
                422);
        assertThat(refused.get("code").asText()).isEqualTo("DEVICE_REQUEST_REFUSED");
        assertThat(refused.get("errors").get("tester_id").asString()).contains("membership");

        // A deactivated tenant accepts no new registrations (the
        // affiliation posture), but keeps administrating what exists.
        register(world.tenantAdminToken, world.tenantA, world.testerId, "IOS", "iPhone", "18.0", "PARKED-001", 201);
        jdbc.update("UPDATE tenants SET status = 'DEACTIVATED' WHERE id = ?", world.tenantA);
        JsonNode parked = register(
                world.tenantAdminToken, world.tenantA, world.testerId, "ANDROID", "Pixel 9", "15", "PARKED-002", 422);
        assertThat(parked.get("errors").get("tenant").asString()).contains("deactivated");
        JsonNode stillListed = get(world.tenantAdminToken, devicesUri(world.tenantA, null), 200);
        assertThat(stillListed.get("items").size()).isEqualTo(1);
        jdbc.update("UPDATE tenants SET status = 'ACTIVE' WHERE id = ?", world.tenantA);
    }

    // -----------------------------------------------------------------------
    // AC2: the console's listing and management
    // -----------------------------------------------------------------------

    @Test
    void devicesAreListedFilteredAndManagedThroughTheConsoleSurface() throws Exception {
        World world = world("dv-manage");
        UUID ios = UUID.fromString(register(
                        world.tenantAdminToken,
                        world.tenantA,
                        world.testerId,
                        "IOS",
                        "iPhone 15",
                        "18.2",
                        "MGT-IOS-1",
                        201)
                .get("id")
                .asText());
        UUID android = UUID.fromString(register(
                        world.tenantAdminToken,
                        world.tenantA,
                        world.testerId,
                        "ANDROID",
                        "Pixel 9",
                        "15",
                        "MGT-AND-1",
                        201)
                .get("id")
                .asText());

        // The listing: filters by platform and tester, the owner join
        // in every row.
        JsonNode iosOnly = get(world.tenantAdminToken, devicesUri(world.tenantA, "platform=IOS"), 200);
        assertThat(iosOnly.get("items").size()).isEqualTo(1);
        assertThat(iosOnly.get("items").get(0).get("identifier").asText()).isEqualTo("MGT-IOS-1");
        assertThat(iosOnly.get("items").get(0).get("ownerEmail").asText()).isEqualTo(world.testerEmail);
        JsonNode byTester = get(world.tenantAdminToken, devicesUri(world.tenantA, "testerId=" + world.testerId), 200);
        assertThat(byTester.get("items").size()).isEqualTo(2);
        JsonNode unknownFilter = get(world.tenantAdminToken, devicesUri(world.tenantA, "platform=HARMONY"), 400);
        assertThat(unknownFilter.get("errors").get("platform").asString()).contains("HARMONY");

        // The detail: an unknown id is the genuine miss.
        assertThat(get(world.tenantAdminToken, deviceUri(world.tenantA, UUID.randomUUID()), 404)
                        .get("code")
                        .asText())
                .isEqualTo("DEVICE_NOT_FOUND");

        // The descriptive update: model and OS move, the identity never.
        JsonNode updated = put(
                world.tenantAdminToken,
                deviceUri(world.tenantA, ios),
                Map.of("model", "iPhone 15 Pro Max", "osVersion", "18.3"),
                200);
        assertThat(updated.get("model").asText()).isEqualTo("iPhone 15 Pro Max");
        assertThat(updated.get("osVersion").asText()).isEqualTo("18.3");
        assertThat(updated.get("identifier").asText()).isEqualTo("MGT-IOS-1");
        assertThat(updated.get("platform").asText()).isEqualTo("IOS");

        // The reversible switch: deactivate, the no-op refuses,
        // reactivate returns.
        JsonNode parked = put(
                world.tenantAdminToken,
                deviceUri(world.tenantA, android) + "/status",
                Map.of("status", "DEACTIVATED"),
                200);
        assertThat(parked.get("status").asText()).isEqualTo("DEACTIVATED");
        assertThat(put(
                                world.tenantAdminToken,
                                deviceUri(world.tenantA, android) + "/status",
                                Map.of("status", "DEACTIVATED"),
                                422)
                        .get("code")
                        .asText())
                .isEqualTo("DEVICE_REQUEST_REFUSED");
        JsonNode released = put(
                world.tenantAdminToken, deviceUri(world.tenantA, android) + "/status", Map.of("status", "ACTIVE"), 200);
        assertThat(released.get("status").asText()).isEqualTo("ACTIVE");

        // The status audit carries the from/to pair, never the identifier.
        var crossings = audit.eventsOfType(AuditEventType.DEVICE_STATUS_CHANGED);
        assertThat(crossings).hasSize(2);
        assertThat(crossings.get(0).detail().get("from")).isEqualTo("ACTIVE");
        assertThat(crossings.get(0).detail().get("to")).isEqualTo("DEACTIVATED");
        assertThat(crossings.get(0).detail().toString()).doesNotContain("MGT-AND-1");

        // The surface is admin-only (the privacy note): personas
        // without the tenant authorities conceal at the tenant target.
        assertThat(get(world.qaToken, devicesUri(world.tenantA, null), 404)
                        .get("code")
                        .asString())
                .isEqualTo("TENANT_NOT_FOUND");
        assertThat(get(world.testerToken, devicesUri(world.tenantA, null), 404)
                        .get("code")
                        .asString())
                .isEqualTo("TENANT_NOT_FOUND");

        // The organization administrator holds the overlay (the
        // tenant:testers shape): full read and manage.
        JsonNode orgAdminList = get(world.adminToken, devicesUri(world.tenantA, null), 200);
        assertThat(orgAdminList.get("items").size()).isEqualTo(2);
        register(world.adminToken, world.tenantA, world.testerId, "IOS", "iPad Air", "18.2", "MGT-ORG-1", 201);
    }

    @Test
    void aForeignTenantsDeviceConcealsAsTheGenuineMiss() throws Exception {
        World world = world("dv-iso");
        JsonNode registered = register(
                world.tenantAdminToken, world.tenantA, world.testerId, "IOS", "iPhone", "18.2", "ISO-001", 201);
        UUID deviceId = UUID.fromString(registered.get("id").asText());

        // The tenant seam: tenant B's administrator addresses tenant A's
        // device under tenant B's route — the same 404 as an unknown id.
        JsonNode foreign = get(world.tenantBAdminToken, deviceUri(world.tenantB, deviceId), 404);
        JsonNode miss = get(world.tenantBAdminToken, deviceUri(world.tenantB, UUID.randomUUID()), 404);
        assertThat(foreign.get("code").asString()).isEqualTo("DEVICE_NOT_FOUND");
        assertThat(foreign.get("code")).isEqualTo(miss.get("code"));
        assertThat(foreign.get("detail")).isEqualTo(miss.get("detail"));
        // And tenant B's listing never contains it.
        assertThat(get(world.tenantBAdminToken, devicesUri(world.tenantB, null), 200)
                        .get("items")
                        .size())
                .isZero();
    }

    // -----------------------------------------------------------------------
    // AC3: the provisioning CSV export
    // -----------------------------------------------------------------------

    @Test
    void theExportStreamsTheProvisioningColumnsToAdminsOnly() throws Exception {
        World world = world("dv-csv");
        register(
                world.tenantAdminToken,
                world.tenantA,
                world.testerId,
                "IOS",
                "iPhone 15, Pro",
                "18.2",
                "CSV-\"1\"",
                201);
        UUID parked = UUID.fromString(register(
                        world.tenantAdminToken, world.tenantA, world.testerId, "ANDROID", "Pixel 9", "15", "CSV-2", 201)
                .get("id")
                .asText());
        put(world.tenantAdminToken, deviceUri(world.tenantA, parked) + "/status", Map.of("status", "DEACTIVATED"), 200);

        // The default export is the ACTIVE fleet, RFC 4180 escaped, with
        // the owner email joined in (AC3's columns verbatim).
        Exchange active = export(world.tenantAdminToken, world.tenantA, null, 200);
        assertThat(active.contentType()).startsWith("text/csv");
        assertThat(new String(active.raw()))
                .isEqualTo("identifier,platform,model,os_version,owner_email\n"
                        + "\"CSV-\"\"1\"\"\",IOS,\"iPhone 15, Pro\",18.2," + world.testerEmail + "\n");

        // The full registry and the parked slice answer the status
        // parameter (asserted by containment — the row order is the
        // listing's newest-first, the parked registration second).
        String all = new String(
                export(world.tenantAdminToken, world.tenantA, "ALL", 200).raw());
        assertThat(all)
                .contains("\"CSV-\"\"1\"\"\",IOS,\"iPhone 15, Pro\",18.2," + world.testerEmail)
                .contains("CSV-2,ANDROID,Pixel 9,15," + world.testerEmail);
        String parkedOnly = new String(export(world.tenantAdminToken, world.tenantA, "DEACTIVATED", 200)
                .raw());
        assertThat(parkedOnly).contains("CSV-2,ANDROID").doesNotContain("iPhone 15, Pro");

        // The export is its own admin authority (AC3): the organization
        // administrator passes through the overlay; every persona below
        // conceals at the tenant target; an unknown status token is a
        // 400, never a silent export.
        assertThat(export(world.adminToken, world.tenantA, null, 200).status()).isEqualTo(200);
        export(world.qaToken, world.tenantA, null, 404);
        export(world.testerToken, world.tenantA, null, 404);
        export(world.tenantAdminToken, world.tenantA, "WOKE", 400);

        // The disclosure is the audited act: count and slice, never a
        // value (the denied and the malformed attempts audit nothing).
        // Slice order: default ACTIVE, ALL, DEACTIVATED, then the
        // organization administrator's default ACTIVE export.
        var exports = audit.eventsOfType(AuditEventType.DEVICES_EXPORTED);
        assertThat(exports).hasSize(4);
        assertThat(exports.get(0).tenantId()).isEqualTo(world.tenantA);
        assertThat(exports.get(0).detail().get("count")).isEqualTo("1");
        assertThat(exports.get(0).detail().get("status")).isEqualTo("ACTIVE");
        assertThat(exports.get(1).detail().get("status")).isEqualTo("ALL");
        assertThat(exports.get(1).detail().get("count")).isEqualTo("2");
        assertThat(exports.get(2).detail().get("status")).isEqualTo("DEACTIVATED");
        assertThat(exports.get(0).detail().toString()).doesNotContain("CSV-");
    }

    @Test
    void theExportNeutralizesSpreadsheetFormulaOpeners() throws Exception {
        World world = world("dv-csv-inj");
        // Hostile free text the API accepts today: a model, OS string, or
        // identifier opening with a formula character executes as a
        // formula when an admin opens the provisioning download.
        register(world.tenantAdminToken, world.tenantA, world.testerId, "IOS", "=SUM(A1:A9)", "18.2", "INJ-1", 201);
        register(
                world.tenantAdminToken,
                world.tenantA,
                world.testerId,
                "ANDROID",
                "+HYPERLINK(\"http://evil.example\",\"x\")",
                "@cmd",
                "INJ-2",
                201);
        register(world.tenantAdminToken, world.tenantA, world.testerId, "IOS", "-2+cmd", "iPad-Gen-7", "INJ-3", 201);

        String exported = new String(
                export(world.tenantAdminToken, world.tenantA, "ALL", 200).raw());
        // Every opener is prefixed with the neutral apostrophe, inside the
        // RFC 4180 quoting where both apply.
        assertThat(exported)
                .contains("'=SUM(A1:A9)")
                .contains("\"'+HYPERLINK(\"\"http://evil.example\"\",\"\"x\"\")\"")
                .contains("'@cmd")
                .contains("'-2+cmd")
                // Legitimate hyphenated words never open with the dash and
                // pass through untouched.
                .contains("iPad-Gen-7");
        assertThat(exported)
                .doesNotContain(",=SUM")
                .doesNotContain(",+HYPERLINK")
                .doesNotContain(",-2+cmd");
    }

    // -----------------------------------------------------------------------
    // AC4: the registered device as the feedback context
    // -----------------------------------------------------------------------

    @Test
    void feedbackLinksTheTestersOwnActiveDeviceAndSurfacesTheContext() throws Exception {
        World world = world("dv-fb");
        UUID deviceId = UUID.fromString(register(
                        world.tenantAdminToken,
                        world.tenantA,
                        world.testerId,
                        "IOS",
                        "iPhone 15 Pro",
                        "18.2.1",
                        "FB-DEV-1",
                        201)
                .get("id")
                .asText());
        UUID otherTestersDevice = UUID.fromString(register(
                        world.tenantAdminToken,
                        world.tenantA,
                        world.testerBId,
                        "IOS",
                        "iPhone 14",
                        "17.4",
                        "FB-DEV-2",
                        201)
                .get("id")
                .asText());

        // The link: the tester's own ACTIVE device in the submitting
        // tenant — the view carries the registered context beside the
        // reported free text (AC4).
        Map<String, Object> withDevice = feedbackParts(world, "BUG", "crashes in background");
        withDevice.put("device_id", deviceId.toString());
        withDevice.put("device_model", "Reported Model");
        withDevice.put("os", "Reported OS");
        JsonNode submitted = submit(world.testerToken, withDevice, 201);
        assertThat(submitted.get("device").get("id").asText()).isEqualTo(deviceId.toString());
        assertThat(submitted.get("device").get("model").asText()).isEqualTo("iPhone 15 Pro");
        assertThat(submitted.get("device").get("osVersion").asText()).isEqualTo("18.2.1");
        assertThat(submitted.get("device").get("platform").asText()).isEqualTo("IOS");
        assertThat(submitted.get("deviceModel").asText()).isEqualTo("Reported Model");
        assertThat(submitted.get("os").asText()).isEqualTo("Reported OS");
        // The context carries no identifier (semi-sensitive; feedback
        // readers span beyond the tenant administrators).
        assertThat(submitted.get("device").has("identifier")).isFalse();

        // The stored link and its audit fact.
        assertThat(jdbc.queryForObject(
                        "SELECT device_id FROM feedback WHERE id = ?",
                        UUID.class,
                        UUID.fromString(submitted.get("id").asText())))
                .isEqualTo(deviceId);
        var received = audit.eventsOfType(AuditEventType.FEEDBACK_RECEIVED);
        assertThat(received.get(0).detail().get("deviceId")).isEqualTo(deviceId.toString());

        // The console detail resolves the same auto-context.
        JsonNode detail = get(
                world.adminToken,
                "/api/v1/releases/" + world.releaseId + "/feedback/"
                        + submitted.get("id").asText() + "?applicationId=" + world.app,
                200);
        assertThat(detail.get("device").get("id").asText()).isEqualTo(deviceId.toString());
        assertThat(detail.get("device").get("osVersion").asText()).isEqualTo("18.2.1");

        // Another tester's device and an unknown device are the SAME
        // concealed miss (the artifact-link's own discipline).
        Map<String, Object> foreign = feedbackParts(world, "BUG", "not mine");
        foreign.put("device_id", otherTestersDevice.toString());
        assertThat(submit(world.testerToken, foreign, 404).get("code").asString())
                .isEqualTo("DEVICE_NOT_FOUND");
        Map<String, Object> unknown = feedbackParts(world, "BUG", "no such device");
        unknown.put("device_id", UUID.randomUUID().toString());
        assertThat(submit(world.testerToken, unknown, 404).get("code").asString())
                .isEqualTo("DEVICE_NOT_FOUND");

        // A deactivated device no longer links — the miss again.
        put(
                world.tenantAdminToken,
                deviceUri(world.tenantA, deviceId) + "/status",
                Map.of("status", "DEACTIVATED"),
                200);
        Map<String, Object> parkedDevice = feedbackParts(world, "BUG", "parked device");
        parkedDevice.put("device_id", deviceId.toString());
        assertThat(submit(world.testerToken, parkedDevice, 404).get("code").asString())
                .isEqualTo("DEVICE_NOT_FOUND");

        // A stored link survives the device's later deactivation: the
        // detail's auto-context still resolves (the history posture).
        JsonNode afterParking = get(
                world.adminToken,
                "/api/v1/releases/" + world.releaseId + "/feedback/"
                        + submitted.get("id").asText() + "?applicationId=" + world.app,
                200);
        assertThat(afterParking.get("device").get("id").asText()).isEqualTo(deviceId.toString());
    }

    // -----------------------------------------------------------------------
    // AC4 edges: the cross-tenant device and the park's release
    // -----------------------------------------------------------------------

    @Test
    void aDeviceOfAnotherTenantConcealsOnTheFeedbackLinkAndTheParkReleases() throws Exception {
        World world = world("dv-xten");

        // The tester joins tenant B too, where they own a GENUINE device —
        // a real id, the real owner, the wrong tenant for this submission.
        post(
                world.adminToken,
                "/api/v1/tenants/" + world.tenantB + "/testers",
                Map.of("testerId", world.testerId.toString()),
                201);
        UUID foreignTenantDevice = UUID.fromString(register(
                        world.tenantBAdminToken,
                        world.tenantB,
                        world.testerId,
                        "IOS",
                        "iPhone 15",
                        "18.2",
                        "XTEN-B-1",
                        201)
                .get("id")
                .asText());

        // A submission under tenant A may not attach tenant B's device:
        // the concealment is byte-identical to the unknown-device miss
        // (the client learns nothing about which link failed).
        Map<String, Object> foreign = feedbackParts(world, "BUG", "my device, other tenant");
        foreign.put("device_id", foreignTenantDevice.toString());
        JsonNode foreignMiss = submit(world.testerToken, foreign, 404);
        Map<String, Object> unknown = feedbackParts(world, "BUG", "no such device");
        unknown.put("device_id", UUID.randomUUID().toString());
        JsonNode unknownMiss = submit(world.testerToken, unknown, 404);
        assertThat(foreignMiss.get("code").asString()).isEqualTo("DEVICE_NOT_FOUND");
        assertThat(foreignMiss.get("code")).isEqualTo(unknownMiss.get("code"));
        assertThat(foreignMiss.get("detail")).isEqualTo(unknownMiss.get("detail"));

        // The reversible interplay at the device switch: the parked device
        // refuses the link, the release restores it.
        UUID deviceId = UUID.fromString(register(
                        world.tenantAdminToken,
                        world.tenantA,
                        world.testerId,
                        "IOS",
                        "iPhone 15",
                        "18.2",
                        "XTEN-A-1",
                        201)
                .get("id")
                .asText());
        put(
                world.tenantAdminToken,
                deviceUri(world.tenantA, deviceId) + "/status",
                Map.of("status", "DEACTIVATED"),
                200);
        Map<String, Object> parked = feedbackParts(world, "BUG", "parked for a moment");
        parked.put("device_id", deviceId.toString());
        assertThat(submit(world.testerToken, parked, 404).get("code").asString())
                .isEqualTo("DEVICE_NOT_FOUND");
        put(world.tenantAdminToken, deviceUri(world.tenantA, deviceId) + "/status", Map.of("status", "ACTIVE"), 200);
        Map<String, Object> released = feedbackParts(world, "BUG", "released again");
        released.put("device_id", deviceId.toString());
        JsonNode linked = submit(world.testerToken, released, 201);
        assertThat(linked.get("device").get("id").asText()).isEqualTo(deviceId.toString());
        assertThat(linked.get("device").get("osVersion").asText()).isEqualTo("18.2");
    }

    // -----------------------------------------------------------------------
    // AC5: deactivating the tester keeps the device records
    // -----------------------------------------------------------------------

    @Test
    void deactivatingTheTesterKeepsTheDeviceRecordsAndTheirFeedbackLinks() throws Exception {
        World world = world("dv-keep");
        UUID deviceId = UUID.fromString(register(
                        world.tenantAdminToken,
                        world.tenantA,
                        world.testerId,
                        "IOS",
                        "iPhone 15",
                        "18.2",
                        "KEEP-1",
                        201)
                .get("id")
                .asText());
        Map<String, Object> withDevice = feedbackParts(world, "BUG", "link before deactivation");
        withDevice.put("device_id", deviceId.toString());
        JsonNode submitted = submit(world.testerToken, withDevice, 201);

        // The S6-04 deactivation act (reason mandatory) — the person
        // record, not the tenant membership.
        put(
                world.adminToken,
                "/api/v1/testers/" + world.testerId + "/status",
                Map.of("status", "DISABLED", "reason", "left the beta"),
                200);

        // The records stay (AC5): the rows, the listing, the detail,
        // and the feedback link all resolve — nothing cascades.
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM devices WHERE tenant_id = ? AND tester_id = ?",
                        Integer.class,
                        world.tenantA,
                        world.testerId))
                .isEqualTo(1);
        JsonNode listed = get(world.tenantAdminToken, devicesUri(world.tenantA, null), 200);
        assertThat(listed.get("items").size()).isEqualTo(1);
        JsonNode detail = get(world.tenantAdminToken, deviceUri(world.tenantA, deviceId), 200);
        assertThat(detail.get("identifier").asText()).isEqualTo("KEEP-1");
        JsonNode feedback = get(
                world.adminToken,
                "/api/v1/releases/" + world.releaseId + "/feedback/"
                        + submitted.get("id").asText() + "?applicationId=" + world.app,
                200);
        assertThat(feedback.get("device").get("id").asText()).isEqualTo(deviceId.toString());
    }

    // -----------------------------------------------------------------------
    // Fixtures: the world and its personas
    // -----------------------------------------------------------------------

    private static final class World {
        UUID org;
        String adminToken;
        String ownerToken;
        String qaToken;
        String tenantAdminToken;
        UUID tenantA;
        UUID tenantB;
        String tenantBAdminToken;
        UUID app;
        UUID version;
        UUID releaseId;
        UUID testerId;
        String testerEmail;
        String testerToken;
        UUID testerBId;
        String testerBToken;
    }

    /**
     * The world: an organization, two tenants, a released shared
     * application targeting both, and two testers linked to tenant A
     * (the second one the not-my-device probe's owner).
     */
    private World world(String slug) throws Exception {
        World world = new World();
        world.org = createOrg("Dv " + slug + " Corp");
        world.adminToken = loginOfOrgRoleIn(world.org, slug + "-admin", "ORG_ADMIN");
        world.ownerToken = loginOfOrgRoleIn(world.org, slug + "-owner", "APP_OWNER");
        world.qaToken = loginOfOrgRoleIn(world.org, slug + "-qa", "QA_ENGINEER");
        world.tenantA = createTenant(world.adminToken, world.org, "Dv " + slug + " Bank", "dv-" + slug + "-a");
        world.tenantB = createTenant(world.adminToken, world.org, "Dv " + slug + " Bank B", "dv-" + slug + "-b");
        world.tenantAdminToken = loginOfTenantAdminRoleIn(world.org, world.tenantA, slug + "-tenant-admin");
        world.tenantBAdminToken = loginOfTenantAdminRoleIn(world.org, world.tenantB, slug + "-tenant-b-admin");
        world.app = createSharedApp(
                world.adminToken, world.org, "Dv " + slug + " Banking", "com." + slug.replace("-", "") + ".banking");
        world.version = createVersion(world.ownerToken, world.app, "1.0.0");
        UUID iosArtifact = insertArtifact(world.version, "IOS", 1, "READY");
        UUID androidArtifact = insertArtifact(world.version, "ANDROID", 2, "READY");
        authorize(world.ownerToken, world.app, world.tenantA);
        authorize(world.ownerToken, world.app, world.tenantB);
        Map<String, Object> release = new LinkedHashMap<>();
        release.put("applicationId", world.app.toString());
        release.put("versionId", world.version.toString());
        release.put("ring", "qa");
        release.put(
                "artifacts",
                List.of(
                        Map.of("platform", "IOS", "artifactId", iosArtifact.toString()),
                        Map.of("platform", "ANDROID", "artifactId", androidArtifact.toString())));
        release.put(
                "targets",
                List.of(
                        Map.of("tenantId", world.tenantA.toString(), "testerGroupIds", List.of()),
                        Map.of("tenantId", world.tenantB.toString(), "testerGroupIds", List.of())));
        JsonNode created = post(
                loginOfOrgRoleIn(world.org, slug + "-manager", "RELEASE_MANAGER"), "/api/v1/releases", release, 201);
        world.releaseId = UUID.fromString(created.get("id").asText());

        world.testerId = linkedTester(world, slug + "-tester");
        world.testerEmail = slug + "-tester@example.com";
        world.testerToken = accessToken(login(world.testerEmail, PASSWORD));
        world.testerBId = linkedTester(world, slug + "-tester-b");
        world.testerBToken = accessToken(login(slug + "-tester-b@example.com", PASSWORD));
        return world;
    }

    /** Invites, registers, and tenant-links one tester; returns their tester id. */
    private UUID linkedTester(World world, String slug) {
        String email = slug + "@example.com";
        JsonNode invited = post(
                world.adminToken,
                "/api/v1/testers",
                Map.of("organizationId", world.org.toString(), "email", email, "fullName", "Dv Tester " + slug),
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
                        "Dv Tester " + slug))
                .retrieve()
                .body(String.class);
        post(
                world.adminToken,
                "/api/v1/tenants/" + world.tenantA + "/testers",
                Map.of("testerId", invited.get("id").asText()),
                201);
        return UUID.fromString(invited.get("id").asText());
    }

    /** A tester of ANOTHER organization (the foreign-owner probe). */
    private UUID foreignTester() {
        UUID org = createOrg("Dv Foreign Corp");
        String adminToken = loginOfOrgRoleIn(org, "dv-foreign-admin", "ORG_ADMIN");
        return UUID.fromString(post(
                        adminToken,
                        "/api/v1/testers",
                        Map.of(
                                "organizationId",
                                org.toString(),
                                "email",
                                "dv-foreign-tester@example.com",
                                "fullName",
                                "Dv Foreign Tester"),
                        201)
                .get("id")
                .asText());
    }

    /** A tester of the SAME organization with no membership row in the tenant. */
    private UUID unlinkedTester(World world) {
        return UUID.fromString(post(
                        world.adminToken,
                        "/api/v1/testers",
                        Map.of(
                                "organizationId",
                                world.org.toString(),
                                "email",
                                UUID.randomUUID() + "@example.com",
                                "fullName",
                                "Dv Unlinked Tester"),
                        201)
                .get("id")
                .asText());
    }

    // -----------------------------------------------------------------------
    // HTTP helpers (the feedback suite's shapes)
    // -----------------------------------------------------------------------

    private String devicesUri(UUID tenantId, String query) {
        return "/api/v1/tenants/" + tenantId + "/devices" + (query == null ? "" : "?" + query);
    }

    private String deviceUri(UUID tenantId, UUID deviceId) {
        return "/api/v1/tenants/" + tenantId + "/devices/" + deviceId;
    }

    private JsonNode register(
            String bearer,
            UUID tenantId,
            UUID testerId,
            String platform,
            String model,
            String osVersion,
            String identifier,
            int expectedStatus) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("testerId", testerId.toString());
        body.put("platform", platform);
        if (model != null && !model.isBlank()) {
            body.put("model", model);
        }
        if (osVersion != null && !osVersion.isBlank()) {
            body.put("osVersion", osVersion);
        }
        if (identifier != null && !identifier.isBlank()) {
            body.put("identifier", identifier);
        }
        return post(bearer, "/api/v1/tenants/" + tenantId + "/devices", body, expectedStatus);
    }

    private Exchange export(String bearer, UUID tenantId, String status, int expectedStatus) {
        Exchange recorded = client.get()
                .uri("/api/v1/tenants/" + tenantId + "/devices/export" + (status == null ? "" : "?status=" + status))
                .headers(headers -> headers.setBearerAuth(bearer))
                .exchange((request, response) -> exchangeOf(
                        response.getStatusCode().value(),
                        response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE),
                        response.getBody().readAllBytes()));
        assertThat(recorded.status()).as("GET export -> %d", expectedStatus).isEqualTo(expectedStatus);
        return recorded;
    }

    /** The submission parts of the world's tester (the release link the portal screen addresses). */
    private Map<String, Object> feedbackParts(World world, String type, String description) {
        Map<String, Object> parts = new LinkedHashMap<>();
        parts.put("application_id", world.app.toString());
        parts.put("version_id", world.version.toString());
        parts.put("tenant_id", world.tenantA.toString());
        parts.put("type", type);
        parts.put("description", description);
        return parts;
    }

    private JsonNode submit(String bearer, Map<String, Object> fields, int expectedStatus) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        fields.forEach(body::add);
        return exchangeMultipart(bearer, "/api/v1/feedback", body, expectedStatus);
    }

    private JsonNode exchangeMultipart(
            String bearer, String uri, MultiValueMap<String, Object> parts, int expectedStatus) {
        Exchange recorded = client.post()
                .uri(uri)
                .headers(headers -> headers.setBearerAuth(bearer))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(parts)
                .exchange((request, response) -> exchangeOf(
                        response.getStatusCode().value(),
                        response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE),
                        response.getBody().readAllBytes()));
        assertThat(recorded.status())
                .as("POST %s -> %d, body=%s", uri, expectedStatus, recorded.raw())
                .isEqualTo(expectedStatus);
        return recorded.json();
    }

    private JsonNode get(String bearer, String uri, int expectedStatus) {
        Exchange recorded = client.get()
                .uri(uri)
                .headers(headers -> headers.setBearerAuth(bearer))
                .exchange((request, response) -> exchangeOf(
                        response.getStatusCode().value(),
                        response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE),
                        response.getBody().readAllBytes()));
        assertThat(recorded.status()).as("GET %s -> %d", uri, expectedStatus).isEqualTo(expectedStatus);
        return recorded.json();
    }

    private JsonNode post(String bearer, String uri, Map<String, Object> body, int expectedStatus) {
        Exchange recorded = client.post()
                .uri(uri)
                .headers(headers -> headers.setBearerAuth(bearer))
                .body(body)
                .exchange((request, response) -> exchangeOf(
                        response.getStatusCode().value(),
                        response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE),
                        response.getBody().readAllBytes()));
        assertThat(recorded.status())
                .as("POST %s -> %d, body=%s", uri, expectedStatus, recorded.raw())
                .isEqualTo(expectedStatus);
        return recorded.json();
    }

    private JsonNode put(String bearer, String uri, Map<String, Object> body, int expectedStatus) {
        Exchange recorded = client.put()
                .uri(uri)
                .headers(headers -> headers.setBearerAuth(bearer))
                .body(body)
                .exchange((request, response) -> exchangeOf(
                        response.getStatusCode().value(),
                        response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE),
                        response.getBody().readAllBytes()));
        assertThat(recorded.status())
                .as("PUT %s -> %d, body=%s", uri, expectedStatus, recorded.raw())
                .isEqualTo(expectedStatus);
        return recorded.json();
    }

    private Exchange exchangeOf(int status, String contentType, byte[] bytes) {
        try {
            JsonNode json = contentType != null && contentType.contains("json") && bytes.length > 0
                    ? mapper.readTree(bytes)
                    : mapper.createObjectNode();
            return new Exchange(status, contentType, bytes, json);
        } catch (Exception e) {
            throw new IllegalStateException("could not decode the exchange body", e);
        }
    }

    /** One exchanged response: status, content type, raw bytes, decoded JSON (an empty object for non-JSON bodies). */
    private record Exchange(int status, String contentType, byte[] raw, JsonNode json) {}

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
                platform.toLowerCase(java.util.Locale.ROOT) + "-" + build + "." + extension,
                "fixture/" + artifactId + "/artifact",
                "a1".repeat(32),
                1024L,
                build,
                status,
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

    private UUID createSharedApp(String token, UUID organizationId, String name, String bundleId) {
        Map<String, Object> body = new HashMap<>();
        body.put("ownershipScope", "ORGANIZATION");
        body.put("organizationId", organizationId.toString());
        body.put("name", name);
        body.put("platforms", List.of("IOS", "ANDROID"));
        body.put("bundleId", bundleId);
        body.put("packageName", bundleId);
        return UUID.fromString(
                post(token, "/api/v1/applications", body, 201).get("id").asText());
    }

    private void authorize(String token, UUID applicationId, UUID tenantId) {
        post(token, "/api/v1/applications/" + applicationId + "/tenants/" + tenantId, Map.of(), 201);
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
                        "Dv " + role))
                .retrieve()
                .body(String.class);
        return accessToken(login(email, PASSWORD));
    }

    /** Registers a user, adds them to the tenant as TENANT_ADMIN; returns their token. */
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
                        "Dv Tenant Admin"))
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
        String claims = bearer.split("\\.")[1];
        try {
            return UUID.fromString(mapper.readTree(Base64.getUrlDecoder().decode(claims))
                    .get("sub")
                    .asText());
        } catch (Exception e) {
            throw new IllegalStateException("could not decode the token", e);
        }
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
            return mapper.readTree(tokensJson).get("accessToken").asString();
        } catch (Exception e) {
            throw new IllegalStateException("could not parse the token pair", e);
        }
    }
}
