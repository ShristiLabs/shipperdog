package com.shipperdog.api.feedback;

import static org.assertj.core.api.Assertions.assertThat;

import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.identity.AuditCapture;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ByteArrayResource;
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
 * End-to-end acceptance flow for the feedback submission surface (issue
 * #46, S11-01, FR-027, PRD 8.13): the six-type submission with
 * server-attached context (AC1/AC2 — the client never supplies an
 * identity field; the tester, tenant, and release link resolve from the
 * session and the release), the attachment tier (AC3 — png/jpg/mp4/
 * txt/log inside configured limits, tenant-scoped object prefix,
 * authorized-roles-only download), the filtered listing (AC4 — by
 * release, type, status, tenant, cursor-paginated), the audit and
 * event emission (AC5 — FEEDBACK_RECEIVED on both channels), and the
 * security-issue restricted visibility (AC6 — org admins and security
 * reviewers alone).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
            "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef",
            // The suite's attachment limits: small enough that an
            // oversized fixture is cheap to mint, large enough that the
            // success-path fixtures fit.
            "shipperdog.feedback.storage.max-screenshot-bytes=1024",
            "shipperdog.feedback.storage.max-log-bytes=1024",
            "shipperdog.feedback.storage.max-video-bytes=1024"
        })
@Testcontainers
@Import({AuditCapture.class, FeedbackIntegrationTest.FeedbackFixtures.class})
class FeedbackIntegrationTest {

    private static final String DEV_ADMIN_EMAIL = "dev@shipperdog.local";
    private static final String DEV_ADMIN_PASSWORD = "shipperdog-dev-admin";
    private static final String PASSWORD = "a-perfectly-valid-password";

    /** The compose GCS emulator image, in a container (the artifact suite's fixture). */
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
    private FeedbackEventRecorder received;

    @Autowired
    private ObjectMapper mapper;

    private RestClient client;

    private int versionCounter;

