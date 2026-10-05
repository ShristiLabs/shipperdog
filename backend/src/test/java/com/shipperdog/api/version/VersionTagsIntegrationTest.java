package com.shipperdog.api.version;

import static org.assertj.core.api.Assertions.assertThat;

import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.identity.AuditCapture;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
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
 * End-to-end acceptance flow for version tags (issue #18, S4-02,
 * FR-009): the replace-set mutation with case normalization and
 * validation (AC1), the {@code tag} filter across an application's
 * versions (AC2), the reserved vocabulary suggested in the API surface
 * while arbitrary custom tags are accepted (AC3), audited tag mutations
 * (AC4) — plus the platform conventions: permissions with concealed
 * denials, pagination composed with the filter, both ownership modes,
 * and tags surviving the ownership-scope transition.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
            "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef"
        })
@Testcontainers
@Import(AuditCapture.class)
class VersionTagsIntegrationTest {

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
    // AC1 + AC4: normalization, dedupe, validation, audit
    // -----------------------------------------------------------------------

    @Test
    void replaceNormalizesTrimsDedupesAndAuditsWithoutTouchingTheVersionRow() throws Exception {
        UUID orgId = createOrg("Tags Norm Corp");
        String adminToken = loginOfOrgRoleIn(orgId, "tags-norm-admin@example.com", "ORG_ADMIN");
        String ownerToken = loginOfOrgRoleIn(orgId, "tags-norm-owner@example.com", "APP_OWNER");
        UUID tenantId = createTenant(adminToken, orgId, "Tags Norm Bank", "crm-tags-norm-1");
        UUID appId = createTenantApp(ownerToken, tenantId, "Tag Farm", "com.tagsnorm.app");
        UUID versionId = versionOf(ownerToken, appId, "7.12.0");

        JsonNode replaced = put(
                ownerToken, tagsUri(appId, versionId), Map.of("tags", List.of("beta", " QA ", "bEtA", "Pilot")), 200);
        assertThat(replaced.get("tags").toString())
                .as("trim + UPPER normalization with post-normalization dedupe (AC1)")
                .isEqualTo("[\"BETA\",\"PILOT\",\"QA\"]");

        Boolean versionRowUntouched = jdbc.queryForObject(
                "SELECT updated_at = created_at FROM versions WHERE id = ?", Boolean.class, versionId);
        assertThat(versionRowUntouched)
                .as("a tag replacement never writes the version row (INV-004 territory: binaries, not tags)")
                .isTrue();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM version_tags WHERE version_id = ?", Integer.class, versionId))
                .isEqualTo(3);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM version_tags WHERE version_id = ? AND tag <> upper(tag)",
                        Integer.class,
                        versionId))
                .as("every stored tag is already UPPER (the CHECK's backstop)")
                .isZero();

        List<AuditEvent> events = audit.eventsOfType(AuditEventType.VERSION_TAGS_CHANGED);
        assertThat(events).as("the change is audited (AC4)").hasSize(1);
        assertThat(events.get(0).applicationId()).isEqualTo(appId);
        assertThat(events.get(0).tenantId()).isEqualTo(tenantId);
        assertThat(events.get(0).detail()).containsEntry("versionName", "7.12.0");
        assertThat(events.get(0).detail()).containsEntry("added", "BETA,PILOT,QA");
        assertThat(events.get(0).detail()).as("nothing was removed").doesNotContainKey("removed");
    }

    // -----------------------------------------------------------------------
    // AC2: the tag filter across the application's versions
    // -----------------------------------------------------------------------

    @Test
    void tagFilterSelectsMatchingVersionsAndComposesWithPagination() throws Exception {
        UUID orgId = createOrg("Tags Filter Corp");
        String adminToken = loginOfOrgRoleIn(orgId, "tags-filter-admin@example.com", "ORG_ADMIN");
        String ownerToken = loginOfOrgRoleIn(orgId, "tags-filter-owner@example.com", "APP_OWNER");
        UUID tenantId = createTenant(adminToken, orgId, "Tags Filter Bank", "crm-tags-filter-1");
        UUID appId = createTenantApp(ownerToken, tenantId, "Filter Me", "com.tagsfilter.app");
        UUID old = versionOf(ownerToken, appId, "7.9.0");
        UUID middle = versionOf(ownerToken, appId, "7.12.0");
        UUID newest = versionOf(ownerToken, appId, "10.0.0");
        put(ownerToken, tagsUri(appId, old), Map.of("tags", List.of("QA")), 200);
        put(ownerToken, tagsUri(appId, middle), Map.of("tags", List.of("beta")), 200);
        put(ownerToken, tagsUri(appId, newest), Map.of("tags", List.of("BETA", "STABLE")), 200);

        JsonNode beta = get(ownerToken, versionsUri(appId) + "?tag=beta", 200);
        assertThat(beta.get("items").size())
                .as("case-insensitive filter input (AC2)")
                .isEqualTo(2);
        assertThat(beta.get("items").get(0).get("versionName").asText())
                .as("the newest-first ordering holds under the filter")
                .isEqualTo("10.0.0");
        assertThat(beta.get("items").get(1).get("versionName").asText()).isEqualTo("7.12.0");
        assertThat(beta.get("items").get(0).get("tags").toString())
                .as("items carry their tags, sorted UPPER")
                .isEqualTo("[\"BETA\",\"STABLE\"]");

        assertThat(get(ownerToken, versionsUri(appId) + "?tag=STABLE", 200)
                        .get("items")
                        .size())
                .isEqualTo(1);
        assertThat(get(ownerToken, versionsUri(appId) + "?tag=QA", 200)
                        .get("items")
                        .get(0)
                        .get("versionName")
                        .asText())
                .isEqualTo("7.9.0");

        JsonNode none = get(ownerToken, versionsUri(appId) + "?tag=UNKNOWN", 200);
        assertThat(none.get("items").size()).isZero();
        assertThat(none.has("next_cursor")).as("no matches, no next page").isFalse();

        JsonNode pageOne = get(ownerToken, versionsUri(appId) + "?tag=BETA&limit=1", 200);
        assertThat(pageOne.get("items").get(0).get("versionName").asText()).isEqualTo("10.0.0");
        JsonNode pageTwo = get(
                ownerToken,
                versionsUri(appId) + "?tag=BETA&limit=1&cursor="
                        + pageOne.get("next_cursor").asText(),
                200);
        assertThat(pageTwo.get("items").get(0).get("versionName").asText()).isEqualTo("7.12.0");
        assertThat(pageTwo.has("next_cursor")).isFalse();

        JsonNode detail = get(ownerToken, versionsUri(appId) + "/" + newest, 200);
        assertThat(detail.get("tags").toString()).isEqualTo("[\"BETA\",\"STABLE\"]");
    }

    // -----------------------------------------------------------------------
    // AC3: reserved vocabulary suggested, custom tags accepted
    // -----------------------------------------------------------------------

    @Test
    void reservedVocabularyIsSuggestedInTheApiAndCustomTagsAreAccepted() throws Exception {
        UUID orgId = createOrg("Tags Vocab Corp");
        String adminToken = loginOfOrgRoleIn(orgId, "tags-vocab-admin@example.com", "ORG_ADMIN");
        String ownerToken = loginOfOrgRoleIn(orgId, "tags-vocab-owner@example.com", "APP_OWNER");
        UUID tenantId = createTenant(adminToken, orgId, "Tags Vocab Bank", "crm-tags-vocab-1");
        UUID appId = createTenantApp(ownerToken, tenantId, "Vocabulary", "com.tagsvocab.app");
        UUID versionId = versionOf(ownerToken, appId, "1.0.0");

        JsonNode replaced = put(
                ownerToken,
                tagsUri(appId, versionId),
                Map.of(
                        "tags",
                        List.of(
                                "QA",
                                "UAT",
                                "BETA",
                                "PILOT",
                                "STABLE",
                                "PRODUCTION",
                                "LEGACY",
                                "INTERNAL_PILOT_3",
                                "nightly-2")),
                200);
        assertThat(replaced.get("tags").size())
                .as("the whole FR-009 set plus arbitrary custom tags is accepted (AC3)")
                .isEqualTo(9);
        assertThat(replaced.get("tags").toString()).contains("INTERNAL_PILOT_3", "NIGHTLY-2");

        String spec = client.get().uri("/v3/api-docs").retrieve().body(String.class);
        assertThat(spec)
                .as("the reserved words are suggested in the API's tag schema description")
                .contains("PRODUCTION");
        assertThat(spec).contains("LEGACY");
    }

    // -----------------------------------------------------------------------
    // AC4 depth: diff-based replacement, clearing, no-op puts
    // -----------------------------------------------------------------------

    @Test
    void replacementIsDiffBasedClearingEmptiesAndNoOpsDoNotAudit() throws Exception {
        UUID orgId = createOrg("Tags Diff Corp");
        String adminToken = loginOfOrgRoleIn(orgId, "tags-diff-admin@example.com", "ORG_ADMIN");
        String ownerToken = loginOfOrgRoleIn(orgId, "tags-diff-owner@example.com", "APP_OWNER");
        UUID tenantId = createTenant(adminToken, orgId, "Tags Diff Bank", "crm-tags-diff-1");
        UUID appId = createTenantApp(ownerToken, tenantId, "Diffing", "com.tagsdiff.app");
        UUID versionId = versionOf(ownerToken, appId, "2.0.0");

        put(ownerToken, tagsUri(appId, versionId), Map.of("tags", List.of("BETA", "QA")), 200);
        JsonNode second = put(ownerToken, tagsUri(appId, versionId), Map.of("tags", List.of("QA", "stable")), 200);
        assertThat(second.get("tags").toString()).isEqualTo("[\"QA\",\"STABLE\"]");

        List<AuditEvent> events = audit.eventsOfType(AuditEventType.VERSION_TAGS_CHANGED);
        assertThat(events).hasSize(2);
        assertThat(events.get(1).detail()).containsEntry("added", "STABLE");
        assertThat(events.get(1).detail()).containsEntry("removed", "BETA");

        JsonNode noOp = put(ownerToken, tagsUri(appId, versionId), Map.of("tags", List.of("stable", "QA")), 200);
        assertThat(noOp.get("tags").toString()).isEqualTo("[\"QA\",\"STABLE\"]");
        assertThat(audit.eventsOfType(AuditEventType.VERSION_TAGS_CHANGED))
                .as("a put that changes nothing is not a mutation (AC4)")
                .hasSize(2);

        put(ownerToken, tagsUri(appId, versionId), Map.of("tags", List.of()), 200);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM version_tags WHERE version_id = ?", Integer.class, versionId))
                .as("the empty list clears the set")
                .isZero();
        assertThat(audit.eventsOfType(AuditEventType.VERSION_TAGS_CHANGED)).hasSize(3);
        assertThat(audit.eventsOfType(AuditEventType.VERSION_TAGS_CHANGED)
                        .get(2)
                        .detail())
                .containsEntry("removed", "QA,STABLE");
    }

    // -----------------------------------------------------------------------
    // Validation: request values and filter values
    // -----------------------------------------------------------------------

    @Test
    void invalidTagValuesAndMalformedFiltersAreRejectedWithNothingPersisted() throws Exception {
        UUID orgId = createOrg("Tags Invalid Corp");
        String adminToken = loginOfOrgRoleIn(orgId, "tags-invalid-admin@example.com", "ORG_ADMIN");
        String ownerToken = loginOfOrgRoleIn(orgId, "tags-invalid-owner@example.com", "APP_OWNER");
        UUID tenantId = createTenant(adminToken, orgId, "Tags Invalid Bank", "crm-tags-invalid-1");
        UUID appId = createTenantApp(ownerToken, tenantId, "Invalid Tags", "com.tagsinvalid.app");
        UUID versionId = versionOf(ownerToken, appId, "3.0.0");

        List<List<String>> badBodies = List.of(
                List.of(""),
                List.of("   "),
                List.of("BAD TAG"),
                List.of("-LEADING"),
                List.of("_LEADING"),
                List.of("OK" + "X".repeat(49)),
                List.of("ТАГ"),
                tooManyTags());
        for (List<String> bad : badBodies) {
            JsonNode rejected = put(ownerToken, tagsUri(appId, versionId), Map.of("tags", bad), 400);
            assertThat(rejected.get("code").asText()).isEqualTo("INVALID_REQUEST");
        }
        List<String> withNull = new ArrayList<>();
        withNull.add("BETA");
        withNull.add(null);
        assertThat(put(ownerToken, tagsUri(appId, versionId), Map.of("tags", withNull), 400)
                        .get("code")
                        .asText())
                .isEqualTo("INVALID_REQUEST");
        assertThat(put(ownerToken, tagsUri(appId, versionId), Map.of(), 400)
                        .get("code")
                        .asText())
                .isEqualTo("INVALID_REQUEST");

        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM version_tags WHERE version_id = ?", Integer.class, versionId))
                .as("no invalid body persisted anything")
                .isZero();

        for (String badFilter : List.of("", "BAD TAG", "-LEADING", "X".repeat(51))) {
            JsonNode rejected = get(ownerToken, versionsUri(appId) + "?tag=" + encode(badFilter), 400);
            assertThat(rejected.get("code").asText()).isEqualTo("INVALID_REQUEST");
        }
    }

    // -----------------------------------------------------------------------
    // Concealment and permissions
    // -----------------------------------------------------------------------

    @Test
    void tagRoutesEnforcePermissionsAndConcealEveryMiss() throws Exception {
        UUID orgId = createOrg("Tags Perms Corp");
        String adminToken = loginOfOrgRoleIn(orgId, "tags-perms-admin@example.com", "ORG_ADMIN");
        String ownerToken = loginOfOrgRoleIn(orgId, "tags-perms-owner@example.com", "APP_OWNER");
        UUID tenantId = createTenant(adminToken, orgId, "Tags Perms Bank", "crm-tags-perms-1");
        UUID appId = createTenantApp(ownerToken, tenantId, "Guarded", "com.tagsperms.app");
        UUID versionId = versionOf(ownerToken, appId, "1.0.0");

        // Read-holding roles filter; write-less roles conceal on PUT.
        String developerToken = loginOfOrgRoleIn(orgId, "tags-perms-dev@example.com", "DEVELOPER");
        assertThat(get(developerToken, versionsUri(appId) + "?tag=BETA", 200)
                        .get("items")
                        .size())
                .isZero();
        JsonNode denied = exchange(tagsUri(appId, versionId), developerToken, "PUT", Map.of("tags", List.of("BETA")))
                .expect(404);
        assertThat(denied.get("code").asText())
                .as("write denials conceal as the application miss")
                .isEqualTo("APPLICATION_NOT_FOUND");

        put(ownerToken, tagsUri(appId, versionId), Map.of("tags", List.of("BETA")), 200);

        // A version id under the wrong application is the standard miss.
        UUID otherApp = createTenantApp(ownerToken, tenantId, "Guarded Neighbor", "com.tagsperms.next");
        assertThat(put(ownerToken, tagsUri(otherApp, versionId), Map.of("tags", List.of("BETA")), 404)
                        .get("code")
                        .asText())
                .isEqualTo("VERSION_NOT_FOUND");
        assertThat(put(ownerToken, tagsUri(appId, UUID.randomUUID()), Map.of("tags", List.of("BETA")), 404)
                        .get("code")
                        .asText())
                .isEqualTo("VERSION_NOT_FOUND");

        // Tenant roles hold no app: grants; foreign organizations probe
        // blind — both conceal exactly like an unknown application.
        UUID tenantAdminId = userIdByEmail(insertOrglessUser("tags-perms-tadmin@example.com"));
        assignMember(adminToken, tenantId, tenantAdminId, "TENANT_ADMIN");
        String tenantAdminToken = accessToken(login("tags-perms-tadmin@example.com", PASSWORD));
        assertThat(exchange(tagsUri(appId, versionId), tenantAdminToken, "PUT", Map.of("tags", List.of("QA"))).status)
                .isEqualTo(404);
        UUID otherOrg = createOrg("Tags Perms Other Corp");
        String foreignToken = loginOfOrgRoleIn(otherOrg, "tags-perms-foreign@example.com", "ORG_ADMIN");
        JsonNode foreignDenied = exchange(tagsUri(appId, versionId), foreignToken, "PUT", Map.of("tags", List.of("QA")))
                .expect(404);
        JsonNode genuineMiss = exchange(
                        tagsUri(UUID.randomUUID(), UUID.randomUUID()), foreignToken, "PUT", Map.of("tags", List.of()))
                .expect(404);
        assertThat(foreignDenied.get("code").asText())
                .isEqualTo(genuineMiss.get("code").asText())
                .isEqualTo("APPLICATION_NOT_FOUND");
    }

    // -----------------------------------------------------------------------
    // S3-02/S3-03: shared applications and the ownership transition
    // -----------------------------------------------------------------------

    @Test
    void tagsServeThroughTheOrganizationScopeAndSurviveOwnershipTransitions() throws Exception {
        UUID orgId = createOrg("Tags Shared Corp");
        String adminToken = loginOfOrgRoleIn(orgId, "tags-shared-admin@example.com", "ORG_ADMIN");
        String ownerToken = loginOfOrgRoleIn(orgId, "tags-shared-owner@example.com", "APP_OWNER");
        UUID tenantId = createTenant(adminToken, orgId, "Tags Shared Bank", "crm-tags-shared-1");
        UUID appId = createTenantApp(ownerToken, tenantId, "Shared Tags", "com.tagsshared.app");
        UUID versionId = versionOf(ownerToken, appId, "4.0.0");
        put(ownerToken, tagsUri(appId, versionId), Map.of("tags", List.of("UAT")), 200);

        patch(ownerToken, "/api/v1/applications/" + appId, Map.of("ownershipScope", "ORGANIZATION"), 200);
        JsonNode orgScoped = get(ownerToken, versionsUri(appId) + "?tag=uat", 200);
        assertThat(orgScoped.get("items").size())
                .as("tags follow the version rows into the organization scope")
                .isEqualTo(1);
        assertThat(orgScoped.get("items").get(0).get("tags").toString()).isEqualTo("[\"UAT\"]");

        patch(
                ownerToken,
                "/api/v1/applications/" + appId,
                Map.of("ownershipScope", "TENANT", "tenantId", tenantId.toString()),
                200);
        JsonNode tenantScoped = get(ownerToken, versionsUri(appId) + "?tag=UAT", 200);
        assertThat(tenantScoped.get("items").size()).isEqualTo(1);
        assertThat(put(ownerToken, tagsUri(appId, versionId), Map.of("tags", List.of("UAT", "PROD")), 200)
                        .get("tags")
                        .toString())
                .isEqualTo("[\"PROD\",\"UAT\"]");
    }

    // -----------------------------------------------------------------------
    // Concurrency: simultaneous replace-sets converge to one request
    // -----------------------------------------------------------------------

    /**
     * Two writers replacing the same version's tags at the same moment
     * must converge to exactly one of the two requested sets — never a
     * union of both (a stale before-read reconciling against an empty
     * set), never a partial application. The rounds repeat because the
     * defect window (both transactions reading {@code before} before
     * either commits) is timing-dependent; several synchronized rounds
     * walk across it. Both writers must also observe 200 — the
     * insert-if-absent claim means no constraint violation may surface
     * to either caller.
     */
    @Test
    void concurrentReplacesConvergeToExactlyOneOfTheRequestedSets() throws Exception {
        UUID orgId = createOrg("Tags Race Corp");
        String adminToken = loginOfOrgRoleIn(orgId, "tags-race-admin@example.com", "ORG_ADMIN");
        String ownerToken = loginOfOrgRoleIn(orgId, "tags-race-owner@example.com", "APP_OWNER");
        UUID tenantId = createTenant(adminToken, orgId, "Tags Race Bank", "crm-tags-race-1");
        UUID appId = createTenantApp(ownerToken, tenantId, "Racing", "com.tagsrace.app");
        UUID versionId = versionOf(ownerToken, appId, "5.0.0");

        List<String> setA = List.of("RACE_A1", "RACE_A2", "RACE_A3");
        List<String> setB = List.of("RACE_B1", "RACE_B2");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 60; round++) {
                CountDownLatch start = new CountDownLatch(1);
                Future<JsonNode> writerA = pool.submit(putter(start, ownerToken, appId, versionId, setA));
                Future<JsonNode> writerB = pool.submit(putter(start, ownerToken, appId, versionId, setB));
                start.countDown();
                writerA.get(30, TimeUnit.SECONDS);
                writerB.get(30, TimeUnit.SECONDS);

                List<String> stored = jdbc.queryForList(
                        "SELECT tag FROM version_tags WHERE version_id = ? ORDER BY tag", String.class, versionId);
                boolean convergedToA = stored.equals(List.copyOf(new TreeSet<>(setA)));
                boolean convergedToB = stored.equals(List.copyOf(new TreeSet<>(setB)));
                assertThat(convergedToA || convergedToB)
                        .as(
                                "round %d: the stored set must be exactly one request, never a union or partial"
                                        + " state (stored: %s)",
                                round, stored)
                        .isTrue();
            }
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
    }

    /** One synchronized writer: releases at the latch, then issues its replace. */
    private Callable<JsonNode> putter(
            CountDownLatch start, String token, UUID appId, UUID versionId, List<String> tags) {
        return () -> {
            start.await();
            return put(token, tagsUri(appId, versionId), Map.of("tags", tags), 200);
        };
    }

    // -----------------------------------------------------------------------
    // Validity at exactly the limits
    // -----------------------------------------------------------------------

    /**
     * The vocabulary's boundaries are inclusive: a fifty-character tag
     * and a twenty-tag set are valid — only beyond them is rejection
     * (the over-limit rejections are covered by the invalid-values
     * test). Guards an off-by-one in either the service cap or the
     * shared shape pattern.
     */
    @Test
    void boundarySizedTagsAreAcceptedAtExactlyTheLimits() throws Exception {
        UUID orgId = createOrg("Tags Limit Corp");
        String adminToken = loginOfOrgRoleIn(orgId, "tags-limit-admin@example.com", "ORG_ADMIN");
        String ownerToken = loginOfOrgRoleIn(orgId, "tags-limit-owner@example.com", "APP_OWNER");
        UUID tenantId = createTenant(adminToken, orgId, "Tags Limit Bank", "crm-tags-limit-1");
        UUID appId = createTenantApp(ownerToken, tenantId, "Limits", "com.tagslimit.app");
        UUID versionId = versionOf(ownerToken, appId, "6.0.0");

        String fiftyChars = "A" + "B".repeat(49);
        assertThat(put(ownerToken, tagsUri(appId, versionId), Map.of("tags", List.of(fiftyChars)), 200)
                        .get("tags")
                        .toString())
                .as("a fifty-character tag is exactly at the shape limit")
                .isEqualTo("[\"" + fiftyChars + "\"]");

        List<String> twentyTags = IntStream.rangeClosed(1, 20)
                .mapToObj(i -> String.format("LIMIT_%02d", i))
                .toList();
        JsonNode atCap = put(ownerToken, tagsUri(appId, versionId), Map.of("tags", twentyTags), 200);
        assertThat(atCap.get("tags").size())
                .as("twenty distinct tags is exactly at the cap")
                .isEqualTo(20);
        assertThat(get(ownerToken, versionsUri(appId) + "?tag=LIMIT_07", 200)
                        .get("items")
                        .size())
                .as("a boundary tag still filters")
                .isEqualTo(1);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static String versionsUri(UUID applicationId) {
        return "/api/v1/applications/" + applicationId + "/versions";
    }

    private static String tagsUri(UUID applicationId, UUID versionId) {
        return versionsUri(applicationId) + "/" + versionId + "/tags";
    }

    private static List<String> tooManyTags() {
        List<String> tags = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            tags.add("T" + i);
        }
        return tags;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private UUID versionOf(String token, UUID applicationId, String versionName) {
        return UUID.fromString(post(token, versionsUri(applicationId), Map.of("versionName", versionName), 201)
                .get("id")
                .asText());
    }

    private UUID createTenantApp(String token, UUID tenantId, String name, String bundleId) {
        Map<String, Object> body = new HashMap<>();
        body.put("tenantId", tenantId.toString());
        body.put("name", name);
        body.put("platforms", new ArrayList<>(List.of("IOS", "ANDROID")));
        body.put("bundleId", bundleId);
        body.put("packageName", bundleId);
        return UUID.fromString(
                post(token, "/api/v1/applications", body, 201).get("id").asText());
    }

    private void assignMember(String token, UUID tenantId, UUID userId, String role) {
        post(token, "/api/v1/tenants/" + tenantId + "/members", Map.of("userId", userId.toString(), "role", role), 201);
    }

    private UUID createTenant(String token, UUID organizationId, String name, String externalId) {
        JsonNode created = post(
                token,
                "/api/v1/tenants",
                Map.of("organizationId", organizationId.toString(), "name", name, "externalId", externalId),
                201);
        return UUID.fromString(created.get("id").asText());
    }

    private UUID userIdByEmail(String email) {
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, email);
    }

    private String insertOrglessUser(String email) {
        String devToken = accessToken(login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD));
        JsonNode invitation = post(devToken, "/api/v1/auth/invitations", Map.of("email", email), 201);
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
                        "Version Tags User"))
                .retrieve()
                .body(String.class);
        return email;
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
                        "Version Tags " + role))
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
