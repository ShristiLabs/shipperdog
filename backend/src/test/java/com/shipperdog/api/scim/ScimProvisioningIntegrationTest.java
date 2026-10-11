package com.shipperdog.api.scim;

import static org.assertj.core.api.Assertions.assertThat;

import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.identity.AuditCapture;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
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
 * The SCIM provisioning protocol and lifecycle for issue #69 (S14-03):
 * the discovery documents, the uniform bearer-token rejections, the
 * three IdP payload shapes (Entra, Okta, Google Workspace) pinned as
 * contract fixtures, the externalId-first idempotent create, the
 * leaver hard-lock cascade (login refused, PAT refused, memberships
 * suspended, tester record deactivated), the re-provision
 * re-derivation, and the group sync that drives role/tenant
 * assignments per the mapping rules.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
            "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef"
        })
@Testcontainers
@Import(AuditCapture.class)
class ScimProvisioningIntegrationTest {

    private static final String DEV_ADMIN_EMAIL = "dev@shipperdog.local";

    private static final String DEV_ADMIN_PASSWORD = "shipperdog-dev-admin";

    private static final String PATCH_SCHEMA = "urn:ietf:params:scim:api:messages:2.0:PatchOp";

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

    private String devToken;

    private UUID orgId;

    private UUID tenantId;

    /** The org's live SCIM connector token. */
    private String scimToken;