    @BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
        audit.reset();
        received.events.clear();
    }

    // -----------------------------------------------------------------------
    // AC1/AC2: the six types, the non-empty description, the
    // server-attached context the client cannot spoof
    // -----------------------------------------------------------------------

    @Test
    void allSixTypesSubmitWithServerAttachedContext() throws Exception {
        World world = releasedWorld("fb-six");
        List<String> types = List.of("BUG", "FEEDBACK", "CRASH", "FEATURE_REQUEST", "UX_ISSUE", "SECURITY_ISSUE");
        for (String type : types) {
            JsonNode submitted = submit(
                    world,
                    world.testerToken,
                    parts(world, type, "Reproducible " + type.toLowerCase() + " on the login screen"),
                    201);
            // The view proves the context is server-attached: none of
            // these fields were part of the request.
            assertThat(submitted.get("applicationId").asText()).isEqualTo(world.app.toString());
            assertThat(submitted.get("versionId").asText()).isEqualTo(world.version.toString());
            assertThat(submitted.get("releaseId").asText()).isEqualTo(world.releaseId.toString());
            assertThat(submitted.get("releaseRef").asText()).isEqualTo(world.releaseRef);
            assertThat(submitted.get("organizationId").asText()).isEqualTo(world.org.toString());
            assertThat(submitted.get("submitterTenantId").asText()).isEqualTo(world.tenantA.toString());
            assertThat(submitted.get("testerId").asText()).isEqualTo(world.testerId.toString());
            assertThat(submitted.get("status").asText()).isEqualTo("OPEN");
            assertThat(submitted.get("type").asText()).isEqualTo(type);
        }

        // The row the server wrote: every identity column resolved
        // server-side, the status born OPEN (triage is S11-02).
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT tester_id, submitter_tenant_id, organization_id, application_id, version_id, status "
                        + "FROM feedback WHERE release_id = ? AND type = 'BUG'",
                world.releaseId);
        assertThat(row.get("tester_id")).isEqualTo(world.testerId);
        assertThat(row.get("submitter_tenant_id")).isEqualTo(world.tenantA);
        assertThat(row.get("organization_id")).isEqualTo(world.org);
        assertThat(row.get("status")).isEqualTo("OPEN");

        // The description gate (AC1): blank is refused, unknown type is refused.
        JsonNode blank = submit(world, world.testerToken, parts(world, "BUG", "   "), 400);
        assertThat(blank.get("errors").get("description").asString()).isEqualTo("description is required");
        JsonNode unknownType = submit(world, world.testerToken, parts(world, "PRAISE", "great app"), 400);
        assertThat(unknownType.get("errors").get("type").asString()).contains("PRAISE");
    }

    @Test
    void spoofedIdentityAndBrokenReleaseLinksConcealAsTheMiss() throws Exception {
        World world = releasedWorld("fb-spoof");

        // A tenant the release targets but the tester was never linked
        // to: same concealed miss as an unknown release.
        JsonNode foreignTenant = submit(world, world.testerToken, parts(world, "BUG", "broken", world.tenantB), 404);
        assertThat(foreignTenant.get("code").asText()).isEqualTo("FEEDBACK_RELEASE_NOT_FOUND");

        // A version with no release at all (the release link IS the
        // submission's standing).
        UUID unreleased = createVersion(world.ownerToken, world.app, "9.9.9");
        Map<String, Object> unreleasedParts = parts(world, "BUG", "no release evaluates this");
        unreleasedParts.put("version_id", unreleased.toString());
        JsonNode noRelease = submit(world, world.testerToken, unreleasedParts, 404);
        assertThat(noRelease.get("code").asText()).isEqualTo("FEEDBACK_RELEASE_NOT_FOUND");

        // A console persona holds no tester record: the same miss,
        // never a 500 or a hint.
        JsonNode consolePersona = submit(world, world.adminToken, parts(world, "BUG", "operator report"), 404);
        assertThat(consolePersona.get("code").asText()).isEqualTo("FEEDBACK_RELEASE_NOT_FOUND");

        // The named build must be a READY artifact of the release's version.
        Map<String, Object> staleArtifact = parts(world, "BUG", "wrong build");
        staleArtifact.put(
                "artifact_id",
                insertArtifact(world.version, "IOS", 99, "VALIDATING").toString());
        JsonNode notReady = submit(world, world.testerToken, staleArtifact, 404);
        assertThat(notReady.get("code").asText()).isEqualTo("ARTIFACT_NOT_FOUND");

        // A suspended membership (the two-switches rule, live): the
        // tester's own standing is gone, and the answer stays the
        // concealed miss.
        jdbc.update(
                "UPDATE tenant_tester_memberships SET status = 'SUSPENDED' WHERE tenant_id = ? AND tester_id = ?",
                world.tenantA,
                world.testerId);
        JsonNode suspended = submit(world, world.testerToken, parts(world, "BUG", "suspended"), 404);
        assertThat(suspended.get("code").asText()).isEqualTo("FEEDBACK_RELEASE_NOT_FOUND");
    }

    @Test
    void probeValidArtifact() throws Exception {
        World world = releasedWorld("fb-probe");
        Map<String, Object> parts = parts(world, "BUG", "valid artifact probe");
        parts.put("artifact_id", world.iosArtifact.toString());
        JsonNode ok = submit(world, world.testerToken, parts, 201);
        assertThat(ok.get("artifactId").asText()).isEqualTo(world.iosArtifact.toString());
    }

    @Test
    void tenantOwnedApplicationsSubmitAndReadThroughTheOwningTenantScope() throws Exception {
        // The tenant-owned shape: the owning tenant holds the
        // application by ownership, the release targets it alone, and
        // the console read binds the owning tenant's scope (the
        // application profile's tenant branch — the shared world's
        // tests exercise the organization branch).
        World world = new World();
        world.org = createOrg("Fb Tn Corp");
        world.adminToken = loginOfOrgRoleIn(world.org, "fb-tn-admin", "ORG_ADMIN");
        world.ownerToken = loginOfOrgRoleIn(world.org, "fb-tn-owner", "APP_OWNER");
        world.tenantA = createTenant(world.adminToken, world.org, "Fb Tn Bank", "fb-tn-tenant");
        world.app = createTenantApp(world.adminToken, world.tenantA, "Fb Tn Banking", "com.fbten.banking");
        world.version = createVersion(world.ownerToken, world.app, "1.0.0");
        world.iosArtifact = insertArtifact(world.version, "IOS", 1, "READY");
        Map<String, Object> release = new LinkedHashMap<>();
        release.put("applicationId", world.app.toString());
        release.put("versionId", world.version.toString());
        release.put("ring", "qa");
        release.put("artifacts", List.of(Map.of("platform", "IOS", "artifactId", world.iosArtifact.toString())));
        release.put("targets", List.of(Map.of("tenantId", world.tenantA.toString(), "testerGroupIds", List.of())));
        JsonNode created =
                post(loginOfOrgRoleIn(world.org, "fb-tn-manager", "RELEASE_MANAGER"), "/api/v1/releases", release, 201);
        world.releaseId = UUID.fromString(created.get("id").asText());

        String testerEmail = "fb-tn-tester@example.com";
        JsonNode invited = post(
                world.adminToken,
                "/api/v1/testers",
                Map.of("organizationId", world.org.toString(), "email", testerEmail, "fullName", "Fb Tn Tester"),
                201);
        client.post()
                .uri("/api/v1/auth/register")
                .body(Map.of(
                        "invitationToken",
                        invited.get("invitation").get("invitationToken").asString(),
                        "email",
                        testerEmail,
                        "password",
                        PASSWORD,
                        "fullName",
                        "Fb Tn Tester"))
                .retrieve()
                .body(String.class);
        post(
                world.adminToken,
                "/api/v1/tenants/" + world.tenantA + "/testers",
                Map.of("testerId", invited.get("id").asText()),
                201);
        world.testerId = UUID.fromString(invited.get("id").asText());
        world.testerToken = accessToken(login(testerEmail, PASSWORD));

        JsonNode submitted = submit(world, world.testerToken, parts(world, "BUG", "tenant-owned bug"), 201);
        assertThat(submitted.get("submitterTenantId").asText()).isEqualTo(world.tenantA.toString());
        JsonNode listed = get(world.adminToken, listUri(world, null), 200);
        assertThat(listed.get("items").size()).isEqualTo(1);
    }

    // -----------------------------------------------------------------------
    // AC3: attachments — limits, tenant-scoped prefix, authorized egress
    // -----------------------------------------------------------------------

    @Test
    void attachmentsLandUnderTheTenantPrefixAndStreamToAuthorizedReadersOnly() throws Exception {
        World world = releasedWorld("fb-att");
        Map<String, Object> withFiles = parts(world, "CRASH", "crashes on launch");
        withFiles.put(
                "attachments",
                List.of(
                        filePart("screenshot.png", "png-bytes-fixture".getBytes()),
                        filePart("logs.txt", "log-line-1\nlog-line-2".getBytes())));
        JsonNode submitted = submit(world, world.testerToken, withFiles, 201);
        assertThat(submitted.get("attachments").size()).isEqualTo(2);
        assertThat(submitted.get("attachments").get(0).get("category").asText()).isEqualTo("SCREENSHOT");
        assertThat(submitted.get("attachments").get(1).get("category").asText()).isEqualTo("LOG");

        // The tenant-scoped object prefix (AC3) and the streamed checksum.
        Map<String, Object> attachmentRow = jdbc.queryForMap(
                "SELECT storage_key, checksum, size_bytes FROM feedback_attachments WHERE feedback_id = ? AND file_name = 'screenshot.png'",
                UUID.fromString(submitted.get("id").asText()));
        assertThat((String) attachmentRow.get("storage_key"))
                .startsWith(world.org + "/" + world.tenantA + "/feedback/"
                        + submitted.get("id").asText() + "/");
        assertThat(attachmentRow.get("size_bytes")).isEqualTo(17L);

        // The extension gate and the per-kind size limit: 422 keyed by
        // file name, before any write.
        Map<String, Object> rejected = parts(world, "BUG", "bad files");
        rejected.put(
                "attachments",
                List.of(
                        filePart("payload.exe", "nope".getBytes()),
                        filePart("huge.png", "x".repeat(2048).getBytes()),
                        filePart("dupe.txt", "one".getBytes()),
                        filePart("dupe.txt", "two".getBytes())));
        JsonNode refusal = submit(world, world.testerToken, rejected, 422);
        assertThat(refusal.get("code").asText()).isEqualTo("ATTACHMENT_REJECTED");
        assertThat(refusal.get("errors").get("payload.exe").asString()).contains("png, jpg, mp4, txt, log");
        assertThat(refusal.get("errors").get("huge.png").asString()).contains("screenshot limit");
        assertThat(refusal.get("errors").get("dupe.txt").asString()).contains("duplicate");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM feedback WHERE release_id = ? AND description = 'bad files'",
                        Integer.class,
                        world.releaseId))
                .isZero();

        // The egress (AC3): the org admin streams the exact bytes; a
        // tester persona and a tenant admin (no app:feedback:read)
        // conceal through the application-grain permission check.
        String attachmentId = submitted.get("attachments").get(0).get("id").asText();
        byte[] bytes = download(world.adminToken, world, submitted.get("id").asText(), attachmentId, 200);
        assertThat(new String(bytes)).isEqualTo("png-bytes-fixture");
        download(world.testerToken, world, submitted.get("id").asText(), attachmentId, 404);
        download(world.tenantAdminToken, world, submitted.get("id").asText(), attachmentId, 404);
    }

    // -----------------------------------------------------------------------
    // AC4: the filtered, cursor-paginated listing
    // -----------------------------------------------------------------------

    @Test
    void listFiltersByTypeStatusTenantAndPagesWithCursors() throws Exception {
        World world = releasedWorld("fb-list");
        submit(world, world.testerToken, parts(world, "BUG", "bug one"), 201);
        submit(world, world.testerToken, parts(world, "BUG", "bug two"), 201);
        submit(world, world.testerToken, parts(world, "FEEDBACK", "nice"), 201);

        // The unfiltered release listing.
        JsonNode all = get(world.adminToken, listUri(world, null), 200);
        assertThat(all.get("items").size()).isEqualTo(3);

        // The conjunctive filters.
        JsonNode bugs = get(world.adminToken, listUri(world, "type=BUG"), 200);
        assertThat(bugs.get("items").size()).isEqualTo(2);
        assertThat(bugs.get("items").get(0).get("type").asText()).isEqualTo("BUG");
        JsonNode open = get(world.adminToken, listUri(world, "status=OPEN"), 200);
        assertThat(open.get("items").size()).isEqualTo(3);
        JsonNode byTenant = get(world.adminToken, listUri(world, "tenantId=" + world.tenantA), 200);
        assertThat(byTenant.get("items").size()).isEqualTo(3);
        JsonNode emptyTenant = get(world.adminToken, listUri(world, "tenantId=" + world.tenantB), 200);
        assertThat(emptyTenant.get("items").size()).isZero();

        // Cursor pagination: two pages, the envelope's next_cursor.
        JsonNode pageOne = get(world.adminToken, listUri(world, "limit=2"), 200);
        assertThat(pageOne.get("items").size()).isEqualTo(2);
        assertThat(pageOne.has("next_cursor")).isTrue();
        JsonNode pageTwo = get(
                world.adminToken,
                listUri(world, "limit=2&cursor=" + pageOne.get("next_cursor").asString()),
                200);
        assertThat(pageTwo.get("items").size()).isEqualTo(1);
        assertThat(pageTwo.has("next_cursor")).isFalse();

        // An unknown filter token is a validation refusal, never a concealment.
        JsonNode bogus = get(world.adminToken, listUri(world, "type=PRAISE"), 400);
        assertThat(bogus.get("errors").get("type").asString()).contains("PRAISE");

        // The detail read, and the release-grain concealment of a foreign id.
        String feedbackId = pageOne.get("items").get(0).get("id").asText();
        JsonNode detail = get(
                world.adminToken,
                "/api/v1/releases/" + world.releaseId + "/feedback/" + feedbackId + "?applicationId=" + world.app,
                200);
        assertThat(detail.get("description").asString()).isNotBlank();
        get(
                world.adminToken,
                "/api/v1/releases/" + UUID.randomUUID() + "/feedback/" + feedbackId + "?applicationId=" + world.app,
                404);
    }

    // -----------------------------------------------------------------------
    // AC6: the security-issue restricted visibility
    // -----------------------------------------------------------------------

    @Test
    void securityIssuesAreVisibleToOrgAdminsAndSecurityReviewersAlone() throws Exception {
        World world = releasedWorld("fb-sec");
        JsonNode security = submit(world, world.testerToken, parts(world, "SECURITY_ISSUE", "token in the URL"), 201);
        JsonNode bug = submit(world, world.testerToken, parts(world, "BUG", "plain bug"), 201);
        String securityId = security.get("id").asText();

        // QA holds the general app:feedback:read grant and sees the
        // ordinary reports — the security report is absent from the
        // listing and conceals at the detail (byte-identical to a miss).
        JsonNode qaList = get(world.qaToken, listUri(world, null), 200);
        assertThat(qaList.get("items").size()).isEqualTo(1);
        assertThat(qaList.get("items").get(0).get("id").asText())
                .isEqualTo(bug.get("id").asText());
        assertThat(get(
                                world.qaToken,
                                "/api/v1/releases/" + world.releaseId + "/feedback/" + securityId + "?applicationId="
                                        + world.app,
                                404)
                        .get("code")
                        .asText())
                .isEqualTo("FEEDBACK_NOT_FOUND");

        // The attachment of a security report conceals the same way.
        Map<String, Object> withFile = parts(world, "SECURITY_ISSUE", "poc attached");
        withFile.put("attachments", List.of(filePart("proof.png", "proof-bytes".getBytes())));
        JsonNode secWithAttachment = submit(world, world.testerToken, withFile, 201);
        String attachmentId =
                secWithAttachment.get("attachments").get(0).get("id").asText();
        download(world.qaToken, world, secWithAttachment.get("id").asText(), attachmentId, 404);

        // The security reviewer sees everything (the restricted
        // visibility's second reader).
        JsonNode secList = get(world.securityToken, listUri(world, null), 200);
        assertThat(secList.get("items").size()).isEqualTo(3);
        assertThat(get(
                                world.securityToken,
                                "/api/v1/releases/" + world.releaseId + "/feedback/" + securityId + "?applicationId="
                                        + world.app,
                                200)
                        .get("type")
                        .asText())
                .isEqualTo("SECURITY_ISSUE");

        // The org admin sees everything, and a filtered listing can ask
        // for exactly the security reports.
        JsonNode adminSec = get(world.adminToken, listUri(world, "type=SECURITY_ISSUE"), 200);
        assertThat(adminSec.get("items").size()).isEqualTo(2);

        // A release persona without the feedback grant conceals through
        // the permission check itself.
        assertThat(get(world.managerToken, listUri(world, null), 404)
                        .get("code")
                        .asText())
                .isEqualTo("APPLICATION_NOT_FOUND");
    }

    // -----------------------------------------------------------------------
    // AC5: the audit event and the in-process domain event
    // -----------------------------------------------------------------------

    @Test
    void submissionIsAuditedAndPublishesTheReceivedEvent() throws Exception {
        World world = releasedWorld("fb-event");
        Map<String, Object> withFile = parts(world, "UX_ISSUE", "button too small");
        withFile.put("attachments", List.of(filePart("screen.png", "ui".getBytes())));
        JsonNode submitted = submit(world, world.testerToken, withFile, 201);

        var auditEvents = audit.eventsOfType(AuditEventType.FEEDBACK_RECEIVED);
        assertThat(auditEvents).hasSize(1);
        var event = auditEvents.get(0);
        assertThat(event.orgId()).isEqualTo(world.org);
        assertThat(event.tenantId()).isEqualTo(world.tenantA);
        assertThat(event.applicationId()).isEqualTo(world.app);
        assertThat(event.versionId()).isEqualTo(world.version);
        assertThat(event.success()).isTrue();
        assertThat(event.detail().get("attachments")).isEqualTo("1");
        // PII minimization: the description's content never reaches the audit trail.
        assertThat(event.detail().toString()).doesNotContain("button too small");

        assertThat(received.events).hasSize(1);
        FeedbackReceivedEvent receivedEvent = received.events.get(0);
        assertThat(receivedEvent.feedbackId())
                .isEqualTo(UUID.fromString(submitted.get("id").asText()));
        assertThat(receivedEvent.releaseRef()).isEqualTo(world.releaseRef);
        assertThat(receivedEvent.type()).isEqualTo("UX_ISSUE");
        assertThat(receivedEvent.submitterTenantId()).isEqualTo(world.tenantA);
        assertThat(receivedEvent.attachmentCount()).isEqualTo(1);
    }

    // -----------------------------------------------------------------------
    // Fixtures: the released world and its linked tester
    // -----------------------------------------------------------------------

    private static final class World {
        UUID org;
        String adminToken;
        String ownerToken;
        String managerToken;
        String qaToken;
        String securityToken;
        String tenantAdminToken;
        UUID tenantA;
        UUID tenantB;
        UUID app;
        UUID version;
        UUID iosArtifact;
        UUID androidArtifact;
        UUID releaseId;
        String releaseRef;
        UUID testerId;
        String testerToken;
    }

    /**
     * The released world: an organization with its functional personas,
     * a tenant-owned application with a two-platform version and a
     * DRAFT release targeting two tenants (the second one a release
     * target the tester holds no membership in — the spoof probe), and
     * a linked tester with an ACTIVE membership in the first.
     */
    private World releasedWorld(String slug) throws Exception {
        World world = new World();
        world.org = createOrg("Fb " + slug + " Corp");
        world.adminToken = loginOfOrgRoleIn(world.org, slug + "-admin", "ORG_ADMIN");
        world.ownerToken = loginOfOrgRoleIn(world.org, slug + "-owner", "APP_OWNER");
        world.managerToken = loginOfOrgRoleIn(world.org, slug + "-manager", "RELEASE_MANAGER");
        world.qaToken = loginOfOrgRoleIn(world.org, slug + "-qa", "QA_ENGINEER");
        world.securityToken = loginOfOrgRoleIn(world.org, slug + "-sec", "SECURITY_REVIEWER");
        world.tenantA = createTenant(world.adminToken, world.org, "Fb " + slug + " Bank", "fb-" + slug + "-a");
        world.tenantB = createTenant(world.adminToken, world.org, "Fb " + slug + " Bank B", "fb-" + slug + "-b");
        world.tenantAdminToken = loginOfTenantAdminRoleIn(world.org, world.tenantA, slug + "-tenant-admin");
        world.app = createSharedApp(
                world.adminToken, world.org, "Fb " + slug + " Banking", "com." + slug.replace("-", "") + ".banking");
        world.version = createVersion(world.ownerToken, world.app, "1.0.0");
        world.iosArtifact = insertArtifact(world.version, "IOS", 1, "READY");
        world.androidArtifact = insertArtifact(world.version, "ANDROID", 2, "READY");
        authorize(world.ownerToken, world.app, world.tenantA);
        authorize(world.ownerToken, world.app, world.tenantB);
        Map<String, Object> release = new LinkedHashMap<>();
        release.put("applicationId", world.app.toString());
        release.put("versionId", world.version.toString());
        release.put("ring", "qa");
        release.put(
                "artifacts",
                List.of(
                        Map.of("platform", "IOS", "artifactId", world.iosArtifact.toString()),
                        Map.of("platform", "ANDROID", "artifactId", world.androidArtifact.toString())));
        release.put(
                "targets",
                List.of(
                        Map.of("tenantId", world.tenantA.toString(), "testerGroupIds", List.of()),
                        Map.of("tenantId", world.tenantB.toString(), "testerGroupIds", List.of())));
        JsonNode created = post(world.managerToken, "/api/v1/releases", release, 201);
        world.releaseId = UUID.fromString(created.get("id").asText());
        world.releaseRef = created.get("releaseRef").asText();

        // The linked tester: invited, registered, linked to tenantA.
        String testerEmail = slug + "-tester@example.com";
        JsonNode invited = post(
                world.adminToken,
                "/api/v1/testers",
                Map.of(
                        "organizationId",
                        world.org.toString(),
                        "email",
                        testerEmail,
                        "fullName",
                        "Fb " + slug + " Tester"),
                201);
        client.post()
                .uri("/api/v1/auth/register")
                .body(Map.of(
                        "invitationToken",
                        invited.get("invitation").get("invitationToken").asString(),
                        "email",
                        testerEmail,
                        "password",
                        PASSWORD,
                        "fullName",
                        "Fb " + slug + " Tester"))
                .retrieve()
                .body(String.class);
        post(
                world.adminToken,
                "/api/v1/tenants/" + world.tenantA + "/testers",
                Map.of("testerId", invited.get("id").asText()),
                201);
        world.testerId = UUID.fromString(invited.get("id").asText());
        world.testerToken = accessToken(login(testerEmail, PASSWORD));
        return world;
    }

    /** Records every published feedback receipt (S12-01's feed, observed in-process). */
    static class FeedbackEventRecorder {
        final List<FeedbackReceivedEvent> events = new CopyOnWriteArrayList<>();

        @EventListener
        void on(FeedbackReceivedEvent event) {
            events.add(event);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FeedbackFixtures {
        @Bean
        FeedbackEventRecorder feedbackEventRecorder() {
            return new FeedbackEventRecorder();
        }
    }

    // -----------------------------------------------------------------------
    // HTTP helpers (the release suite's shapes)
    // -----------------------------------------------------------------------

    /** The submission parts of the world's tester (the release link the portal screen addresses). */
    private Map<String, Object> parts(World world, String type, String description) {
        Map<String, Object> parts = new LinkedHashMap<>();
        parts.put("application_id", world.app.toString());
        parts.put("version_id", world.version.toString());
        parts.put("tenant_id", world.tenantA.toString());
        parts.put("type", type);
        parts.put("description", description);
        return parts;
    }

    private Map<String, Object> parts(World world, String type, String description, UUID tenantId) {
        Map<String, Object> parts = parts(world, type, description);
        parts.put("tenant_id", tenantId.toString());
        return parts;
    }

    private JsonNode submit(World world, String bearer, Map<String, Object> fields, int expectedStatus) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        fields.forEach((key, value) -> {
            if (value instanceof Collection<?> collection) {
                collection.forEach(item -> body.add(key, item));
            } else {
                body.add(key, value);
            }
        });
        return exchangeMultipart(bearer, "/api/v1/feedback", body, expectedStatus);
    }

    /** One file part carrying its own name (the artifact suite's fixture shape). */
    private static ByteArrayResource filePart(String fileName, byte[] content) {
        return new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return fileName;
            }
        };
    }

    private JsonNode exchangeMultipart(
            String bearer, String uri, MultiValueMap<String, Object> parts, int expectedStatus) {
        Exchange recorded = client.post()
                .uri(uri)
                .headers(headers -> headers.setBearerAuth(bearer))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(parts)
                .exchange((request, response) -> {
                    byte[] bytes = response.getBody().readAllBytes();
                    return new Exchange(
                            response.getStatusCode().value(),
                            bytes.length == 0 ? mapper.createObjectNode() : mapper.readTree(bytes),
                            bytes);
                });
        assertThat(recorded.status())
                .as("POST %s -> %d, body=%s", uri, expectedStatus, recorded.body())
                .isEqualTo(expectedStatus);
        return recorded.body();
    }

    private byte[] download(String bearer, World world, String feedbackId, String attachmentId, int expectedStatus) {
        Exchange recorded = client.get()
                .uri("/api/v1/releases/" + world.releaseId + "/feedback/" + feedbackId + "/attachments/" + attachmentId
                        + "?applicationId=" + world.app)
                .headers(headers -> headers.setBearerAuth(bearer))
                .exchange((request, response) -> {
                    byte[] bytes = response.getBody().readAllBytes();
                    return new Exchange(
                            response.getStatusCode().value(),
                            isJson(response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE))
                                    ? mapper.readTree(bytes)
                                    : mapper.createObjectNode(),
                            bytes);
                });
        assertThat(recorded.status()).as("GET attachment -> %d", expectedStatus).isEqualTo(expectedStatus);
        return recorded.raw();
    }

    /** Problem envelopes parse; streamed bytes (the attachment egress) do not. */
    private static boolean isJson(String contentType) {
        return contentType != null && contentType.contains("json");
    }

    private String listUri(World world, String query) {
        return "/api/v1/releases/" + world.releaseId + "/feedback?applicationId=" + world.app
                + (query == null ? "" : "&" + query);
    }

    private JsonNode get(String bearer, String uri, int expectedStatus) {
        Exchange recorded = client.get()
                .uri(uri)
                .headers(headers -> headers.setBearerAuth(bearer))
                .exchange((request, response) -> {
                    byte[] bytes = response.getBody().readAllBytes();
                    return new Exchange(
                            response.getStatusCode().value(),
                            bytes.length == 0 ? mapper.createObjectNode() : mapper.readTree(bytes),
                            bytes);
                });
        assertThat(recorded.status()).as("GET %s -> %d", uri, expectedStatus).isEqualTo(expectedStatus);
        return recorded.body();
    }

    private JsonNode post(String bearer, String uri, Map<String, Object> body, int expectedStatus) {
        Exchange recorded = client.post()
                .uri(uri)
                .headers(headers -> headers.setBearerAuth(bearer))
                .body(body)
                .exchange((request, response) -> {
                    byte[] bytes = response.getBody().readAllBytes();
                    return new Exchange(
                            response.getStatusCode().value(),
                            bytes.length == 0 ? mapper.createObjectNode() : mapper.readTree(bytes),
                            bytes);
                });
        assertThat(recorded.status())
                .as("POST %s -> %d, body=%s", uri, expectedStatus, recorded.body())
                .isEqualTo(expectedStatus);
        return recorded.body();
    }

    /** One exchanged response: status, decoded body (problem envelopes included), raw bytes. */
    private record Exchange(int status, JsonNode body, byte[] raw) {}

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

    /** An ORGANIZATION-shared application (the two-target release's shape): no tenant holds it until authorized. */
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

    /** A tenant-owned application (the owning tenant holds it by ownership; releases target it alone). */
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
                        "Fb " + role))
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
                        "Fb Tenant Admin"))
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

    private JsonNode decode(String token) {
        String claims = token.split("\\.")[1];
        return mapper.readTree(Base64.getUrlDecoder().decode(claims));
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
