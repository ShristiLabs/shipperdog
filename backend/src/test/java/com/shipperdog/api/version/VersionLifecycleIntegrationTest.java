package com.shipperdog.api.version;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.identity.AuditCapture;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
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
 * End-to-end acceptance flow for the version lifecycle (issue #19,
 * S4-03, FR-008, INV-004/INV-006): archiving as the read-only soft
 * terminal that leaves the default listing (AC2), revoke-distribution
 * as the strongest terminal that deletes nothing and carries its
 * mandatory reason into the audit trail and the status timeline (AC3),
 * INV-004's artifact-set freeze through the distribution-guard seam
 * (AC1 — a synthetic contributor plays the E7 download-grant role the
 * story says will register there), INV-006's kernel guarantee that
 * old, archived, and revoked versions stay fully available after newer
 * versions exist (AC4; the tester-facing download leg lands with E7's
 * grants and E5's artifacts — what is testable now is exactly what
 * those stories will consume: nothing deleted, reads intact, only
 * mutation blocked), and the chronological history read (AC5).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
            "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef"
        })
@Testcontainers
@Import(AuditCapture.class)
class VersionLifecycleIntegrationTest {

    private static final String DEV_ADMIN_EMAIL = "dev@shipperdog.local";
    private static final String DEV_ADMIN_PASSWORD = "shipperdog-dev-admin";
    private static final String PASSWORD = "a-perfectly-valid-password";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));

    /**
     * The synthetic distribution records of this suite: version ids the
     * test drops into the {@link VersionDistributionContributor} seam
     * stand in for the E7 download grants / M2 releases that will
     * register real ones — the story's AC1 trigger ("any distribution
     * record") exercised through exactly the path the guard will use
     * in production, never a direct table check.
     */
    static final CopyOnWriteArraySet<UUID> DISTRIBUTED = new CopyOnWriteArraySet<>();

    @TestConfiguration(proxyBeanMethods = false)
    static class SyntheticDistributionRecords {

        @org.springframework.context.annotation.Bean
        VersionDistributionContributor syntheticDistributionRecords() {
            return versionId -> DISTRIBUTED.contains(versionId);
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AuditCapture audit;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private VersionDistributionGuard distributionGuard;

    @Autowired
    private com.shipperdog.api.tenant.TenantScopeExecutor scopeExecutor;

    private RestClient client;

    @BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
        audit.reset();
        DISTRIBUTED.clear();
    }

    // -----------------------------------------------------------------------
    // AC2: archive — read-only, out of the default listing, back with
    // include=archived
    // -----------------------------------------------------------------------

    @Test
    void archivingRetiresTheVersionOutOfTheDefaultListingAndFreezesIt() throws Exception {
        UUID orgId = createOrg("Vl Archive Corp");
        String adminToken = loginOfOrgRoleIn(orgId, "vl-arch-admin@example.com", "ORG_ADMIN");
        String ownerToken = loginOfOrgRoleIn(orgId, "vl-arch-owner@example.com", "APP_OWNER");
        UUID tenantId = createTenant(adminToken, orgId, "Vl Archive Bank", "crm-vl-arch-1");
        UUID appId = createTenantApp(ownerToken, tenantId, "Archivable", "com.vlarch.app");
        UUID oldVersion = createVersion(ownerToken, appId, "1.0.0");
        createVersion(ownerToken, appId, "2.0.0");

        put(ownerToken, tagsUri(appId, oldVersion), Map.of("tags", List.of("PILOT")), 200);

        JsonNode archived = post(ownerToken, archiveUri(appId, oldVersion), Map.of(), 200);
        assertThat(archived.get("status").asText()).isEqualTo("ARCHIVED");
        assertThat(archived.get("versionName").asText()).isEqualTo("1.0.0");
        assertThat(archived.get("tags").toString()).contains("PILOT");

        // The default listing is the working set; the archived version
        // returns only with its flag (AC2), newest first either way.
        assertThat(get(ownerToken, versionsUri(appId), 200).get("items").size()).isEqualTo(1);
        JsonNode withArchived = get(ownerToken, versionsUri(appId) + "?include=archived", 200);
        assertThat(withArchived.get("items").size()).isEqualTo(2);
        assertThat(withArchived.get("items").get(0).get("versionName").asText()).isEqualTo("2.0.0");
        assertThat(withArchived.get("items").get(1).get("status").asText()).isEqualTo("ARCHIVED");

        // Read-only: the detail and the history stay fully readable
        // forever, and the tag surface — M1's only mutable metadata —
        // answers the precise conflict.
        assertThat(get(ownerToken, versionUri(appId, oldVersion), 200)
                        .get("status")
                        .asText())
                .isEqualTo("ARCHIVED");
        Recording frozen = exchange(tagsUri(appId, oldVersion), ownerToken, "PUT", Map.of("tags", List.of("QA")));
        assertThat(frozen.status).isEqualTo(409);
        assertThat(frozen.body.get("code").asText()).isEqualTo("VERSION_ARCHIVED");

        // One real transition, one audit event with before/after, one
        // timeline row beyond creation's DRAFT (AC5's spine).
        List<AuditEvent> events = audit.eventsOfType(AuditEventType.VERSION_ARCHIVED);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).applicationId()).isEqualTo(appId);
        assertThat(events.get(0).tenantId()).isEqualTo(tenantId);
        assertThat(events.get(0).detail()).containsEntry("fromStatus", "DRAFT");
        assertThat(events.get(0).detail()).containsEntry("toStatus", "ARCHIVED");
        assertThat(get(ownerToken, historyUri(appId, oldVersion), 200)
                        .get("statusHistory")
                        .size())
                .isEqualTo(2);
    }

    // -----------------------------------------------------------------------
    // AC3: revoke distribution — blocks, deletes nothing, reason required
    // -----------------------------------------------------------------------

    @Test
    void revokeDistributionDeletesNothingAndCarriesItsReasonIntoHistory() throws Exception {
        UUID orgId = createOrg("Vl Revoke Corp");
        String adminToken = loginOfOrgRoleIn(orgId, "vl-rev-admin@example.com", "ORG_ADMIN");
        String ownerToken = loginOfOrgRoleIn(orgId, "vl-rev-owner@example.com", "APP_OWNER");
        UUID tenantId = createTenant(adminToken, orgId, "Vl Revoke Bank", "crm-vl-rev-1");
        UUID appId = createTenantApp(ownerToken, tenantId, "Revocable", "com.vlrev.app");
        UUID versionId = createVersion(ownerToken, appId, "1.4.2");
        put(ownerToken, tagsUri(appId, versionId), Map.of("tags", List.of("QA", "PILOT")), 200);

        String reason = "Legal takedown notice 2026-10-06; binary must not reach new devices.";
        JsonNode revoked = post(ownerToken, revokeUri(appId, versionId), Map.of("reason", reason), 200);
        assertThat(revoked.get("status").asText()).isEqualTo("REVOKED");
        assertThat(revoked.get("versionName").asText()).isEqualTo("1.4.2");

        // The reason is mandatory (AC3): absent, blank, and oversized
        // reasons are broken requests, not unlucky ones. Whitespace is
        // trimmed, so a padded-but-real reason passes.
        post(ownerToken, revokeUri(appId, createVersion(ownerToken, appId, "1.4.3")), Map.of(), 400);
        post(ownerToken, revokeUri(appId, createVersion(ownerToken, appId, "1.4.4")), Map.of("reason", "   "), 400);
        post(
                ownerToken,
                revokeUri(appId, createVersion(ownerToken, appId, "1.4.5")),
                Map.of("reason", "x".repeat(1001)),
                400);

        // Deletes nothing (INV-006/INV-007): the row, its tags, and its
        // timeline all remain; reads stay fully authorized.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM versions WHERE id = ?", Integer.class, versionId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM version_tags WHERE version_id = ?", Integer.class, versionId))
                .isEqualTo(2);
        JsonNode detail = get(ownerToken, versionUri(appId, versionId), 200);
        assertThat(detail.get("status").asText()).isEqualTo("REVOKED");
        assertThat(detail.get("tags").size()).isEqualTo(2);

        // Out of the default surface (E7's eligibility view), back with
        // its flag — history preserved, new distribution blocked.
        assertThat(get(ownerToken, versionsUri(appId), 200).get("items").size()).isEqualTo(3);
        assertThat(get(ownerToken, versionsUri(appId) + "?include=revoked", 200)
                        .get("items")
                        .size())
                .isEqualTo(4);

        // The audit event carries the reason (FR-024) and before/after;
        // the timeline row carries it too, so the takedown explains
        // itself (AC5).
        List<AuditEvent> events = audit.eventsOfType(AuditEventType.VERSION_DISTRIBUTION_REVOKED);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).applicationId()).isEqualTo(appId);
        assertThat(events.get(0).tenantId()).isEqualTo(tenantId);
        assertThat(events.get(0).detail()).containsEntry("reason", reason);
        assertThat(events.get(0).detail()).containsEntry("fromStatus", "DRAFT");
        assertThat(events.get(0).detail()).containsEntry("toStatus", "REVOKED");
        JsonNode timeline = get(ownerToken, historyUri(appId, versionId), 200).get("statusHistory");
        assertThat(timeline.size()).isEqualTo(2);
        assertThat(timeline.get(1).get("status").asText()).isEqualTo("REVOKED");
        assertThat(timeline.get(1).get("reason").asText()).isEqualTo(reason);
        assertThat(timeline.get(0).get("reason").isNull()).isTrue();
    }

    // -----------------------------------------------------------------------
    // Terminals are final: precise conflicts, no exits
    // -----------------------------------------------------------------------

    @Test
    void terminalStatesAreFinalAndAnswerPreciseConflicts() throws Exception {
        UUID orgId = createOrg("Vl Final Corp");
        String adminToken = loginOfOrgRoleIn(orgId, "vl-fin-admin@example.com", "ORG_ADMIN");
        String ownerToken = loginOfOrgRoleIn(orgId, "vl-fin-owner@example.com", "APP_OWNER");
        UUID tenantId = createTenant(adminToken, orgId, "Vl Final Bank", "crm-vl-fin-1");
        UUID appId = createTenantApp(ownerToken, tenantId, "Final", "com.vlfin.app");

        UUID archived = createVersion(ownerToken, appId, "1.0.0");
        post(ownerToken, archiveUri(appId, archived), Map.of(), 200);
        Recording reArchive = exchange(archiveUri(appId, archived), ownerToken, "POST", Map.of());
        assertThat(reArchive.status).isEqualTo(409);
        assertThat(reArchive.body.get("code").asText()).isEqualTo("VERSION_ARCHIVED");

        UUID revoked = createVersion(ownerToken, appId, "2.0.0");
        post(ownerToken, revokeUri(appId, revoked), Map.of("reason", "security fix superseded"), 200);
        Recording reRevoke = exchange(revokeUri(appId, revoked), ownerToken, "POST", Map.of("reason", "again"));
        assertThat(reRevoke.status).isEqualTo(409);
        assertThat(reRevoke.body.get("code").asText()).isEqualTo("VERSION_REVOKED");
        Recording archiveRevoked = exchange(archiveUri(appId, revoked), ownerToken, "POST", Map.of());
        assertThat(archiveRevoked.status).isEqualTo(409);
        assertThat(archiveRevoked.body.get("code").asText()).isEqualTo("VERSION_REVOKED");
        Recording tagsOnRevoked = exchange(tagsUri(appId, revoked), ownerToken, "PUT", Map.of("tags", List.of("QA")));
        assertThat(tagsOnRevoked.status).isEqualTo(409);
        assertThat(tagsOnRevoked.body.get("code").asText()).isEqualTo("VERSION_REVOKED");

        // A compliance takedown of an already-retired version is the
        // one sanctioned crossing: ARCHIVED -> REVOKED.
        JsonNode crossed =
                post(ownerToken, revokeUri(appId, archived), Map.of("reason", "takedown after retirement"), 200);
        assertThat(crossed.get("status").asText()).isEqualTo("REVOKED");
        JsonNode timeline = get(ownerToken, historyUri(appId, archived), 200).get("statusHistory");
        assertThat(timeline.size()).isEqualTo(3);
        assertThat(timeline.get(0).get("status").asText()).isEqualTo("DRAFT");
        assertThat(timeline.get(1).get("status").asText()).isEqualTo("ARCHIVED");
        assertThat(timeline.get(2).get("status").asText()).isEqualTo("REVOKED");
    }

    // -----------------------------------------------------------------------
    // AC5: the chronological history read
    // -----------------------------------------------------------------------

    @Test
    void historyReturnsTheChronologicalStatusTimelineForAudit() throws Exception {
        UUID orgId = createOrg("Vl Hist Corp");
        String adminToken = loginOfOrgRoleIn(orgId, "vl-hist-admin@example.com", "ORG_ADMIN");
        String ownerToken = loginOfOrgRoleIn(orgId, "vl-hist-owner@example.com", "APP_OWNER");
        UUID tenantId = createTenant(adminToken, orgId, "Vl Hist Bank", "crm-vl-hist-1");
        UUID appId = createTenantApp(ownerToken, tenantId, "Historied", "com.vlhist.app");
        UUID versionId = createVersion(ownerToken, appId, "7.0.0");
        createVersion(ownerToken, appId, "7.1.0");
        post(ownerToken, archiveUri(appId, versionId), Map.of(), 200);

        JsonNode history = get(ownerToken, historyUri(appId, versionId), 200);
        assertThat(history.get("id").asText()).isEqualTo(versionId.toString());
        assertThat(history.get("versionName").asText()).isEqualTo("7.0.0");
        assertThat(history.get("status").asText()).isEqualTo("ARCHIVED");
        JsonNode timeline = history.get("statusHistory");
        assertThat(timeline.size()).isEqualTo(2);
        assertThat(timeline.get(0).get("status").asText()).isEqualTo("DRAFT");
        assertThat(timeline.get(1).get("status").asText()).isEqualTo("ARCHIVED");
        assertThat(timeline.get(0).get("occurredAt").asText()).isNotBlank();
        assertThat(timeline.get(0)
                        .get("occurredAt")
                        .asText()
                        .compareTo(timeline.get(1).get("occurredAt").asText()))
                .as("the timeline is chronological")
                .isLessThanOrEqualTo(0);
        // The artifact section exists and is the E5 surface (AC5's
        // chronological artifacts): empty in the M1 kernel.
        assertThat(history.get("artifacts").isArray()).isTrue();
        assertThat(history.get("artifacts").isEmpty()).isTrue();
        // Tags are mutable metadata (S4-02), not history: the history
        // view deliberately carries no tag snapshot.
        assertThat(history.has("tags")).isFalse();

        // A DRAFT version that never transitioned still has its birth
        // row — the timeline begins at creation for every version.
        JsonNode young = get(
                ownerToken,
                historyUri(
                        appId,
                        UUID.fromString(get(ownerToken, versionsUri(appId), 200)
                                .get("items")
                                .get(0)
                                .get("id")
                                .asText())),
                200);
        assertThat(young.get("statusHistory").size()).isEqualTo(1);
        assertThat(young.get("statusHistory").get(0).get("status").asText()).isEqualTo("DRAFT");
    }

    // -----------------------------------------------------------------------
    // AC4 + AC1: INV-006 availability and INV-004's freeze, through the
    // distribution-guard seam
    // -----------------------------------------------------------------------

    @Test
    void oldVersionsStayFullyAvailableAfterNewerOnesExistAndThroughTerminalStates() throws Exception {
        UUID orgId = createOrg("Vl Avail Corp");
        String adminToken = loginOfOrgRoleIn(orgId, "vl-av-admin@example.com", "ORG_ADMIN");
        String ownerToken = loginOfOrgRoleIn(orgId, "vl-av-owner@example.com", "APP_OWNER");
        UUID tenantId = createTenant(adminToken, orgId, "Vl Avail Bank", "crm-vl-av-1");
        UUID appId = createTenantApp(ownerToken, tenantId, "Available", "com.vlav.app");

        UUID old = createVersion(ownerToken, appId, "1.0.0");
        put(ownerToken, tagsUri(appId, old), Map.of("tags", List.of("STABLE")), 200);
        UUID middle = createVersion(ownerToken, appId, "2.0.0");
        createVersion(ownerToken, appId, "3.0.0");

        // An M1 download grant on the old version, played through the
        // contributor seam exactly as E7 will register it: from this
        // moment the old version is distributed history.
        DISTRIBUTED.add(old);
        post(ownerToken, archiveUri(appId, middle), Map.of(), 200);

        // INV-006: the old version — distributed, older than two newer
        // releases — is still fully readable, tags and slots and
        // timeline intact, and so are the archived middle and the young
        // head. Nothing anywhere was deleted.
        for (UUID versionId : List.of(old, middle)) {
            JsonNode detail = get(ownerToken, versionUri(appId, versionId), 200);
            assertThat(detail.get("artifactsByPlatform").toString()).contains("IOS", "ANDROID");
            assertThat(get(ownerToken, historyUri(appId, versionId), 200)
                            .get("statusHistory")
                            .size())
                    .isGreaterThanOrEqualTo(1);
        }
        assertThat(get(ownerToken, versionUri(appId, old), 200).get("tags").toString())
                .contains("STABLE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM versions WHERE application_id = ?", Integer.class, appId))
                .isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM version_tags WHERE version_id = ?", Integer.class, old))
                .isEqualTo(1);

        // INV-004 at the same seam: the distributed old version's
        // artifact set is frozen (AC1), while the undistributed young
        // head stays mutable — availability and immutability are two
        // different axes, which is the whole point of the pair. The
        // guard is driven through the sanctioned scope executor the
        // way E5's artifact routes will have their scope bound by the
        // interceptor.
        UUID ownerUserId = userIdByEmail("vl-av-owner@example.com");
        UUID head = UUID.fromString(get(ownerToken, versionsUri(appId), 200)
                .get("items")
                .get(0)
                .get("id")
                .asText());
        assertThatThrownBy(() -> guardInScope(tenantId, ownerUserId, appId, old))
                .isInstanceOf(VersionException.class)
                .hasMessageContaining("distribution records");
        assertThatCode(() -> guardInScope(tenantId, ownerUserId, appId, head)).doesNotThrowAnyException();
        // The terminal statuses freeze the set through the same guard
        // even with no distribution record at all.
        assertThatThrownBy(() -> guardInScope(tenantId, ownerUserId, appId, middle))
                .isInstanceOf(VersionException.class)
                .hasMessageContaining("archived");
    }

    @Test
    void theGuardFreezesThroughTheContributorSeamAndConcealsUnknownVersions() throws Exception {
        UUID orgId = createOrg("Vl Guard Corp");
        String adminToken = loginOfOrgRoleIn(orgId, "vl-gu-admin@example.com", "ORG_ADMIN");
        String ownerToken = loginOfOrgRoleIn(orgId, "vl-gu-owner@example.com", "APP_OWNER");
        UUID tenantId = createTenant(adminToken, orgId, "Vl Guard Bank", "crm-vl-gu-1");
        UUID appId = createTenantApp(ownerToken, tenantId, "Guarded", "com.vlgu.app");
        UUID appIdElsewhere = createTenantApp(ownerToken, tenantId, "Guarded Elsewhere", "com.vlgu.other");

        UUID versionId = createVersion(ownerToken, appId, "1.0.0");
        UUID ownerUserId = userIdByEmail("vl-gu-owner@example.com");
        assertThatCode(() -> guardInScope(tenantId, ownerUserId, appId, versionId))
                .as("a DRAFT version with no distribution record is mutable")
                .doesNotThrowAnyException();

        // The first distribution record is the freeze trigger (AC1) —
        // and the check went through the contributor seam, not a table.
        DISTRIBUTED.add(versionId);
        assertThatThrownBy(() -> guardInScope(tenantId, ownerUserId, appId, versionId))
                .isInstanceOf(VersionException.class)
                .hasMessageContaining("frozen");
        DISTRIBUTED.clear();

        // The guard resolves scoped and conceal-denies exactly like the
        // HTTP surface: unknown ids and foreign applications are the
        // standard miss, indistinguishable.
        assertThatThrownBy(() -> guardInScope(tenantId, ownerUserId, appId, UUID.randomUUID()))
                .isInstanceOf(VersionException.class)
                .hasMessageContaining("version not found");
        assertThatThrownBy(() -> guardInScope(tenantId, ownerUserId, appIdElsewhere, versionId))
                .isInstanceOf(VersionException.class)
                .hasMessageContaining("version not found");
    }

    // -----------------------------------------------------------------------
    // include flags compose with the tag filter and reject typos
    // -----------------------------------------------------------------------

    @Test
    void includeFlagsComposeWithTagFiltersAndRejectUnknownValues() throws Exception {
        UUID orgId = createOrg("Vl Inc Corp");
        String adminToken = loginOfOrgRoleIn(orgId, "vl-inc-admin@example.com", "ORG_ADMIN");
        String ownerToken = loginOfOrgRoleIn(orgId, "vl-inc-owner@example.com", "APP_OWNER");
        UUID tenantId = createTenant(adminToken, orgId, "Vl Inc Bank", "crm-vl-inc-1");
        UUID appId = createTenantApp(ownerToken, tenantId, "Includable", "com.vlinc.app");

        UUID live = createVersion(ownerToken, appId, "1.0.0");
        put(ownerToken, tagsUri(appId, live), Map.of("tags", List.of("QA")), 200);
        UUID archived = createVersion(ownerToken, appId, "2.0.0");
        put(ownerToken, tagsUri(appId, archived), Map.of("tags", List.of("QA")), 200);
        post(ownerToken, archiveUri(appId, archived), Map.of(), 200);
        UUID revoked = createVersion(ownerToken, appId, "3.0.0");
        post(ownerToken, revokeUri(appId, revoked), Map.of("reason", "bad build"), 200);

        assertThat(get(ownerToken, versionsUri(appId), 200).get("items").size()).isEqualTo(1);
        assertThat(get(ownerToken, versionsUri(appId) + "?include=archived", 200)
                        .get("items")
                        .size())
                .isEqualTo(2);
        assertThat(get(ownerToken, versionsUri(appId) + "?include=revoked", 200)
                        .get("items")
                        .size())
                .isEqualTo(2);
        assertThat(get(ownerToken, versionsUri(appId) + "?include=archived,revoked", 200)
                        .get("items")
                        .size())
                .isEqualTo(3);
        assertThat(get(ownerToken, versionsUri(appId) + "?include= Archived ", 200)
                        .get("items")
                        .size())
                .as("flags are trimmed and case-insensitive")
                .isEqualTo(2);
        assertThat(get(ownerToken, versionsUri(appId) + "?tag=QA&include=archived", 200)
                        .get("items")
                        .size())
                .as("the include window composes with the tag filter")
                .isEqualTo(2);

        get(ownerToken, versionsUri(appId) + "?include=archvied", 400);
        get(ownerToken, versionsUri(appId) + "?include=archived,deleted", 400);
    }

    // -----------------------------------------------------------------------
    // Concealment and permissions on the lifecycle routes
    // -----------------------------------------------------------------------

    @Test
    void lifecycleRoutesResolveThroughPermissionsAndConcealOtherwise() throws Exception {
        UUID orgId = createOrg("Vl Perm Corp");
        String adminToken = loginOfOrgRoleIn(orgId, "vl-perm-admin@example.com", "ORG_ADMIN");
        String ownerToken = loginOfOrgRoleIn(orgId, "vl-perm-owner@example.com", "APP_OWNER");
        UUID tenantId = createTenant(adminToken, orgId, "Vl Perm Bank", "crm-vl-perm-1");
        UUID appId = createTenantApp(ownerToken, tenantId, "Permed", "com.vlperm.app");
        UUID versionId = createVersion(ownerToken, appId, "1.0.0");

        // Read-holding roles read the history (app:versions:read).
        String viewerToken = loginOfOrgRoleIn(orgId, "vl-perm-viewer@example.com", "VIEWER");
        assertThat(get(viewerToken, historyUri(appId, versionId), 200)
                        .get("versionName")
                        .asText())
                .isEqualTo("1.0.0");

        // Write-denied roles get the concealed application miss — the
        // same indistinguishable 404 as an unknown application (S3-03
        // doctrine; DEVELOPER holds read but not write).
        String developerToken = loginOfOrgRoleIn(orgId, "vl-perm-dev@example.com", "DEVELOPER");
        assertThat(exchange(archiveUri(appId, versionId), developerToken, "POST", Map.of()).status)
                .isEqualTo(404);
        assertThat(exchange(revokeUri(appId, versionId), developerToken, "POST", Map.of("reason", "x")).status)
                .isEqualTo(404);

        // Unknown versions and foreign applications conceal identically
        // on every lifecycle route.
        for (String uri : List.of(
                archiveUri(appId, UUID.randomUUID()),
                revokeUri(appId, UUID.randomUUID()),
                historyUri(appId, UUID.randomUUID()))) {
            Recording miss = exchange(uri, ownerToken, uri.endsWith("history") ? "GET" : "POST", Map.of("reason", "x"));
            assertThat(miss.status).isEqualTo(404);
            assertThat(miss.body.get("code").asText()).isEqualTo("VERSION_NOT_FOUND");
        }
        UUID otherApp = createTenantApp(ownerToken, tenantId, "Permed Neighbor", "com.vlperm.next");
        for (String uri : List.of(
                archiveUri(otherApp, versionId), revokeUri(otherApp, versionId), historyUri(otherApp, versionId))) {
            Recording foreign =
                    exchange(uri, ownerToken, uri.endsWith("history") ? "GET" : "POST", Map.of("reason", "x"));
            assertThat(foreign.status).isEqualTo(404);
            assertThat(foreign.body.get("code").asText()).isEqualTo("VERSION_NOT_FOUND");
        }

        // Unauthenticated requests never reach the surface.
        assertThat(get(archiveUri(appId, UUID.randomUUID()), null).status).isEqualTo(401);
    }

    // -----------------------------------------------------------------------
    // Concurrency: the transition compare-and-set
    // -----------------------------------------------------------------------

    @Test
    void concurrentArchivesProduceExactlyOneTransition() throws Exception {
        UUID orgId = createOrg("Vl Race Corp");
        String adminToken = loginOfOrgRoleIn(orgId, "vl-race-admin@example.com", "ORG_ADMIN");
        String ownerToken = loginOfOrgRoleIn(orgId, "vl-race-owner@example.com", "APP_OWNER");
        UUID tenantId = createTenant(adminToken, orgId, "Vl Race Bank", "crm-vl-race-1");
        UUID appId = createTenantApp(ownerToken, tenantId, "Raced", "com.vlrace.app");
        UUID versionId = createVersion(ownerToken, appId, "1.0.0");

        int threads = 8;
        CyclicBarrier start = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return exchange(archiveUri(appId, versionId), ownerToken, "POST", Map.of()).status;
                }));
            }
            int ok = 0;
            int conflicts = 0;
            for (Future<Integer> future : futures) {
                int status = future.get(30, TimeUnit.SECONDS);
                if (status == 200) {
                    ok++;
                } else if (status == 409) {
                    conflicts++;
                } else {
                    throw new AssertionError("unexpected status " + status + " from a racing archive");
                }
            }
            assertThat(ok).as("exactly one racing archive wins the transition").isEqualTo(1);
            assertThat(conflicts).isEqualTo(threads - 1);
        } finally {
            pool.shutdownNow();
        }

        // The durable record is exact: creation's DRAFT row plus ONE
        // ARCHIVED row — the database compare-and-set, not luck.
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM version_status_history WHERE version_id = ?", Integer.class, versionId))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT status FROM versions WHERE id = ?", String.class, versionId))
                .isEqualTo("ARCHIVED");
    }

    @Test
    void aRacingArchiveCanNeverUnRevokeACommittedRevoke() throws Exception {
        UUID orgId = createOrg("Vl Cross Corp");
        String adminToken = loginOfOrgRoleIn(orgId, "vl-cross-admin@example.com", "ORG_ADMIN");
        String ownerToken = loginOfOrgRoleIn(orgId, "vl-cross-owner@example.com", "APP_OWNER");
        UUID tenantId = createTenant(adminToken, orgId, "Vl Cross Bank", "crm-vl-cross-1");
        UUID appId = createTenantApp(ownerToken, tenantId, "Crossed", "com.vlcross.app");
        UUID versionId = createVersion(ownerToken, appId, "1.0.0");

        // Archive and revoke released together on one version: without
        // the compare-and-set, a stale archive reading DRAFT could
        // commit after the revoke and silently un-revoke the pulled
        // version. With it, every interleaving ends REVOKED — the
        // revoke either wins outright or legally crosses ARCHIVED.
        CyclicBarrier start = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> archive = pool.submit(() -> {
                start.await();
                return exchange(archiveUri(appId, versionId), ownerToken, "POST", Map.of()).status;
            });
            Future<Integer> revoke = pool.submit(() -> {
                start.await();
                return exchange(revokeUri(appId, versionId), ownerToken, "POST", Map.of("reason", "racing takedown"))
                        .status;
            });
            int archiveStatus = archive.get(30, TimeUnit.SECONDS);
            int revokeStatus = revoke.get(30, TimeUnit.SECONDS);
            assertThat(revokeStatus)
                    .as("the revoke always succeeds, outright or across ARCHIVED")
                    .isEqualTo(200);
            assertThat(archiveStatus)
                    .as("the archive either wins first or conflicts precisely")
                    .isIn(200, 409);
        } finally {
            pool.shutdownNow();
        }

        assertThat(jdbc.queryForObject("SELECT status FROM versions WHERE id = ?", String.class, versionId))
                .as("no interleaving can leave the version un-revoked")
                .isEqualTo("REVOKED");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM version_status_history WHERE version_id = ? AND status = 'REVOKED'",
                        Integer.class,
                        versionId))
                .isEqualTo(1);
    }

    // -----------------------------------------------------------------------
    // ACTIVE is a legal transition source (the M2 release activation
    // seeds it; the state machine must already accept it)
    // -----------------------------------------------------------------------

    @Test
    void activeVersionsArchiveAndRevokeAsLegalSources() throws Exception {
        UUID orgId = createOrg("Vl Active Corp");
        String adminToken = loginOfOrgRoleIn(orgId, "vl-act-admin@example.com", "ORG_ADMIN");
        String ownerToken = loginOfOrgRoleIn(orgId, "vl-act-owner@example.com", "APP_OWNER");
        UUID tenantId = createTenant(adminToken, orgId, "Vl Active Bank", "crm-vl-act-1");
        UUID appId = createTenantApp(ownerToken, tenantId, "Activated", "com.vlact.app");

        UUID toArchive = createVersion(ownerToken, appId, "1.0.0");
        UUID toRevoke = createVersion(ownerToken, appId, "2.0.0");
        // The M2 release activation does not exist yet; seeding the
        // reserved state directly pins that both transitions accept it
        // as a source the day it lands.
        jdbc.update("UPDATE versions SET status = 'ACTIVE' WHERE id IN (?, ?)", toArchive, toRevoke);

        assertThat(post(ownerToken, archiveUri(appId, toArchive), Map.of(), 200)
                        .get("status")
                        .asText())
                .isEqualTo("ARCHIVED");
        assertThat(post(ownerToken, revokeUri(appId, toRevoke), Map.of("reason", "active build pulled"), 200)
                        .get("status")
                        .asText())
                .isEqualTo("REVOKED");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static String versionsUri(UUID applicationId) {
        return "/api/v1/applications/" + applicationId + "/versions";
    }

    private static String versionUri(UUID applicationId, UUID versionId) {
        return versionsUri(applicationId) + "/" + versionId;
    }

    private static String tagsUri(UUID applicationId, UUID versionId) {
        return versionUri(applicationId, versionId) + "/tags";
    }

    private static String archiveUri(UUID applicationId, UUID versionId) {
        return versionUri(applicationId, versionId) + "/archive";
    }

    private static String revokeUri(UUID applicationId, UUID versionId) {
        return versionUri(applicationId, versionId) + "/revoke-distribution";
    }

    private static String historyUri(UUID applicationId, UUID versionId) {
        return versionUri(applicationId, versionId) + "/history";
    }

    private UUID createVersion(String token, UUID applicationId, String versionName) {
        return UUID.fromString(post(token, versionsUri(applicationId), Map.of("versionName", versionName), 201)
                .get("id")
                .asText());
    }

    private UUID userIdByEmail(String email) {
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, email);
    }

    /**
     * Drives the distribution guard inside the sanctioned tenant scope
     * — the same binding the route interceptor gives every HTTP caller
     * and the executor gives background work, so the bean-level test
     * exercises the guard exactly as E5's artifact routes will.
     */
    private void guardInScope(UUID tenantId, UUID actorId, UUID applicationId, UUID versionId) {
        scopeExecutor.inScope(tenantId, actorId, () -> {
            distributionGuard.requireArtifactsMutable(applicationId, versionId);
            return null;
        });
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
                        "Lifecycle " + role))
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

    private JsonNode get(String bearer, String uri, int expectedStatus) {
        return exchange(uri, bearer, "GET", null).expect(expectedStatus);
    }

    private Recording get(String uri, String bearer) {
        return exchange(uri, bearer, "GET", null);
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
