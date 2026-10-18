package com.shipperdog.api.otabundle;

import static org.assertj.core.api.Assertions.assertThat;

import com.shipperdog.api.otabundle.internal.service.OtaSigningKeyService;
import java.net.URI;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The S16-03 acceptance flow (issue #165, ADR-0010 workstream 2): the
 * full bundle authoring lifecycle — create (the dedicated versions row
 * plus the artifact store's direct-to-storage session, D2/D5), bytes
 * to the granted URL, finalize+sign (the store's hash-back manifest,
 * the app's Ed25519 key from the secret-store port, D8), the SIGNED
 * row, the {@code OTA_BUNDLE_SIGNED} outbox event (ADR-0007), the
 * public key + key id retrieval (the console copy surface), replay
 * semantics, the listing, and the module's own refusal shapes. The
 * object-storage target is the compose GCS emulator image (ADR-0003),
 * the same Testcontainer the artifact upload suite drives.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
            "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef"
        })
@Testcontainers
class OtaBundleLifecycleIntegrationTest {

    private static final String DEV_ADMIN_EMAIL = "dev@shipperdog.local";
    private static final String DEV_ADMIN_PASSWORD = "shipperdog-dev-admin";
    private static final String PASSWORD = "a-perfectly-valid-password";

    /** The compose GCS emulator image, in a container (ADR-0003). */
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
    static void artifactStorage(DynamicPropertyRegistry registry) {
        registry.add(
                "shipperdog.artifact.storage.host", () -> "http://" + GCS.getHost() + ":" + GCS.getMappedPort(4443));
    }

    @LocalServerPort
    int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper mapper;

    private RestClient client;

    @BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    // -----------------------------------------------------------------------
    // AC1-AC4: create -> bytes -> finalize+sign -> SIGNED, with the
    // manifest, the per-app key, the outbox event, and the public-key
    // read
    // -----------------------------------------------------------------------

    @Test
    void bundleLifecycleCreatesSignsAndPublishes() throws Exception {
        OtaWorld world = otaWorld("ob-life");
        byte[] bytes = payload(2048);

        JsonNode draft = createBundle(world.token, world.appId, "1.4.2", "react-native", "bundle-1.4.2.zip", 201);
        UUID bundleId = UUID.fromString(draft.get("id").asText());
        UUID versionId = UUID.fromString(draft.get("versionId").asText());
        assertThat(draft.get("status").asText()).isEqualTo("UPLOADING");
        assertThat(draft.get("upload").get("uploadUrl")).isNotNull();

        // D2: the bundle's OWN versions row, labeled with the bundle
        // version; V55's schema keeps the pre-bytes bundle row
        // impossible, so UPLOADING lives in the session the draft
        // answered with.
        assertThat(jdbc.queryForObject("SELECT version_name FROM versions WHERE id = ?", String.class, versionId))
                .isEqualTo("1.4.2");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ota_bundles WHERE id = ?", Integer.class, bundleId))
                .isZero();

        pushBytes(draft.get("upload").get("uploadUrl").asText(), bytes);
        JsonNode finalized = post(
                world.token,
                "/api/v1/ota/bundles/" + bundleId + "/finalize",
                Map.of("versionId", versionId.toString()),
                201);
        assertThat(finalized.get("created").asBoolean()).isTrue();
        JsonNode bundle = finalized.get("bundle");
        assertThat(bundle.get("status").asText()).isEqualTo("SIGNED");
        assertThat(bundle.get("sha256").asText()).isEqualTo(sha256Of(bytes));
        assertThat(bundle.get("sizeBytes").asLong()).isEqualTo(bytes.length);
        assertThat(bundle.get("platformTag").asText()).isEqualTo("react-native");
        assertThat(bundle.get("bundleVersion").asText()).isEqualTo("1.4.2");
        assertThat(bundle.get("forced").asBoolean()).isFalse();
        assertThat(bundle.get("keyId")).isNotNull();
        assertThat(bundle.get("signature")).isNotNull();

        // The row landed with the V55 coherent pairs; the binary is an
        // ordinary READY artifact (D5 — no package pipeline touched it).
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, sha256, size_bytes, key_id, artifact_id FROM ota_bundles WHERE id = ?", bundleId);
        assertThat(row.get("status")).isEqualTo("SIGNED");
        assertThat(row.get("key_id")).isEqualTo(bundle.get("keyId").asText());
        assertThat(row.get("artifact_id").toString())
                .isEqualTo(bundle.get("artifactId").asText());
        assertThat(jdbc.queryForObject("SELECT status FROM artifacts WHERE id = ?", String.class, bundleId))
                .isEqualTo("READY");

        // AC2: the signature verifies under the retrievable public key
        // (the console copy surface, AC4) over the manifest checksum.
        JsonNode key = get(world.token, "/api/v1/ota/signing-keys?applicationId=" + world.appId, 200);
        assertThat(key.get("keyId").asText()).isEqualTo(bundle.get("keyId").asText());
        assertThat(key.get("algorithm").asText()).isEqualTo("Ed25519");
        assertThat(OtaSigningKeyService.verify(
                        key.get("publicKey").asText(),
                        bundle.get("sha256").asText(),
                        bundle.get("signature").asText()))
                .as("the bundle sha256 verifies under the app's public key")
                .isTrue();

        // AC3: the outbox row committed with the bundle (ADR-0007).
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM notification_outbox WHERE event_name = 'OTA_BUNDLE_SIGNED'"
                                + " AND application_id = ? AND artifact_id = ?",
                        Integer.class,
                        world.appId,
                        bundleId))
                .isEqualTo(1);

        // The reads: the item and the listing.
        JsonNode read = get(world.token, "/api/v1/ota/bundles/" + bundleId + "?applicationId=" + world.appId, 200);
        assertThat(read.get("id").asText()).isEqualTo(bundleId.toString());
        JsonNode list = get(world.token, "/api/v1/ota/bundles?applicationId=" + world.appId + "&limit=10", 200);
        assertThat(list.get("items").size()).isEqualTo(1);
        assertThat(list.get("items").get(0).get("id").asText()).isEqualTo(bundleId.toString());

        // The replay: the terminal state is the answer (created=false).
        JsonNode replay = post(
                world.token,
                "/api/v1/ota/bundles/" + bundleId + "/finalize",
                Map.of("versionId", versionId.toString()),
                200);
        assertThat(replay.get("created").asBoolean()).isFalse();
        assertThat(replay.get("bundle").get("id").asText()).isEqualTo(bundleId.toString());
    }

    // -----------------------------------------------------------------------
    // AC2: per-app key custody — one key, reused across bundles, its
    // private half only behind the secret-store port
    // -----------------------------------------------------------------------

    @Test
    void signingKeyIsPerAppAndPrivateMaterialStaysInTheStore() throws Exception {
        OtaWorld world = otaWorld("ob-keys");
        JsonNode first = authorSignedBundle(world, "2.0.0", "electron", "asgard-2.0.0.zip", payload(512));
        JsonNode second = authorSignedBundle(world, "2.0.1", "electron", "asgard-2.0.1.zip", payload(640));

        // One app, one key: both bundles name the same key id.
        assertThat(second.get("keyId").asText()).isEqualTo(first.get("keyId").asText());

        // Before the first bundle there was no key; after, exactly one
        // ACTIVE row whose only private reference is the store's.
        Map<String, Object> keyRow = jdbc.queryForMap(
                "SELECT key_id, public_key, secret_reference FROM ota_signing_keys"
                        + " WHERE application_id = ? AND revoked_at IS NULL",
                world.appId);
        assertThat(keyRow.get("key_id")).isEqualTo(first.get("keyId").asText());
        assertThat((String) keyRow.get("secret_reference")).startsWith("local-envelope://");
        // The public key column never carries PKCS8 material (the raw
        // 32-byte half Base64s to 44 characters).
        assertThat(((String) keyRow.get("public_key"))).hasSize(44);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM ota_signing_keys WHERE application_id = ?", Integer.class, world.appId))
                .isEqualTo(1);

        // Both signatures verify under the one public key.
        JsonNode key = get(world.token, "/api/v1/ota/signing-keys?applicationId=" + world.appId, 200);
        assertThat(OtaSigningKeyService.verify(
                        key.get("publicKey").asText(),
                        first.get("sha256").asText(),
                        first.get("signature").asText()))
                .isTrue();
        assertThat(OtaSigningKeyService.verify(
                        key.get("publicKey").asText(),
                        second.get("sha256").asText(),
                        second.get("signature").asText()))
                .isTrue();
    }

    // -----------------------------------------------------------------------
    // AC5 and the module's own refusals
    // -----------------------------------------------------------------------

    @Test
    void authoringGuardsRefuseHonestly() throws Exception {
        OtaWorld world = otaWorld("ob-guard");
        OtaWorld plain = plainWorld("ob-plain");

        // The OTA opt-in is real: an app that never enabled it is a 409.
        Recording disabled =
                postRaw(plain.token, "/api/v1/ota/bundles", bundleBody(plain.appId, "1.0.0", "ios", "b.zip"));
        assertThat(disabled.status).isEqualTo(409);
        assertThat(disabled.body.get("code").asText()).isEqualTo("APPLICATION_NOT_OTA_ENABLED");

        // Shape rejections name their fields.
        Recording malformed =
                postRaw(world.token, "/api/v1/ota/bundles", bundleBody(world.appId, "1.x", "x".repeat(33), "b.zip"));
        assertThat(malformed.status).isEqualTo(400);
        assertThat(malformed.body.get("errors").get("bundleVersion")).isNotNull();
        assertThat(malformed.body.get("errors").get("platformTag")).isNotNull();

        // A duplicate bundle version is the version module's own 409
        // (D2: the label IS the versions row's name).
        createBundle(world.token, world.appId, "3.1.0", "ios", "one.zip", 201);
        Recording taken =
                postRaw(world.token, "/api/v1/ota/bundles", bundleBody(world.appId, "3.1.0", "ios", "two.zip"));
        assertThat(taken.status).isEqualTo(409);

        // Finalize before the client stored anything: broken request.
        JsonNode empty = createBundle(world.token, world.appId, "3.2.0", "ios", "empty.zip", 201);
        post(
                world.token,
                "/api/v1/ota/bundles/" + empty.get("id").asText() + "/finalize",
                Map.of("versionId", empty.get("versionId").asText()),
                400);

        // Unknown bundle and mismatched version are the concealed miss.
        assertThat(postRaw(
                                world.token,
                                "/api/v1/ota/bundles/" + UUID.randomUUID() + "/finalize",
                                Map.of("versionId", empty.get("versionId").asText()))
                        .status)
                .isEqualTo(404);
        JsonNode other = createBundle(world.token, world.appId, "3.3.0", "ios", "other.zip", 201);
        assertThat(postRaw(
                                world.token,
                                "/api/v1/ota/bundles/" + other.get("id").asText() + "/finalize",
                                Map.of("versionId", empty.get("versionId").asText()))
                        .status)
                .isEqualTo(404);

        // The signing-key read before the first bundle: the honest miss
        // (generation is a finalize-time act, never a read side effect).
        assertThat(exchange("/api/v1/ota/signing-keys?applicationId=" + world.appId, world.token, "GET", null).status)
                .isEqualTo(404);
    }

    // -----------------------------------------------------------------------
    // The REVOKED crossing: the author's withdrawal of a SIGNED bundle
    // -----------------------------------------------------------------------

    @Test
    void signedBundlesRevocationIsAGuardedCrossing() throws Exception {
        OtaWorld world = otaWorld("ob-revoke");
        JsonNode signed = authorSignedBundle(world, "4.0.0", "android", "kit-4.0.0.zip", payload(384));
        UUID bundleId = UUID.fromString(signed.get("id").asText());

        JsonNode revoked = post(
                world.token,
                "/api/v1/ota/bundles/" + bundleId + "/revoke",
                Map.of("applicationId", world.appId.toString()),
                200);
        assertThat(revoked.get("status").asText()).isEqualTo("REVOKED");

        // Only a SIGNED bundle is revocable through this surface.
        assertThat(postRaw(
                                world.token,
                                "/api/v1/ota/bundles/" + bundleId + "/revoke",
                                Map.of("applicationId", world.appId.toString()))
                        .status)
                .isEqualTo(409);

        // The revoked bundle still lists (history, not the working set
        // the S16-07 check resolution will refuse).
        JsonNode listed = get(world.token, "/api/v1/ota/bundles?applicationId=" + world.appId + "&status=REVOKED", 200);
        assertThat(listed.get("items").size()).isEqualTo(1);
    }

    // -----------------------------------------------------------------------
    // The claims replay's remaining surfaces: forced + minAppVersion, the
    // derived storage platform, and the key id's SDK contract
    // -----------------------------------------------------------------------

    @Test
    void forcedAndMinAppVersionRideTheClaimsAndTheStoragePlatformDerives() throws Exception {
        OtaWorld world = otaWorld("ob-claims");
        Map<String, Object> body = bundleBody(world.appId, "5.0.0", "flutter", "flux-5.0.0.tgz");
        body.put("forced", true);
        body.put("minAppVersion", "1.2.0");
        JsonNode draft = post(world.token, "/api/v1/ota/bundles", body, 201);
        pushBytes(draft.get("upload").get("uploadUrl").asText(), payload(256));
        JsonNode signed = post(
                        world.token,
                        "/api/v1/ota/bundles/" + draft.get("id").asText() + "/finalize",
                        Map.of("versionId", draft.get("versionId").asText()),
                        201)
                .get("bundle");

        // The authoring claims asserted at finalize are the ones create
        // captured — forced and the optional min app version included.
        assertThat(signed.get("forced").asBoolean()).isTrue();
        assertThat(signed.get("minAppVersion").asText()).isEqualTo("1.2.0");

        // The documented derived-value mapping: a tag outside the closed
        // V1 storage vocabulary rides the application's FIRST registered
        // platform (inert metadata — OTA resolves binaries by artifact id).
        assertThat(jdbc.queryForObject(
                        "SELECT platform FROM artifacts WHERE id = ?",
                        String.class,
                        UUID.fromString(signed.get("id").asText())))
                .isEqualTo("IOS");

        // The SDK contract (S16-12/S16-13): the key id is computable from
        // the public key the console hands out, with no other facts.
        JsonNode key = get(world.token, "/api/v1/ota/signing-keys?applicationId=" + world.appId, 200);
        assertThat(OtaSigningKeyService.keyIdOf(
                        Base64.getDecoder().decode(key.get("publicKey").asText())))
                .isEqualTo(signed.get("keyId").asText());
    }

    // -----------------------------------------------------------------------
    // The world and its helpers
    // -----------------------------------------------------------------------

    private record OtaWorld(UUID orgId, UUID tenantId, UUID appId, String token) {}

    /** The OTA authoring world: an org, a DEVELOPER (the artifact family's persona), a tenant app with OTA enabled. */
    private OtaWorld otaWorld(String suffix) {
        return world(suffix, true);
    }

    /** The same world without the OTA opt-in (V55's default). */
    private OtaWorld plainWorld(String suffix) {
        return world(suffix, false);
    }

    private OtaWorld world(String suffix, boolean otaEnabled) {
        UUID orgId = createOrg("Ob " + suffix + " Corp");
        String ownerToken = loginOfOrgRoleIn(orgId, "ob-" + suffix + "-owner@example.com", "APP_OWNER");
        String token = loginOfOrgRoleIn(orgId, "ob-" + suffix + "-dev@example.com", "DEVELOPER");
        String adminToken = loginOfOrgRoleIn(orgId, "ob-" + suffix + "-admin@example.com", "ORG_ADMIN");
        UUID tenantId = createTenant(adminToken, orgId, "Ob " + suffix + " Bank", "crm-ob-" + suffix);
        UUID appId = createTenantApp(
                ownerToken,
                tenantId,
                "Ob " + suffix + " App",
                "com.ob." + suffix.replace("-", ""),
                List.of("IOS", "ANDROID"));
        if (otaEnabled) {
            jdbc.update("UPDATE applications SET ota_enabled = true WHERE id = ?", appId);
        }
        return new OtaWorld(orgId, tenantId, appId, token);
    }

    /** Creates one bundle, streams its bytes, finalizes+signs, and answers the SIGNED bundle view. */
    private JsonNode authorSignedBundle(OtaWorld world, String version, String tag, String fileName, byte[] bytes) {
        JsonNode draft = createBundle(world.token, world.appId, version, tag, fileName, 201);
        pushBytes(draft.get("upload").get("uploadUrl").asText(), bytes);
        return post(
                        world.token,
                        "/api/v1/ota/bundles/" + draft.get("id").asText() + "/finalize",
                        Map.of("versionId", draft.get("versionId").asText()),
                        201)
                .get("bundle");
    }

    private static Map<String, Object> bundleBody(UUID appId, String version, String tag, String fileName) {
        Map<String, Object> body = new HashMap<>();
        body.put("applicationId", appId.toString());
        body.put("bundleVersion", version);
        body.put("platformTag", tag);
        body.put("fileName", fileName);
        return body;
    }

    private JsonNode createBundle(String token, UUID appId, String version, String tag, String fileName, int expected) {
        return post(token, "/api/v1/ota/bundles", bundleBody(appId, version, tag, fileName), expected);
    }

    /** The client's direct-to-storage leg: PUT the bytes to the session URL. */
    private Recording pushBytes(String uploadUrl, byte[] bytes) {
        return client.put()
                .uri(URI.create(uploadUrl))
                .header("Content-Range", "bytes 0-" + (bytes.length - 1) + "/" + bytes.length)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(bytes)
                .exchange((request, response) -> {
                    byte[] raw = response.getBody().readAllBytes();
                    return new Recording(
                            response.getStatusCode().value(),
                            response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE),
                            raw.length == 0 ? mapper.createObjectNode() : mapper.readTree(raw));
                });
    }

    private UUID createTenantApp(String token, UUID tenantId, String name, String bundleId, List<String> platforms) {
        Map<String, Object> body = new HashMap<>();
        body.put("tenantId", tenantId.toString());
        body.put("name", name);
        body.put("platforms", platforms);
        body.put("bundleId", platforms.contains("IOS") ? bundleId : null);
        body.put("packageName", platforms.contains("ANDROID") ? bundleId : null);
        return UUID.fromString(
                post(token, "/api/v1/applications", body, 201).get("id").asText());
    }

    private UUID createTenant(String token, UUID organizationId, String name, String externalId) {
        JsonNode created = post(
                token,
                "/api/v1/tenants",
                Map.of("organizationId", organizationId.toString(), "name", name, "externalId", externalId),
                201);
        return UUID.fromString(created.get("id").asText());
    }

    /** Creates one organization per test; repeated calls for the same name are a fixture bug. */
    private UUID createOrg(String orgName) {
        String devToken = accessToken(login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD));
        return UUID.fromString(post(devToken, "/api/v1/organizations", Map.of("name", orgName), 201)
                .get("id")
                .asText());
    }

    /** Registers a user with the given role into an EXISTING organization; returns their token. */
    private String loginOfOrgRoleIn(UUID orgId, String email, String role) {
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
                        "Ota " + role))
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

    private JsonNode post(String bearer, String uri, Map<String, Object> body, Integer expectedStatus) {
        Recording recording = exchange(uri, bearer, "POST", body);
        if (expectedStatus != null) {
            assertThat(recording.status)
                    .as("POST %s -> %s", uri, recording.body)
                    .isEqualTo(expectedStatus);
        }
        return recording.body;
    }

    private Recording postRaw(String bearer, String uri, Map<String, Object> body) {
        return exchange(uri, bearer, "POST", body);
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

    private static byte[] payload(int size) {
        byte[] bytes = new byte[size];
        for (int i = 0; i < size; i++) {
            bytes[i] = (byte) ('a' + (i % 26));
        }
        return bytes;
    }

    private static String sha256Of(byte[] bytes) throws Exception {
        return java.util.HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private record Recording(int status, String contentType, JsonNode body) {

        JsonNode expect(int expectedStatus) {
            assertThat(status).as("HTTP status for %s", body.toString()).isEqualTo(expectedStatus);
            return body;
        }
    }
}