    @BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
        audit.reset();
        devToken = devToken();
        orgId = createOrganization("SCIM Provisioning " + UUID.randomUUID());
        tenantId = createTenant(orgId, "scim-tenant");
        // Enabled connector, a Developers role mapping, and a tenant
        // mapping behind the same group name.
        putScimConfig(Map.of(
                "enabled",
                true,
                "defaultRole",
                "VIEWER",
                "groupRoleMappings",
                List.of(Map.of("group", "Developers", "orgRole", "DEVELOPER")),
                "groupTenantMappings",
                List.of(Map.of(
                        "group", "Developers",
                        "tenantId", tenantId.toString(),
                        "tenantRole", "TENANT_TESTER"))));
        scimToken = generateScimToken();
    }

    // ------------------------------------------------------------------
    // Discovery
    // ------------------------------------------------------------------

    @Test
    void discoveryEndpointsAnswerAnonymousCapabilityMetadata() throws Exception {
        JsonNode spConfig = scimGet("/scim/v2/ServiceProviderConfig", null, 200);
        assertThat(spConfig.get("schemas").get(0).asText())
                .isEqualTo("urn:ietf:params:scim:schemas:core:2.0:ServiceProviderConfig");
        assertThat(spConfig.get("patch").get("supported").asBoolean()).isTrue();
        assertThat(spConfig.get("filter").get("supported").asBoolean()).isTrue();
        assertThat(spConfig.get("bulk").get("supported").asBoolean()).isFalse();
        assertThat(spConfig.get("authenticationSchemes").get(0).get("type").asText())
                .isEqualTo("oauthbearertoken");

        JsonNode resourceTypes = scimGet("/scim/v2/ResourceTypes", null, 200);
        List<String> names = new ArrayList<>();
        resourceTypes.forEach(type -> names.add(type.get("name").asText()));
        assertThat(names).containsExactly("User", "Group");

        JsonNode schemas = scimGet("/scim/v2/Schemas", null, 200);
        assertThat(schemas.size()).isEqualTo(3);
    }

    // ------------------------------------------------------------------
    // Authentication
    // ------------------------------------------------------------------

    @Test
    void authenticationFailuresAreUniformWithScimErrorShape() throws Exception {
        // Missing, malformed, and unknown credentials all answer the
        // SAME 401 document — no organization hints, no leg hints.
        for (String header : new String[] {null, "Basic abc", "Bearer ", "Bearer sds-not-a-real-token"}) {
            JsonNode error = scimGetRawHeader("/scim/v2/Users", header, 401);
            assertThat(error.get("schemas").get(0).asText()).isEqualTo("urn:ietf:params:scim:api:messages:2.0:Error");
            assertThat(error.get("status").asText()).isEqualTo("401");
        }
        List<String> reasons = audit.eventsOfType(AuditEventType.SCIM_REQUEST_REJECTED).stream()
                .map(event -> event.detail().get("reason"))
                .toList();
        assertThat(reasons)
                .containsExactlyInAnyOrder("missing_token", "missing_token", "missing_token", "invalid_token");

        // A foreign organization's token answers 200 for ITS users but
        // cannot see this organization's resources: the cross-org user
        // id is the uniform 404.
        UUID foreignOrg = createOrganization("SCIM Foreign " + UUID.randomUUID());
        putScimConfigFor(foreignOrg, Map.of("enabled", true, "groupRoleMappings", List.of()));
        String foreignToken = generateScimTokenFor(foreignOrg);
        JsonNode created =
                scimPost("/scim/v2/Users", entraUser("entra-foreign", "foreign.person@foreign.example"), 201);
        assertThat(scimStatus(HttpMethod.GET, "/scim/v2/Users", foreignToken)).isEqualTo(200);
        assertThat(scimStatus(
                        HttpMethod.GET, "/scim/v2/Users/" + created.get("id").asText(), scimToken))
                .as("the owning organization's token reads its own user")
                .isEqualTo(200);
        assertThat(scimStatus(
                        HttpMethod.GET, "/scim/v2/Users/" + created.get("id").asText(), foreignToken))
                .as("a foreign organization's token conceals this organization's user at the 404")
                .isEqualTo(404);
    }

    // ------------------------------------------------------------------
    // The joiner flow and the IdP payload shapes
    // ------------------------------------------------------------------

    @Test
    void entraShapedCreateProvisionsAPasswordlessAccountWithTheDefaultRole() throws Exception {
        JsonNode created =
                scimPost("/scim/v2/Users", entraUser("entra-1111-2222", "ada.lovelace@company.example"), 201);
        assertThat(created.get("id").asText()).isNotBlank();
        assertThat(created.get("userName").asText()).isEqualTo("ada.lovelace@company.example");
        assertThat(created.get("active").asBoolean()).isTrue();
        assertThat(created.get("meta").get("resourceType").asText()).isEqualTo("User");

        // The SCIM id is separate from the account UUID.
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT su.id AS scim_id, su.external_id, u.id AS user_id, u.full_name, u.password_hash "
                        + "FROM scim_users su JOIN users u ON u.id = su.user_id WHERE su.organization_id = ?",
                orgId);
        assertThat(row.get("external_id")).isEqualTo("entra-1111-2222");
        assertThat(row.get("scim_id")).isNotEqualTo(row.get("user_id"));
        assertThat(row.get("password_hash")).isNull();
        assertThat(row.get("full_name")).isEqualTo("Ada Lovelace");

        // The org membership carries the configured default role.
        String role = jdbc.queryForObject(
                "SELECT role FROM organization_members WHERE organization_id = ? AND user_id = ?",
                String.class,
                orgId,
                row.get("user_id"));
        assertThat(role).isEqualTo("VIEWER");

        assertThat(audit.eventsOfType(AuditEventType.SCIM_USER_PROVISIONED)).hasSize(1);
        assertThat(audit.eventsOfType(AuditEventType.SCIM_USER_PROVISIONED)
                        .getFirst()
                        .detail()
                        .get("kind"))
                .isEqualTo("created");

        // Read-back by id renders the same resource, groups empty.
        JsonNode fetched = scimGet("/scim/v2/Users/" + created.get("id").asText(), scimToken, 200);
        assertThat(fetched.get("id").asText()).isEqualTo(created.get("id").asText());
        assertThat(fetched.get("groups")).isEmpty();
    }

    @Test
    void oktaShapedCreateLinksByVerifiedEmailMatch() throws Exception {
        // A pre-existing account with a local password: the SCIM create
        // with the same address links instead of creating a second one.
        String address = "okta.person@company.example";
        String userToken = invitedAccountToken(address, "Okta Person");
        int usersBefore = jdbc.queryForObject("SELECT count(*) FROM users", Integer.class);

        Map<String, Object> okta = new HashMap<>();
        okta.put("schemas", List.of("urn:ietf:params:scim:schemas:core:2.0:User"));
        okta.put("externalId", "okta-00okta987654321");
        okta.put("userName", "okta.person@company.example");
        okta.put("name", Map.of("givenName", "Okta", "familyName", "Person", "formatted", "Okta Person"));
        okta.put("active", true);
        JsonNode created = scimPost("/scim/v2/Users", okta, 201);

        int usersAfter = jdbc.queryForObject("SELECT count(*) FROM users", Integer.class);
        assertThat(usersAfter).as("the email match linked, not created").isEqualTo(usersBefore);

        // The linked account's local login still works and its SCIM
        // row points at the SAME account.
        assertThat(loginStatus(address, "correct-horse-battery-1")).isEqualTo(200);
        UUID linkedUserId = jdbc.queryForObject(
                "SELECT user_id FROM scim_users WHERE organization_id = ? AND external_id = ?",
                UUID.class,
                orgId,
                "okta-00okta987654321");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM scim_users WHERE organization_id = ? AND user_id = ?",
                        Integer.class,
                        orgId,
                        linkedUserId))
                .isEqualTo(1);
        assertThat(created.get("id").asText()).isNotBlank();
    }

    @Test
    void workspaceShapedCreateUsesThePrimaryEmail() throws Exception {
        Map<String, Object> workspace = new HashMap<>();
        workspace.put("schemas", List.of("urn:ietf:params:scim:schemas:core:2.0:User"));
        workspace.put("externalId", "workspace-1082736450");
        workspace.put("userName", "ws.person@company.example");
        workspace.put(
                "name", Map.of("givenName", "Workspace", "familyName", "Person", "formatted", "Workspace Person"));
        workspace.put(
                "emails",
                List.of(
                        Map.of("value", "secondary@company.example", "type", "home"),
                        Map.of("value", "primary.person@company.example", "type", "work", "primary", true)));
        JsonNode created = scimPost("/scim/v2/Users", workspace, 201);
        assertThat(created.get("emails").get(0).get("value").asText()).isEqualTo("primary.person@company.example");

        String email = jdbc.queryForObject(
                "SELECT u.email FROM scim_users su JOIN users u ON u.id = su.user_id "
                        + "WHERE su.organization_id = ? AND su.external_id = ?",
                String.class,
                orgId,
                "workspace-1082736450");
        assertThat(email)
                .as("the PRIMARY email wins over userName and the other entries")
                .isEqualTo("primary.person@company.example");
    }

    @Test
    void platformAdministratorAccountsAreRefused() throws Exception {
        Map<String, Object> payload = entraUser("entra-dev-admin", DEV_ADMIN_EMAIL);
        JsonNode error = scimPost("/scim/v2/Users", payload, 409);
        assertThat(error.get("scimType").asText()).isEqualTo("uniqueness");
    }

    // ------------------------------------------------------------------
    // Idempotency and conflicts
    // ------------------------------------------------------------------

    @Test
    void createIsIdempotentOnExternalIdAndConflictsOnUserName() throws Exception {
        JsonNode first = scimPost("/scim/v2/Users", entraUser("dup-external-1", "dup.one@company.example"), 201);
        JsonNode replay = scimPost("/scim/v2/Users", entraUser("dup-external-1", "dup.one@company.example"), 200);
        assertThat(replay.get("id").asText())
                .as("the replay answers the SAME stable SCIM id")
                .isEqualTo(first.get("id").asText());
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM scim_users WHERE organization_id = ? AND external_id = ?",
                        Integer.class,
                        orgId,
                        "dup-external-1"))
                .isEqualTo(1);

        JsonNode conflict = scimPost("/scim/v2/Users", entraUser("dup-external-2", "dup.one@company.example"), 409);
        assertThat(conflict.get("scimType").asText()).isEqualTo("uniqueness");
    }

    @Test
    void emailChangeCarriesToTheAccountAndCollisionsRefuse() throws Exception {
        String scimId = scimPost("/scim/v2/Users", entraUser("mail-1", "mail.one@company.example"), 201)
                .get("id")
                .asText();
        scimPost("/scim/v2/Users", entraUser("mail-2", "mail.two@company.example"), 201);

        Map<String, Object> changed = entraUser("mail-1", "mail.one@company.example");
        changed.put("emails", List.of(Map.of("value", "mail.one.renamed@company.example", "primary", true)));
        JsonNode updated = scimPut("/scim/v2/Users/" + scimId, changed, 200);
        assertThat(updated.get("emails").get(0).get("value").asText()).isEqualTo("mail.one.renamed@company.example");
        assertThat(audit.eventsOfType(AuditEventType.SCIM_USER_UPDATED)).isNotEmpty();

        // Moving onto the OTHER account's address is a 409.
        changed.put("emails", List.of(Map.of("value", "mail.two@company.example", "primary", true)));
        assertThat(scimStatusBody(HttpMethod.PUT, "/scim/v2/Users/" + scimId, changed))
                .isEqualTo(409);
    }

    // ------------------------------------------------------------------
    // Validation, filters, pagination
    // ------------------------------------------------------------------

    @Test
    void malformedRequestsAreRejectedPerTheScimSpec() throws Exception {
        // Unparseable body: 400 invalidSyntax.
        JsonNode syntax = scimPostRaw("/scim/v2/Users", "{\"userName\": ", 400);
        assertThat(syntax.get("scimType").asText()).isEqualTo("invalidSyntax");

        // Missing required attributes: 400 invalidValue.
        assertThat(scimPost("/scim/v2/Users", entraUser(null, "missing.external@company.example"), 400)
                        .get("scimType")
                        .asText())
                .isEqualTo("invalidValue");
        Map<String, Object> noUserName = entraUser("no-user-name", null);
        assertThat(scimPost("/scim/v2/Users", noUserName, 400).get("scimType").asText())
                .isEqualTo("invalidValue");

        // No usable email at all.
        Map<String, Object> noEmail = new HashMap<>(entraUser("no-email-1", "not-an-email"));
        noEmail.remove("emails");
        assertThat(scimPost("/scim/v2/Users", noEmail, 400).get("scimType").asText())
                .isEqualTo("invalidValue");

        // Unsupported filters are refused LOUDLY; the supported two work.
        assertThat(scimGetFiltered("name.givenName eq \"Ada\"", 400)
                        .get("scimType")
                        .asText())
                .isEqualTo("invalidFilter");
        assertThat(scimGetFiltered("emails.value eq \"a@b.c\"", 400)
                        .get("scimType")
                        .asText())
                .isEqualTo("invalidFilter");

        scimPost("/scim/v2/Users", entraUser("filter-1", "filter.one@company.example"), 201);
        JsonNode byUserName = scimGetFiltered("userName eq \"filter.one@company.example\"", 200);
        assertThat(byUserName.get("totalResults").asInt()).isEqualTo(1);
        JsonNode byExternalId = scimGetFiltered("externalId eq \"filter-1\"", 200);
        assertThat(byExternalId.get("totalResults").asInt()).isEqualTo(1);
        assertThat(byExternalId.get("resources").get(0).get("userName").asText())
                .isEqualTo("filter.one@company.example");

        // Unknown user: the uniform SCIM 404.
        JsonNode miss = scimGet("/scim/v2/Users/" + UUID.randomUUID(), scimToken, 404);
        assertThat(miss.get("schemas").get(0).asText()).isEqualTo("urn:ietf:params:scim:api:messages:2.0:Error");
    }

    @Test
    void listingIsScimShapedStartIndexAndCount() throws Exception {
        for (int i = 1; i <= 3; i++) {
            scimPost("/scim/v2/Users", entraUser("page-" + i, "page." + i + "@company.example"), 201);
        }
        JsonNode pageOne = scimGet("/scim/v2/Users", scimToken, 200);
        assertThat(pageOne.get("schemas").get(0).asText())
                .isEqualTo("urn:ietf:params:scim:api:messages:2.0:ListResponse");
        assertThat(pageOne.get("totalResults").asInt()).isEqualTo(3);
        assertThat(pageOne.get("startIndex").asInt()).isEqualTo(1);
        assertThat(pageOne.get("itemsPerPage").asInt()).isEqualTo(100);
        assertThat(pageOne.get("resources")).hasSize(3);

        JsonNode paged = scimGet("/scim/v2/Users?startIndex=2&count=1", scimToken, 200);
        assertThat(paged.get("totalResults").asInt()).isEqualTo(3);
        assertThat(paged.get("startIndex").asInt()).isEqualTo(2);
        assertThat(paged.get("itemsPerPage").asInt()).isEqualTo(1);
        assertThat(paged.get("resources")).hasSize(1);
        assertThat(paged.get("resources").get(0).get("userName").asText()).isEqualTo("page.2@company.example");

        JsonNode emptyPage = scimGet("/scim/v2/Users?startIndex=9&count=5", scimToken, 200);
        assertThat(emptyPage.get("resources")).isEmpty();
        assertThat(emptyPage.get("totalResults").asInt()).isEqualTo(3);
    }

    // ------------------------------------------------------------------
    // The leaver flow: the hard-lock cascade
    // ------------------------------------------------------------------

    @Test
    void patchDeactivateRunsTheHardLockCascade() throws Exception {
        String address = "leaver.person@company.example";
        String accessToken = invitedAccountToken(address, "Leaver Person");
        UUID userId = userIdOf(address);
        addTenantMember(tenantId, userId, "TENANT_TESTER");
        String pat = createPat(accessToken);

        // Live before: password login, PAT, and the ACTIVE membership.
        assertThat(loginStatus(address, "correct-horse-battery-1")).isEqualTo(200);
        assertThat(statusOf(HttpMethod.GET, "/api/v1/auth/me", pat)).isEqualTo(200);
        assertThat(membershipStatus(tenantId, userId)).isEqualTo("ACTIVE");

        String scimId = scimPost("/scim/v2/Users", entraUser("leaver-1", address), 201)
                .get("id")
                .asText();

        // Entra's PATCH shape: the string boolean "False".
        Map<String, Object> patch = Map.of(
                "schemas", List.of(PATCH_SCHEMA),
                "Operations", List.of(Map.of("op", "replace", "path", "active", "value", "False")));
        JsonNode patched = scimPatch("/scim/v2/Users/" + scimId, patch, 200);
        assertThat(patched.get("active").asBoolean()).isFalse();

        // The hard lock: account LOCKED, login refused, PAT refused,
        // the tenant membership suspended — immediately.
        assertThat(jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String.class, userId))
                .isEqualTo("LOCKED");
        assertThat(loginStatus(address, "correct-horse-battery-1")).isEqualTo(401);
        assertThat(statusOf(HttpMethod.GET, "/api/v1/auth/me", pat)).isEqualTo(401);
        assertThat(membershipStatus(tenantId, userId)).isEqualTo("SUSPENDED");

        AuditEvent deactivation =
                audit.eventsOfType(AuditEventType.SCIM_USER_DEACTIVATED).getLast();
        assertThat(deactivation.orgId()).isEqualTo(orgId);
        assertThat(deactivation.detail().get("suspendedMemberships")).isEqualTo("1");
        assertThat(deactivation.detail().get("externalId")).isEqualTo("leaver-1");
    }

    @Test
    void deleteDeactivatesIdempotently() throws Exception {
        String scimId = scimPost("/scim/v2/Users", entraUser("delete-1", "delete.one@company.example"), 201)
                .get("id")
                .asText();
        UUID userId = userIdOf("delete.one@company.example");

        assertThat(scimStatus(HttpMethod.DELETE, "/scim/v2/Users/" + scimId, scimToken))
                .isEqualTo(204);
        assertThat(jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String.class, userId))
                .isEqualTo("LOCKED");
        assertThat(jdbc.queryForObject(
                        "SELECT active FROM scim_users WHERE organization_id = ? AND external_id = ?",
                        Boolean.class,
                        orgId,
                        "delete-1"))
                .isFalse();

        // The second DELETE is idempotent (still 204, still parked).
        assertThat(scimStatus(HttpMethod.DELETE, "/scim/v2/Users/" + scimId, scimToken))
                .isEqualTo(204);

        // The row is never deleted: the SCIM id stays stable.
        assertThat(scimGet("/scim/v2/Users/" + scimId, scimToken, 200)
                        .get("active")
                        .asBoolean())
                .isFalse();
    }

    @Test
    void deprovisionDeactivatesALinkedTesterRecord() throws Exception {
        String address = "tester.person@company.example";
        String accessToken = invitedAccountToken(address, "Tester Person");
        UUID userId = userIdOf(address);
        scimPost("/scim/v2/Users", entraUser("tester-leaver-1", address), 201);
        // A linked tester record seeded directly (the accept flow's
        // end state): ACTIVE person, owned by this organization.
        jdbc.update(
                "INSERT INTO testers (id, organization_id, email, full_name, status, user_id, created_at, "
                        + "updated_at) VALUES (?, ?, ?, ?, 'ACTIVE', ?, now(), now())",
                UUID.randomUUID(),
                orgId,
                address,
                "Tester Person",
                userId);

        String scimId = scimGetFiltered("externalId eq \"tester-leaver-1\"", 200)
                .get("resources")
                .get(0)
                .get("id")
                .asText();
        scimStatus(HttpMethod.DELETE, "/scim/v2/Users/" + scimId, scimToken);

        assertThat(jdbc.queryForObject("SELECT status FROM testers WHERE user_id = ?", String.class, userId))
                .isEqualTo("DISABLED");
        assertThat(audit.eventsOfType(AuditEventType.TESTER_DEACTIVATED)).isNotEmpty();
    }

    // ------------------------------------------------------------------
    // Re-provision and group sync
    // ------------------------------------------------------------------

    @Test
    void reprovisionRestoresTheAccountAndReDerivesFromCurrentGroups() throws Exception {
        String scimId = scimPost("/scim/v2/Users", entraUser("reprovision-1", "back.person@company.example"), 201)
                .get("id")
                .asText();
        UUID userId = userIdOf("back.person@company.example");

        // The user joins the mapped Developers group: role DEVELOPER
        // and the mapped tenant membership arrive.
        scimPost("/scim/v2/Groups", group("Developers", "group-ext-1", List.of(scimId)), 201);
        assertThat(orgRoleOf(userId)).isEqualTo("DEVELOPER");
        assertThat(membershipStatus(tenantId, userId)).isEqualTo("ACTIVE");

        // Leaver, then re-provision with the SAME externalId.
        scimStatus(HttpMethod.DELETE, "/scim/v2/Users/" + scimId, scimToken);
        assertThat(orgRoleOf(userId)).isEqualTo("DEVELOPER");
        JsonNode reactivated =
                scimPost("/scim/v2/Users", entraUser("reprovision-1", "back.person@company.example"), 200);
        assertThat(reactivated.get("id").asText()).isEqualTo(scimId);
        assertThat(reactivated.get("active").asBoolean()).isTrue();

        // The account is ACTIVE again and the CURRENT group state
        // re-derived: role and tenant membership back.
        assertThat(jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String.class, userId))
                .isEqualTo("ACTIVE");
        assertThat(orgRoleOf(userId)).isEqualTo("DEVELOPER");
        assertThat(membershipStatus(tenantId, userId)).isEqualTo("ACTIVE");
        assertThat(audit.eventsOfType(AuditEventType.SCIM_USER_REACTIVATED)).isNotEmpty();
    }

    @Test
    void groupSyncDrivesAssignmentsPerTheMappingRules() throws Exception {
        JsonNode first = scimPost("/scim/v2/Users", entraUser("group-a", "group.a@company.example"), 201);
        JsonNode second = scimPost("/scim/v2/Users", entraUser("group-b", "group.b@company.example"), 201);
        String firstId = first.get("id").asText();
        String secondId = second.get("id").asText();
        UUID firstUserId = userIdOf("group.a@company.example");
        UUID secondUserId = userIdOf("group.b@company.example");

        // Group create with two members: both re-derive.
        JsonNode group =
                scimPost("/scim/v2/Groups", group("Developers", "dev-group-1", List.of(firstId, secondId)), 201);
        assertThat(group.get("members")).hasSize(2);
        assertThat(orgRoleOf(firstUserId)).isEqualTo("DEVELOPER");
        assertThat(orgRoleOf(secondUserId)).isEqualTo("DEVELOPER");
        assertThat(membershipStatus(tenantId, firstUserId)).isEqualTo("ACTIVE");
        assertThat(membershipStatus(tenantId, secondUserId)).isEqualTo("ACTIVE");
        assertThat(audit.eventsOfType(AuditEventType.SCIM_GROUP_SYNCED)
                        .getFirst()
                        .detail()
                        .get("membersAdded"))
                .isEqualTo("2");

        // GET user renders the derived groups (read-only).
        JsonNode rendered = scimGet("/scim/v2/Users/" + firstId, scimToken, 200);
        assertThat(rendered.get("groups").get(0).get("display").asText()).isEqualTo("Developers");

        // Okta's remove-with-filter-path PATCH: the second member
        // leaves the group. Their role stays (absence never demotes)
        // and the granted membership stays (granting is one-directional
        // by design), but the group no longer lists them.
        Map<String, Object> removeMember = Map.of(
                "schemas", List.of(PATCH_SCHEMA),
                "Operations", List.of(Map.of("op", "remove", "path", "members[value eq \"" + secondId + "\"]")));
        JsonNode patched = scimPatch("/scim/v2/Groups/" + group.get("id").asText(), removeMember, 200);
        assertThat(patched.get("members")).hasSize(1);
        assertThat(orgRoleOf(secondUserId)).isEqualTo("DEVELOPER");

        // Entra's value-array add: the second member rejoins.
        Map<String, Object> addMember = Map.of(
                "schemas",
                List.of(PATCH_SCHEMA),
                "Operations",
                List.of(Map.of("op", "add", "path", "members", "value", List.of(Map.of("value", secondId)))));
        scimPatch("/scim/v2/Groups/" + group.get("id").asText(), addMember, 200);
        assertThat(scimGet("/scim/v2/Groups/" + group.get("id").asText(), scimToken, 200)
                        .get("members"))
                .hasSize(2);

        // PUT replaces the membership wholesale: only the first member
        // remains declared.
        Map<String, Object> replace = group("Developers", "dev-group-1", List.of(firstId));
        JsonNode replaced = scimPut("/scim/v2/Groups/" + group.get("id").asText(), replace, 200);
        assertThat(replaced.get("members")).hasSize(1);

        // A member id that was never provisioned is refused loudly.
        Map<String, Object> bogus =
                group("Developers", "dev-group-1", List.of(UUID.randomUUID().toString()));
        assertThat(scimStatusBody(
                        HttpMethod.PUT, "/scim/v2/Groups/" + group.get("id").asText(), bogus))
                .isEqualTo(400);

        // Group DELETE removes the memberships and re-derives.
        assertThat(scimStatus(
                        HttpMethod.DELETE, "/scim/v2/Groups/" + group.get("id").asText(), scimToken))
                .isEqualTo(204);
        assertThat(scimGet("/scim/v2/Users/" + firstId, scimToken, 200).get("groups"))
                .isEmpty();
        // The granted assignments stay (the documented one-directional
        // posture); the group listing no longer carries the resource.
        assertThat(scimGet("/scim/v2/Groups", scimToken, 200)
                        .get("totalResults")
                        .asInt())
                .isEqualTo(0);
    }

    @Test
    void groupCreateIsIdempotentOnExternalIdAndConflictsOnDisplayName() throws Exception {
        JsonNode first = scimPost("/scim/v2/Groups", group("Platform Team", "group-ext-9", List.of()), 201);
        JsonNode replay = scimPost("/scim/v2/Groups", group("Platform Team Renamed", "group-ext-9", List.of()), 200);
        assertThat(replay.get("id").asText()).isEqualTo(first.get("id").asText());
        assertThat(replay.get("displayName").asText()).isEqualTo("Platform Team Renamed");

        JsonNode conflict = scimPost("/scim/v2/Groups", group("Platform Team Renamed", "group-ext-8", List.of()), 409);
        assertThat(conflict.get("scimType").asText()).isEqualTo("uniqueness");
    }

    // ------------------------------------------------------------------
    // PATCH shape variance beyond the Entra path form
    // ------------------------------------------------------------------

    @Test
    void oktaShapedPatchTogglesActiveWithTheValueObjectShape() throws Exception {
        String scimId = scimPost("/scim/v2/Users", entraUser("okta-patch-1", "okta.patch@company.example"), 201)
                .get("id")
                .asText();
        UUID userId = userIdOf("okta.patch@company.example");

        // Okta's shape: a blank path with the whole value object.
        Map<String, Object> deactivate = Map.of(
                "schemas",
                List.of(PATCH_SCHEMA),
                "Operations",
                List.of(Map.of("op", "replace", "value", Map.of("active", false))));
        JsonNode deactivated = scimPatch("/scim/v2/Users/" + scimId, deactivate, 200);
        assertThat(deactivated.get("active").asBoolean()).isFalse();
        assertThat(jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String.class, userId))
                .isEqualTo("LOCKED");

        // The same shape re-provisions: PATCH active=true is the
        // re-provision leg, not only a fresh create replay.
        Map<String, Object> reactivate = Map.of(
                "schemas",
                List.of(PATCH_SCHEMA),
                "Operations",
                List.of(Map.of("op", "replace", "value", Map.of("active", true))));
        JsonNode reactivated = scimPatch("/scim/v2/Users/" + scimId, reactivate, 200);
        assertThat(reactivated.get("active").asBoolean()).isTrue();
        assertThat(reactivated.get("id").asText()).isEqualTo(scimId);
        assertThat(jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String.class, userId))
                .isEqualTo("ACTIVE");
        assertThat(audit.eventsOfType(AuditEventType.SCIM_USER_REACTIVATED)).isNotEmpty();
    }

    @Test
    void userRemoveOperationsAndEmptyPatchBodiesAreRefusedLoudly() throws Exception {
        String scimId = scimPost("/scim/v2/Users", entraUser("patch-refuse-1", "patch.refuse@company.example"), 201)
                .get("id")
                .asText();

        // remove is refused loudly: this subset has no removable
        // attribute, and a quiet acceptance would lie about state.
        Map<String, Object> remove = Map.of(
                "schemas", List.of(PATCH_SCHEMA), "Operations", List.of(Map.of("op", "remove", "path", "active")));
        JsonNode refused = scimPatch("/scim/v2/Users/" + scimId, remove, 400);
        assertThat(refused.get("scimType").asText()).isEqualTo("invalidPath");

        // An empty Operations array is a malformed PATCH, not a no-op.
        Map<String, Object> empty = Map.of("schemas", List.of(PATCH_SCHEMA), "Operations", List.of());
        JsonNode malformed = scimPatch("/scim/v2/Users/" + scimId, empty, 400);
        assertThat(malformed.get("scimType").asText()).isEqualTo("invalidSyntax");

        // Nothing changed on the resource.
        assertThat(scimGet("/scim/v2/Users/" + scimId, scimToken, 200)
                        .get("active")
                        .asBoolean())
                .isTrue();
    }

    // ------------------------------------------------------------------
    // The complete leaver cascade: all four legs on ONE user
    // ------------------------------------------------------------------

    @Test
    void patchDeactivateRunsTheCompleteCascadeOnALinkedTester() throws Exception {
        String address = "full.cascade@company.example";
        String accessToken = invitedAccountToken(address, "Full Cascade");
        UUID userId = userIdOf(address);
        addTenantMember(tenantId, userId, "TENANT_TESTER");
        String pat = createPat(accessToken);
        UUID testerId = seedLinkedTester(address, userId);

        assertThat(loginStatus(address, "correct-horse-battery-1")).isEqualTo(200);
        assertThat(statusOf(HttpMethod.GET, "/api/v1/auth/me", pat)).isEqualTo(200);
        assertThat(membershipStatus(tenantId, userId)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject(
                        "SELECT status FROM tenant_tester_memberships WHERE tenant_id = ? AND tester_id = ?",
                        String.class,
                        tenantId,
                        testerId))
                .isEqualTo("ACTIVE");

        String scimId = scimPost("/scim/v2/Users", entraUser("cascade-1", address), 201)
                .get("id")
                .asText();
        Map<String, Object> patch = Map.of(
                "schemas",
                List.of(PATCH_SCHEMA),
                "Operations",
                List.of(Map.of("op", "replace", "value", Map.of("active", false))));
        scimPatch("/scim/v2/Users/" + scimId, patch, 200);

        // All four legs, one deprovision: (1) login blocked.
        assertThat(jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String.class, userId))
                .isEqualTo("LOCKED");
        assertThat(loginStatus(address, "correct-horse-battery-1")).isEqualTo(401);
        // (2) the PAT is dead at its next use.
        assertThat(statusOf(HttpMethod.GET, "/api/v1/auth/me", pat)).isEqualTo(401);
        // (3) every tenant membership suspended.
        assertThat(membershipStatus(tenantId, userId)).isEqualTo("SUSPENDED");
        // (4) the linked tester record and its memberships down.
        assertThat(jdbc.queryForObject("SELECT status FROM testers WHERE id = ?", String.class, testerId))
                .isEqualTo("DISABLED");
        assertThat(jdbc.queryForObject(
                        "SELECT status FROM tenant_tester_memberships WHERE tenant_id = ? AND tester_id = ?",
                        String.class,
                        tenantId,
                        testerId))
                .isEqualTo("SUSPENDED");

        AuditEvent deactivation =
                audit.eventsOfType(AuditEventType.SCIM_USER_DEACTIVATED).getLast();
        assertThat(deactivation.detail().get("testerDeactivated")).isEqualTo("true");
        assertThat(deactivation.detail().get("suspendedMemberships")).isEqualTo("1");
    }

    @Test
    void reprovisionReactivatesTheTesterRecordButNotItsMemberships() throws Exception {
        String address = "doctrine.person@company.example";
        String accessToken = invitedAccountToken(address, "Doctrine Person");
        UUID userId = userIdOf(address);
        UUID testerId = seedLinkedTester(address, userId);

        String scimId = scimPost("/scim/v2/Users", entraUser("doctrine-1", address), 201)
                .get("id")
                .asText();
        scimStatus(HttpMethod.DELETE, "/scim/v2/Users/" + scimId, scimToken);
        assertThat(jdbc.queryForObject("SELECT status FROM testers WHERE id = ?", String.class, testerId))
                .isEqualTo("DISABLED");
        assertThat(jdbc.queryForObject(
                        "SELECT status FROM tenant_tester_memberships WHERE tenant_id = ? AND tester_id = ?",
                        String.class,
                        tenantId,
                        testerId))
                .isEqualTo("SUSPENDED");

        // Re-provision: the tester RECORD returns (the IdP's onboarding
        // signal is authoritative) but its memberships stay suspended —
        // the S6-04 manual-restoration doctrine, no silent undo.
        JsonNode reactivated = scimPost("/scim/v2/Users", entraUser("doctrine-1", address), 200);
        assertThat(reactivated.get("id").asText()).isEqualTo(scimId);
        assertThat(jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String.class, userId))
                .isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT status FROM testers WHERE id = ?", String.class, testerId))
                .isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject(
                        "SELECT status FROM tenant_tester_memberships WHERE tenant_id = ? AND tester_id = ?",
                        String.class,
                        tenantId,
                        testerId))
                .as("the tester membership stays SUSPENDED until manually restored")
                .isEqualTo("SUSPENDED");
    }

    // ------------------------------------------------------------------
    // The concurrent upsert race on externalId
    // ------------------------------------------------------------------

    @Test
    void concurrentCreatesWithTheSameExternalIdResolveIdempotently() throws Exception {
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String[]>> answers = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                answers.add(pool.submit(() -> {
                    start.await();
                    return client.post()
                            .uri("/scim/v2/Users")
                            .header("Authorization", "Bearer " + scimToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(entraUser("race-external-1", "race.person@company.example"))
                            .exchange((request, response) -> {
                                String text = new String(response.getBody().readAllBytes());
                                JsonNode body = text.isBlank() ? mapper.nullNode() : mapper.readTree(text);
                                return new String[] {
                                    Integer.toString(response.getStatusCode().value()),
                                    body.hasNonNull("id") ? body.get("id").asText() : ""
                                };
                            });
                }));
            }
            start.countDown();
            List<String> ids = new ArrayList<>();
            List<String> statuses = new ArrayList<>();
            for (Future<String[]> answer : answers) {
                String[] result = answer.get(60, TimeUnit.SECONDS);
                statuses.add(result[0]);
                if (!result[1].isBlank()) {
                    ids.add(result[1]);
                }
            }
            assertThat(statuses)
                    .as("every concurrent duplicate resolves as the idempotent 200/201 pair, never a 500")
                    .containsOnly("200", "201");
            assertThat(ids).as("every answer carries the SAME stable SCIM id").hasSize(threads);
            assertThat(ids.stream().distinct().toList()).hasSize(1);
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(10, TimeUnit.SECONDS);
        }

        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM scim_users WHERE organization_id = ? AND external_id = ?",
                        Integer.class,
                        orgId,
                        "race-external-1"))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM users WHERE email = ?", Integer.class, "race.person@company.example"))
                .isEqualTo(1);
    }

    @Test
    void concurrentGroupCreatesWithTheSameExternalIdResolveIdempotently() throws Exception {
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String[]>> answers = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                answers.add(pool.submit(() -> {
                    start.await();
                    return client.post()
                            .uri("/scim/v2/Groups")
                            .header("Authorization", "Bearer " + scimToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(group("Race Group", "race-group-ext-1", List.of()))
                            .exchange((request, response) -> {
                                String text = new String(response.getBody().readAllBytes());
                                JsonNode body = text.isBlank() ? mapper.nullNode() : mapper.readTree(text);
                                return new String[] {
                                    Integer.toString(response.getStatusCode().value()),
                                    body.hasNonNull("id") ? body.get("id").asText() : ""
                                };
                            });
                }));
            }
            start.countDown();
            List<String> ids = new ArrayList<>();
            List<String> statuses = new ArrayList<>();
            for (Future<String[]> answer : answers) {
                String[] result = answer.get(60, TimeUnit.SECONDS);
                statuses.add(result[0]);
                if (!result[1].isBlank()) {
                    ids.add(result[1]);
                }
            }
            assertThat(statuses)
                    .as("every concurrent group push resolves as the idempotent 200/201 pair, never a 500")
                    .containsOnly("200", "201");
            assertThat(ids)
                    .as("every answer carries the SAME stable SCIM group id")
                    .hasSize(threads);
            assertThat(ids.stream().distinct().toList()).hasSize(1);
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(10, TimeUnit.SECONDS);
        }

        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM scim_groups WHERE organization_id = ? AND external_id = ?",
                        Integer.class,
                        orgId,
                        "race-group-ext-1"))
                .isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** The Entra provisioning shape: GUID externalId, UPN userName, enterprise extension block. */
    private static Map<String, Object> entraUser(String externalId, String userName) {
        Map<String, Object> user = new HashMap<>();
        user.put(
                "schemas",
                List.of(
                        "urn:ietf:params:scim:schemas:core:2.0:User",
                        "urn:ietf:params:scim:schemas:extension:enterprise:2.0:User"));
        if (externalId != null) {
            user.put("externalId", externalId);
        }
        if (userName != null) {
            user.put("userName", userName);
        }
        user.put("name", Map.of("givenName", "Ada", "familyName", "Lovelace", "formatted", "Ada Lovelace"));
        user.put("active", true);
        if (userName != null && userName.contains("@")) {
            user.put("emails", List.of(Map.of("value", userName, "type", "work", "primary", true)));
        }
        // The enterprise extension block: tolerated, ignored.
        user.put(
                "urn:ietf:params:scim:schemas:extension:enterprise:2.0:User",
                Map.of("employeeNumber", "721", "costCenter", "2410"));
        return user;
    }

    /** A group resource. */
    private static Map<String, Object> group(String displayName, String externalId, List<String> memberIds) {
        Map<String, Object> group = new HashMap<>();
        group.put("schemas", List.of("urn:ietf:params:scim:schemas:core:2.0:Group"));
        group.put("displayName", displayName);
        group.put("externalId", externalId);
        group.put("members", memberIds.stream().map(id -> Map.of("value", id)).toList());
        return group;
    }

    private JsonNode scimGet(String uri, String token, int expectedStatus) throws Exception {
        return exchange(HttpMethod.GET, uri, token, null, expectedStatus);
    }

    /** GET with the Authorization header set verbatim (the auth-failure shapes). */
    private JsonNode scimGetRawHeader(String uri, String header, int expectedStatus) throws Exception {
        RestClient.RequestHeadersSpec<?> spec = client.get().uri(uri);
        if (header != null) {
            spec = spec.header("Authorization", header);
        }
        return spec.exchange((request, response) -> {
            assertThat(response.getStatusCode().value()).isEqualTo(expectedStatus);
            return mapper.readTree(new String(response.getBody().readAllBytes()));
        });
    }

    /**
     * The filtered user listing, built through the URI builder so the
     * filter expression is encoded exactly once (a pre-encoded string
     * URI template re-encodes the percent sequences).
     */
    private JsonNode scimGetFiltered(String filter, int expectedStatus) throws Exception {
        return client.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/scim/v2/Users")
                        .queryParam("filter", filter)
                        .build())
                .headers(headers -> headers.set("Authorization", "Bearer " + scimToken))
                .exchange((request, response) -> {
                    assertThat(response.getStatusCode().value()).isEqualTo(expectedStatus);
                    return mapper.readTree(new String(response.getBody().readAllBytes()));
                });
    }

    private JsonNode scimPost(String uri, Object body, int expectedStatus) throws Exception {
        return exchange(HttpMethod.POST, uri, scimToken, body, expectedStatus);
    }

    private JsonNode scimPostRaw(String uri, String rawBody, int expectedStatus) throws Exception {
        return client.post()
                .uri(uri)
                .header("Authorization", "Bearer " + scimToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(rawBody)
                .exchange((request, response) -> {
                    assertThat(response.getStatusCode().value()).isEqualTo(expectedStatus);
                    return mapper.readTree(new String(response.getBody().readAllBytes()));
                });
    }

    private JsonNode scimPut(String uri, Object body, int expectedStatus) throws Exception {
        return exchange(HttpMethod.PUT, uri, scimToken, body, expectedStatus);
    }

    private JsonNode scimPatch(String uri, Object body, int expectedStatus) throws Exception {
        return exchange(HttpMethod.PATCH, uri, scimToken, body, expectedStatus);
    }

    private int scimStatus(HttpMethod method, String uri, String token) {
        return client.method(method)
                .uri(uri)
                .headers(headers -> {
                    if (token != null) {
                        headers.set("Authorization", "Bearer " + token);
                    }
                })
                .exchange((request, response) -> response.getStatusCode().value());
    }

    private int scimStatusBody(HttpMethod method, String uri, Object body) {
        return client.method(method)
                .uri(uri)
                .header("Authorization", "Bearer " + scimToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange((request, response) -> response.getStatusCode().value());
    }

    private JsonNode exchange(HttpMethod method, String uri, String token, Object body, int expectedStatus)
            throws Exception {
        RestClient.RequestBodySpec spec = client.method(method).uri(uri);
        if (token != null) {
            spec = spec.header("Authorization", "Bearer " + token);
        }
        if (body != null) {
            spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return spec.exchange((request, response) -> {
            assertThat(response.getStatusCode().value()).isEqualTo(expectedStatus);
            String text = new String(response.getBody().readAllBytes());
            return text.isBlank() ? mapper.nullNode() : mapper.readTree(text);
        });
    }

    private void putScimConfig(Map<String, Object> body) {
        putScimConfigFor(orgId, body);
    }

    private void putScimConfigFor(UUID organizationId, Map<String, Object> body) {
        client.put()
                .uri("/api/v1/organizations/" + organizationId + "/scim-config")
                .header("Authorization", "Bearer " + devToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange((request, response) -> {
                    assertThat(response.getStatusCode().value()).isEqualTo(200);
                    return null;
                });
    }

    private String generateScimToken() {
        return generateScimTokenFor(orgId);
    }

    private String generateScimTokenFor(UUID organizationId) {
        return client.post()
                .uri("/api/v1/organizations/" + organizationId + "/scim-config/token")
                .header("Authorization", "Bearer " + devToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("label", "test connector"))
                .exchange((request, response) -> {
                    assertThat(response.getStatusCode().value()).isEqualTo(200);
                    return mapper.readTree(new String(response.getBody().readAllBytes()))
                            .get("token")
                            .asText();
                });
    }

    /** Creates an invited account with a local password and answers its access token. */
    private String invitedAccountToken(String address, String fullName) {
        JsonNode invitation = client.post()
                .uri("/api/v1/auth/invitations")
                .header("Authorization", "Bearer " + devToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", address, "organizationId", orgId.toString(), "role", "ORG_MEMBER"))
                .retrieve()
                .body(JsonNode.class);
        client.post()
                .uri("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "invitationToken",
                        invitation.get("invitationToken").asText(),
                        "email",
                        address,
                        "password",
                        "correct-horse-battery-1",
                        "fullName",
                        fullName))
                .retrieve()
                .body(JsonNode.class);
        return login(address, "correct-horse-battery-1").get("accessToken").asText();
    }

    /** Creates a personal access token for the account and answers its plaintext. */
    private String createPat(String accessToken) {
        return client.post()
                .uri("/api/v1/auth/tokens")
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", "scim-leaver-test"))
                .exchange((request, response) -> {
                    assertThat(response.getStatusCode().value()).isEqualTo(201);
                    return mapper.readTree(new String(response.getBody().readAllBytes()))
                            .get("token")
                            .asText();
                });
    }

    private JsonNode login(String email, String password) {
        return client.post()
                .uri("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", email, "password", password))
                .exchange((request, response) -> {
                    assertThat(response.getStatusCode().value()).isEqualTo(200);
                    return mapper.readTree(new String(response.getBody().readAllBytes()));
                });
    }

    private int loginStatus(String email, String password) {
        return client.post()
                .uri("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", email, "password", password))
                .exchange((request, response) -> response.getStatusCode().value());
    }

    private int statusOf(HttpMethod method, String uri, String token) {
        return client.method(method)
                .uri(uri)
                .headers(headers -> {
                    if (token != null) {
                        headers.set("Authorization", "Bearer " + token);
                    }
                })
                .exchange((request, response) -> response.getStatusCode().value());
    }

    /** Seeds a linked tester record plus an ACTIVE tester membership (the accept flow's end state). */
    private UUID seedLinkedTester(String address, UUID userId) {
        UUID testerId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO testers (id, organization_id, email, full_name, status, user_id, created_at, "
                        + "updated_at) VALUES (?, ?, ?, ?, 'ACTIVE', ?, now(), now())",
                testerId,
                orgId,
                address,
                "Linked Tester",
                userId);
        jdbc.update(
                "INSERT INTO tenant_tester_memberships (tenant_id, tester_id, status, created_at, updated_at) "
                        + "VALUES (?, ?, 'ACTIVE', now(), now())",
                tenantId,
                testerId);
        return testerId;
    }

    private void addTenantMember(UUID tenant, UUID userId, String role) {
        client.post()
                .uri("/api/v1/tenants/" + tenant + "/members")
                .header("Authorization", "Bearer " + devToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("userId", userId.toString(), "role", role))
                .exchange((request, response) -> {
                    assertThat(response.getStatusCode().value()).isEqualTo(201);
                    return null;
                });
    }

    private String membershipStatus(UUID tenant, UUID userId) {
        return jdbc.queryForObject(
                "SELECT status FROM tenant_members WHERE tenant_id = ? AND user_id = ?", String.class, tenant, userId);
    }

    private String orgRoleOf(UUID userId) {
        return jdbc.queryForObject(
                "SELECT role FROM organization_members WHERE organization_id = ? AND user_id = ?",
                String.class,
                orgId,
                userId);
    }

    private UUID userIdOf(String email) {
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, email);
    }

    private String devToken() {
        return login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD).get("accessToken").asText();
    }

    private UUID createOrganization(String name) {
        JsonNode organization = client.post()
                .uri("/api/v1/organizations")
                .header("Authorization", "Bearer " + devToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", name))
                .retrieve()
                .body(JsonNode.class);
        return UUID.fromString(organization.get("id").asText());
    }

    private UUID createTenant(UUID organizationId, String name) {
        JsonNode tenant = client.post()
                .uri("/api/v1/tenants")
                .header("Authorization", "Bearer " + devToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "organizationId",
                        organizationId.toString(),
                        "name",
                        name,
                        "externalId",
                        "scim-ext-" + UUID.randomUUID()))
                .retrieve()
                .body(JsonNode.class);
        return UUID.fromString(tenant.get("id").asText());
    }
}
