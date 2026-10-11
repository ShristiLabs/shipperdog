package com.shipperdog.api.sso;

import static org.assertj.core.api.Assertions.assertThat;

import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.identity.AuditCapture;
import com.shipperdog.ssofixture.MockOidcProvider;
import com.shipperdog.ssofixture.MockOidpAccess;
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
 * End-to-end acceptance flow for issue #67 (S14-01) against a real
 * PostgreSQL, HTTP stack, and the contract-faithful mock OIDC provider:
 * the full authorization-code + PKCE browser dance (options, authorize
 * redirect, provider callback, one-time session-code exchange), the
 * provisioning/linkage policies, group-claim mapping, the IdP-inactive
 * lockout, back-channel logout, and the spec-shaped token attacks the
 * verification must refuse.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
            "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef"
        })
@TestPropertySource(properties = "shipperdog.sso.redirect-base-uri=http://sso-test-api.local")
@Testcontainers
@Import({AuditCapture.class, MockOidcProvider.class, MockOidpAccess.class})
class SsoFlowIntegrationTest {

    private static final String DEV_ADMIN_EMAIL = "dev@shipperdog.local";
    private static final String DEV_ADMIN_PASSWORD = "shipperdog-dev-admin";
    private static final String CONSOLE_CALLBACK = "http://localhost:5173/sso/callback";

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
    private MockOidcProvider idp;

    private RestClient client;

    @BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
        audit.reset();
        idp.reset();
        idp.announcePort(port);
    }

    // ------------------------------------------------------------------
    // The happy path and the options probe
    // ------------------------------------------------------------------

    @Test
    void fullFlowLinksInvitedMemberAndIssuesLocalSession() throws Exception {
        String devToken = devToken();
        UUID orgId = createOrganization(devToken, "Acme SSO");
        UUID aliceId = inviteRegisterAndLogin(devToken, orgId, "alice@acme.example", "ORG_MEMBER");
        putConfig(devToken, orgId, Map.of("provisioningMode", "INVITED_ONLY", "enabled", true));

        idp.behave().email = "alice@acme.example";
        String callback = completeBrowserDance(orgId);

        assertThat(callback).startsWith(CONSOLE_CALLBACK + "?code=");
        String sessionCode = callback.substring(callback.indexOf("code=") + "code=".length());
        JsonNode tokens = exchange(sessionCode, 200);
        assertThat(tokens.get("accessToken").asText()).isNotBlank();
        assertThat(tokens.get("refreshToken").asText()).isNotBlank();

        JsonNode me = client.get()
                .uri("/api/v1/auth/me")
                .header("Authorization", "Bearer " + tokens.get("accessToken").asText())
                .retrieve()
                .body(JsonNode.class);
        assertThat(me.get("id").asText()).isEqualTo(aliceId.toString());
        assertThat(me.get("authenticatedVia").asText()).isEqualTo("jwt");

        Map<String, Object> link =
                jdbc.queryForMap("SELECT user_id, link_kind FROM user_idp_identities WHERE organization_id = ?", orgId);
        assertThat(link.get("user_id")).isEqualTo(aliceId);
        assertThat(link.get("link_kind")).isEqualTo("EMAIL");
        assertThat(audit.eventsOfType(AuditEventType.SSO_LOGIN_SUCCESS)).hasSize(1);
        assertThat(audit.eventsOfType(AuditEventType.SSO_IDENTITY_LINKED)).hasSize(1);
    }

    @Test
    void optionsRouteAnswersUniformlyForUnknownUnconfiguredAndDisabled() throws Exception {
        String devToken = devToken();
        UUID orgId = createOrganization(devToken, "Beta SSO");

        assertThat(statusOf(HttpMethod.GET, "/api/v1/auth/sso/options?organizationId=" + UUID.randomUUID(), null))
                .isEqualTo(404);
        assertThat(statusOf(HttpMethod.GET, "/api/v1/auth/sso/options?organizationId=" + orgId, null))
                .as("unconfigured organization answers the same miss")
                .isEqualTo(404);

        putConfig(devToken, orgId, Map.of("enabled", false));
        assertThat(statusOf(HttpMethod.GET, "/api/v1/auth/sso/options?organizationId=" + orgId, null))
                .as("disabled configuration answers the same miss")
                .isEqualTo(404);
        assertThat(statusOf(HttpMethod.GET, "/api/v1/auth/sso/authorize?organizationId=" + orgId, null))
                .isEqualTo(404);

        putConfig(devToken, orgId, Map.of("enabled", true));
        assertThat(statusOf(HttpMethod.GET, "/api/v1/auth/sso/options?organizationId=" + orgId, null))
                .isEqualTo(200);
    }

    // ------------------------------------------------------------------
    // Provisioning and linkage policy
    // ------------------------------------------------------------------

    @Test
    void unverifiedEmailIsRejectedUnderTheStrictDefault() throws Exception {
        String devToken = devToken();
        UUID orgId = createOrganization(devToken, "Gamma SSO");
        putConfig(devToken, orgId, Map.of("enabled", true));

        idp.behave().email = "someone@gamma.example";
        idp.behave().emailVerified = false;

        assertThat(completeBrowserDance(orgId)).isEqualTo(CONSOLE_CALLBACK + "?error=sso");
        assertThat(failureReason()).isEqualTo("unverified_email");
        assertThat(identityCount(orgId)).isZero();
    }

    @Test
    void invitedOnlyRefusesUnknownEmails() throws Exception {
        String devToken = devToken();
        UUID orgId = createOrganization(devToken, "Delta SSO");
        putConfig(devToken, orgId, Map.of("provisioningMode", "INVITED_ONLY", "enabled", true));

        idp.behave().email = "stranger@delta.example";

        assertThat(completeBrowserDance(orgId)).isEqualTo(CONSOLE_CALLBACK + "?error=sso");
        assertThat(failureReason()).isEqualTo("not_invited");
        assertThat(identityCount(orgId)).isZero();
    }

    @Test
    void jitProvisionsPasswordlessAccountWithLeastPrivilegeDefaultRole() throws Exception {
        String devToken = devToken();
        UUID orgId = createOrganization(devToken, "Epsilon SSO");
        putConfig(devToken, orgId, Map.of("provisioningMode", "JIT", "enabled", true));

        idp.behave().email = "new.hire@epsilon.example";
        idp.behave().name = "New Hire";
        String callback = completeBrowserDance(orgId);
        assertThat(callback).startsWith(CONSOLE_CALLBACK + "?code=");

        Map<String, Object> user = jdbc.queryForMap(
                "SELECT id, password_hash, status, full_name FROM users WHERE email = ?", "new.hire@epsilon.example");
        assertThat(user.get("password_hash")).isNull();
        assertThat(user.get("status")).isEqualTo("ACTIVE");
        assertThat(user.get("full_name")).isEqualTo("New Hire");

        String role = jdbc.queryForObject(
                "SELECT role::text FROM organization_members WHERE organization_id = ? AND user_id = ?",
                String.class,
                orgId,
                user.get("id"));
        assertThat(role).as("JIT default role is the least-privilege VIEWER").isEqualTo("VIEWER");

        assertThat(audit.eventsOfType(AuditEventType.SSO_USER_JIT_PROVISIONED)).hasSize(1);
        assertThat(audit.eventsOfType(AuditEventType.SSO_IDENTITY_LINKED))
                .singleElement()
                .extracting(event -> event.detail().get("linkKind"))
                .isEqualTo("JIT");

        // The provisioned account is password-less: local password login
        // is uniformly refused (and does not throw on the null hash).
        assertThat(statusOf(
                        HttpMethod.POST,
                        "/api/v1/auth/login",
                        null,
                        Map.of(
                                "email", "new.hire@epsilon.example",
                                "password", "any-guess-123456")))
                .isEqualTo(401);
    }

    @Test
    void jitAdmitsExistingNonMemberAccountsByEmail() throws Exception {
        String devToken = devToken();
        UUID orgId = createOrganization(devToken, "Zeta SSO");
        UUID outsiderId = inviteRegisterAndLogin(devToken, null, "moved.in@zeta.example", null);
        putConfig(devToken, orgId, Map.of("provisioningMode", "JIT", "enabled", true));

        idp.behave().email = "moved.in@zeta.example";
        assertThat(completeBrowserDance(orgId)).startsWith(CONSOLE_CALLBACK + "?code=");

        String role = jdbc.queryForObject(
                "SELECT role::text FROM organization_members WHERE organization_id = ? AND user_id = ?",
                String.class,
                orgId,
                outsiderId);
        assertThat(role).isEqualTo("VIEWER");
    }

    @Test
    void platformAdminsNeverAuthenticateThroughSso() throws Exception {
        String devToken = devToken();
        UUID orgId = createOrganization(devToken, "Eta SSO");
        putConfig(devToken, orgId, Map.of("enabled", true));

        idp.behave().email = DEV_ADMIN_EMAIL;

        assertThat(completeBrowserDance(orgId)).isEqualTo(CONSOLE_CALLBACK + "?error=sso");
        assertThat(failureReason()).isEqualTo("platform_admin_breakglass");
    }

    // ------------------------------------------------------------------
    // Group-claim mapping
    // ------------------------------------------------------------------

    @Test
    void groupClaimMappingGrantsAndReappliesRolesOnLogin() throws Exception {
        String devToken = devToken();
        UUID orgId = createOrganization(devToken, "Theta SSO");
        putConfig(
                devToken,
                orgId,
                Map.of(
                        "provisioningMode",
                        "JIT",
                        "enabled",
                        true,
                        "groupClaimName",
                        "groups",
                        "roleMappings",
                        Map.of("eng-devs", "DEVELOPER")));

        idp.behave().email = "dev.theta@theta.example";
        idp.behave().groups = List.of("everyone", "eng-devs");
        assertThat(completeBrowserDance(orgId)).startsWith(CONSOLE_CALLBACK + "?code=");

        UUID userId = jdbc.queryForObject(
                "SELECT user_id FROM user_idp_identities WHERE organization_id = ?", UUID.class, orgId);
        assertThat(membershipRole(orgId, userId)).isEqualTo("DEVELOPER");

        // Re-login after the mapping table moved the group's meaning:
        // the linked subject's role follows the mapping (audited).
        audit.reset();
        putConfig(
                devToken,
                orgId,
                Map.of(
                        "provisioningMode",
                        "JIT",
                        "enabled",
                        true,
                        "groupClaimName",
                        "groups",
                        "roleMappings",
                        Map.of("eng-devs", "QA_ENGINEER")));
        idp.reset();
        idp.behave().email = "dev.theta@theta.example";
        idp.behave().groups = List.of("eng-devs");
        assertThat(completeBrowserDance(orgId)).startsWith(CONSOLE_CALLBACK + "?code=");

        assertThat(membershipRole(orgId, userId)).isEqualTo("QA_ENGINEER");
        AuditEvent mapping = audit.eventsOfType(AuditEventType.SSO_ROLE_MAPPED).getFirst();
        assertThat(mapping.detail())
                .containsEntry("role.before", "DEVELOPER")
                .containsEntry("role.after", "QA_ENGINEER");
        assertThat(audit.eventsOfType(AuditEventType.ORG_MEMBER_ROLE_CHANGED)).isNotEmpty();
    }

    // ------------------------------------------------------------------
    // Lockout and logout
    // ------------------------------------------------------------------

    @Test
    void idpInactiveClaimLocksAccountAndEndsExistingSessions() throws Exception {
        String devToken = devToken();
        UUID orgId = createOrganization(devToken, "Iota SSO");
        putConfig(
                devToken,
                orgId,
                Map.of(
                        "provisioningMode", "JIT",
                        "enabled", true,
                        "inactiveClaimName", "sd_inactive"));

        idp.behave().email = "offboarding@iota.example";
        String callback = completeBrowserDance(orgId);
        String sessionCode = callback.substring(callback.indexOf("code=") + "code=".length());
        JsonNode tokens = exchange(sessionCode, 200);
        String refreshToken = tokens.get("refreshToken").asText();
        assertThat(statusOf(HttpMethod.POST, "/api/v1/auth/refresh", null, Map.of("refreshToken", refreshToken)))
                .isEqualTo(200);

        audit.reset();
        idp.reset();
        idp.announcePort(port);
        idp.behave().email = "offboarding@iota.example";
        idp.behave().extraClaims = Map.of("sd_inactive", true);

        assertThat(completeBrowserDance(orgId)).isEqualTo(CONSOLE_CALLBACK + "?error=sso");
        assertThat(failureReason()).isEqualTo("idp_inactive");

        UUID userId = jdbc.queryForObject(
                "SELECT user_id FROM user_idp_identities WHERE organization_id = ?", UUID.class, orgId);
        assertThat(jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String.class, userId))
                .isEqualTo("LOCKED");
        assertThat(audit.eventsOfType(AuditEventType.SSO_ACCOUNT_LOCKED)).hasSize(1);
        assertThat(statusOf(HttpMethod.POST, "/api/v1/auth/refresh", null, Map.of("refreshToken", refreshToken)))
                .as("the lock revoked every refresh family")
                .isEqualTo(401);
    }

    @Test
    void backChannelLogoutEndsTheLocalSession() throws Exception {
        String devToken = devToken();
        UUID orgId = createOrganization(devToken, "Kappa SSO");
        putConfig(devToken, orgId, Map.of("provisioningMode", "JIT", "enabled", true));

        idp.behave().email = "leaving@kappa.example";
        String callback = completeBrowserDance(orgId);
        String refreshToken = exchange(callback.substring(callback.indexOf("code=") + 5), 200)
                .get("refreshToken")
                .asText();
        assertThat(statusOf(HttpMethod.POST, "/api/v1/auth/refresh", null, Map.of("refreshToken", refreshToken)))
                .isEqualTo(200);

        audit.reset();
        String logoutToken = idp.mintLogoutToken(idp.behave().subject);
        client.post()
                .uri("/api/v1/auth/sso/logout")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body("logout_token=" + logoutToken)
                .retrieve()
                .toBodilessEntity();
        assertThat(audit.eventsOfType(AuditEventType.SSO_LOGOUT)).hasSize(1);
        assertThat(statusOf(HttpMethod.POST, "/api/v1/auth/refresh", null, Map.of("refreshToken", refreshToken)))
                .as("the IdP's logout token ended the local session")
                .isEqualTo(401);
    }

    // ------------------------------------------------------------------
    // One-time guarantees and the spec-shaped attacks
    // ------------------------------------------------------------------

    @Test
    void stateIsSingleUseAndSessionCodesAreSingleUse() throws Exception {
        String devToken = devToken();
        UUID orgId = createOrganization(devToken, "Lambda SSO");
        putConfig(devToken, orgId, Map.of("provisioningMode", "JIT", "enabled", true));
        idp.behave().email = "replay@lambda.example";

        String callbackUrl = browserDanceUpToCallbackUrl(orgId);
        String location = followCallback(callbackUrl);
        assertThat(location).startsWith(CONSOLE_CALLBACK + "?code=");
        String sessionCode = location.substring(location.indexOf("code=") + 5);

        audit.reset();
        String replay = followCallback(callbackUrl);
        assertThat(replay).isEqualTo(CONSOLE_CALLBACK + "?error=sso");
        assertThat(failureReason()).isEqualTo("unknown_state");

        exchange(sessionCode, 200);
        assertThat(statusOf(HttpMethod.POST, "/api/v1/auth/sso/exchange", null, Map.of("code", sessionCode)))
                .as("a replayed session code answers the uniform 401")
                .isEqualTo(401);
    }

    @Test
    void es256TokensAreAccepted() throws Exception {
        String devToken = devToken();
        UUID orgId = createOrganization(devToken, "Mu SSO");
        putConfig(devToken, orgId, Map.of("provisioningMode", "JIT", "enabled", true));

        idp.behave().email = "ec.user@mu.example";
        idp.behave().alg = com.nimbusds.jose.JWSAlgorithm.ES256;

        assertThat(completeBrowserDance(orgId)).startsWith(CONSOLE_CALLBACK + "?code=");
        assertThat(audit.eventsOfType(AuditEventType.SSO_LOGIN_SUCCESS)).hasSize(1);
    }

    @Test
    void tokenAttacksAreRefusedUniformly() throws Exception {
        String devToken = devToken();
        UUID orgId = createOrganization(devToken, "Nu SSO");
        putConfig(devToken, orgId, Map.of("provisioningMode", "JIT", "enabled", true));

        List<Runnable> attacks = List.of(
                () -> idp.behave().alg = com.nimbusds.jose.JWSAlgorithm.HS256,
                () -> idp.behave().dropNonce = true,
                () -> idp.behave().audienceOverride = "some-other-client",
                () -> idp.behave().expiredToken = true,
                () -> idp.behave().rejectVerifier = true);
        for (Runnable attack : attacks) {
            idp.reset();
            idp.behave().email = "attack@nu.example";
            attack.run();
            audit.reset();
            assertThat(completeBrowserDance(orgId))
                    .as("attack %s refused", attack)
                    .isEqualTo(CONSOLE_CALLBACK + "?error=sso");
            assertThat(failureReason())
                    .as("the refusal reason is the verification/exchange leg, never a hint")
                    .isIn("token_verification", "provider_exchange");
        }
        assertThat(identityCount(orgId)).as("no attack minted an identity").isZero();
    }

    // ------------------------------------------------------------------
    // Refusal paths the golden suites do not reach (tester pass)
    // ------------------------------------------------------------------

    @Test
    void deactivatedOrganizationRefusesSso() throws Exception {
        String devToken = devToken();
        UUID orgId = createOrganization(devToken, "Xi SSO");
        putConfig(devToken, orgId, Map.of("provisioningMode", "JIT", "enabled", true));

        client.put()
                .uri("/api/v1/organizations/" + orgId)
                .header("Authorization", "Bearer " + devToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("status", "DEACTIVATED"))
                .retrieve()
                .body(JsonNode.class);
        idp.behave().email = "xi.user@xi.example";

        assertThat(completeBrowserDance(orgId)).isEqualTo(CONSOLE_CALLBACK + "?error=sso");
        assertThat(failureReason()).isEqualTo("organization_deactivated");
    }

    @Test
    void disabledAccountIsRefusedEvenWhenLinked() throws Exception {
        String devToken = devToken();
        UUID orgId = createOrganization(devToken, "Omicron SSO");
        putConfig(devToken, orgId, Map.of("provisioningMode", "JIT", "enabled", true));

        idp.behave().email = "disabled.user@omicron.example";
        assertThat(completeBrowserDance(orgId)).startsWith(CONSOLE_CALLBACK + "?code=");
        UUID userId = jdbc.queryForObject(
                "SELECT user_id FROM user_idp_identities WHERE organization_id = ?", UUID.class, orgId);

        jdbc.update("UPDATE users SET status = 'DISABLED' WHERE id = ?", userId);
        audit.reset();
        idp.reset();
        idp.announcePort(port);
        idp.behave().email = "disabled.user@omicron.example";

        assertThat(completeBrowserDance(orgId)).isEqualTo(CONSOLE_CALLBACK + "?error=sso");
        assertThat(failureReason()).isEqualTo("account_disabled");
    }

    @Test
    void expiredStateIsRefused() throws Exception {
        String devToken = devToken();
        UUID orgId = createOrganization(devToken, "Pi SSO");
        putConfig(devToken, orgId, Map.of("provisioningMode", "JIT", "enabled", true));
        idp.behave().email = "slow.browser@pi.example";

        String callbackUrl = browserDanceUpToCallbackUrl(orgId);
        jdbc.update("UPDATE sso_authorization_requests SET expires_at = now() - INTERVAL '1 minute'");

        assertThat(followCallback(callbackUrl)).isEqualTo(CONSOLE_CALLBACK + "?error=sso");
        assertThat(failureReason()).isEqualTo("expired_state");
    }

    @Test
    void expiredSessionCodeIsRefused() throws Exception {
        String devToken = devToken();
        UUID orgId = createOrganization(devToken, "Rho SSO");
        putConfig(devToken, orgId, Map.of("provisioningMode", "JIT", "enabled", true));
        idp.behave().email = "slow.exchange@rho.example";

        String callback = completeBrowserDance(orgId);
        String sessionCode = callback.substring(callback.indexOf("code=") + 5);
        jdbc.update("UPDATE sso_session_codes SET expires_at = now() - INTERVAL '1 minute'");

        assertThat(statusOf(HttpMethod.POST, "/api/v1/auth/sso/exchange", null, Map.of("code", sessionCode)))
                .isEqualTo(401);
    }

    @Test
    void unresolvableSecretReferenceFailsClosed() throws Exception {
        String devToken = devToken();
        UUID orgId = createOrganization(devToken, "Sigma SSO");
        Map<String, Object> body = new HashMap<>();
        body.put("providerType", "OIDC");
        body.put("issuerUrl", idp.issuerFor(port));
        body.put("clientId", MockOidcProvider.CLIENT_ID);
        body.put("clientSecretRef", "local-envelope://00000000-0000-0000-0000-000000000000/oidc/never-stored");
        body.put("provisioningMode", "JIT");
        body.put("enabled", true);
        client.put()
                .uri("/api/v1/organizations/" + orgId + "/idp-config")
                .header("Authorization", "Bearer " + devToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class);
        idp.behave().email = "sigma.user@sigma.example";

        assertThat(completeBrowserDance(orgId)).isEqualTo(CONSOLE_CALLBACK + "?error=sso");
        assertThat(failureReason()).isEqualTo("secret_unresolvable");
    }

    @Test
    void discoveryIssuerMismatchRefusesAuthorization() throws Exception {
        String devToken = devToken();
        UUID orgId = createOrganization(devToken, "Tau SSO");
        Map<String, Object> body = new HashMap<>();
        body.put("providerType", "OIDC");
        body.put("issuerUrl", idp.issuerFor(port) + "-mismatch");
        body.put("clientId", MockOidcProvider.CLIENT_ID);
        body.put("clientSecret", MockOidcProvider.CLIENT_SECRET);
        body.put("provisioningMode", "JIT");
        body.put("enabled", true);
        client.put()
                .uri("/api/v1/organizations/" + orgId + "/idp-config")
                .header("Authorization", "Bearer " + devToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class);

        assertThat(statusOf(HttpMethod.GET, "/api/v1/auth/sso/authorize?organizationId=" + orgId, null))
                .as("a discovery document whose issuer disagrees with the configured issuer refuses the flow")
                .isEqualTo(400);
    }

    @Test
    void escalationRankPicksTheMostPrivilegedMappedGroup() throws Exception {
        String devToken = devToken();
        UUID orgId = createOrganization(devToken, "Upsilon SSO");
        putConfig(
                devToken,
                orgId,
                Map.of(
                        "provisioningMode",
                        "JIT",
                        "enabled",
                        true,
                        "groupClaimName",
                        "groups",
                        "roleMappings",
                        Map.of("eng-devs", "DEVELOPER", "team-leads", "ORG_ADMIN")));

        idp.behave().email = "lead.user@upsilon.example";
        idp.behave().groups = List.of("eng-devs", "team-leads");
        assertThat(completeBrowserDance(orgId)).startsWith(CONSOLE_CALLBACK + "?code=");

        UUID userId = jdbc.queryForObject(
                "SELECT user_id FROM user_idp_identities WHERE organization_id = ?", UUID.class, orgId);
        assertThat(membershipRole(orgId, userId))
                .as("the most privileged mapped group wins the rank resolution")
                .isEqualTo("ORG_ADMIN");
    }

    // ------------------------------------------------------------------
    // Review-loop pins (hard lock gates, logout-token shape)
    // ------------------------------------------------------------------

    @Test
    void idpInactiveHardLockEndsPatSessionsToo() throws Exception {
        String devToken = devToken();
        UUID orgId = createOrganization(devToken, "Chi SSO");
        putConfig(
                devToken,
                orgId,
                Map.of("provisioningMode", "JIT", "enabled", true, "inactiveClaimName", "sd_inactive"));

        idp.behave().email = "pat.holder@chi.example";
        String callback = completeBrowserDance(orgId);
        JsonNode tokens = exchange(callback.substring(callback.indexOf("code=") + 5), 200);

        JsonNode pat = client.post()
                .uri("/api/v1/auth/tokens")
                .header("Authorization", "Bearer " + tokens.get("accessToken").asText())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", "ci-token"))
                .retrieve()
                .body(JsonNode.class);
        String rawPat = pat.get("token").asText();
        assertThat(statusOf(HttpMethod.GET, "/api/v1/auth/me", rawPat)).isEqualTo(200);

        idp.reset();
        idp.announcePort(port);
        idp.behave().email = "pat.holder@chi.example";
        idp.behave().extraClaims = Map.of("sd_inactive", true);
        assertThat(completeBrowserDance(orgId)).isEqualTo(CONSOLE_CALLBACK + "?error=sso");

        assertThat(statusOf(HttpMethod.GET, "/api/v1/auth/me", rawPat))
                .as("the IdP-inactive hard lock ends PAT sessions too")
                .isEqualTo(401);
    }

    @Test
    void logoutTokenWithoutTheEventsClaimIsRefused() throws Exception {
        String devToken = devToken();
        UUID orgId = createOrganization(devToken, "Psi SSO");
        putConfig(devToken, orgId, Map.of("provisioningMode", "JIT", "enabled", true));

        idp.behave().email = "shape.check@psi.example";
        assertThat(completeBrowserDance(orgId)).startsWith(CONSOLE_CALLBACK + "?code=");

        // A well-signed ID-token-shaped assertion: no events claim, so
        // NOT a logout token -- refused per the back-channel spec.
        String idTokenShaped = idp.mintLogoutToken(idp.behave().subject, false);
        int status = client.post()
                .uri("/api/v1/auth/sso/logout")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body("logout_token=" + idTokenShaped)
                .exchange((request, response) -> response.getStatusCode().value());
        assertThat(status)
                .as("the logout_token form field must carry the spec's events claim")
                .isEqualTo(400);
    }

    // ------------------------------------------------------------------
    // Helpers: the browser dance
    // ------------------------------------------------------------------

    /** Runs the full dance and answers the final console redirect. */
    private String completeBrowserDance(UUID organizationId) {
        return followCallback(browserDanceUpToCallbackUrl(organizationId));
    }

    /** The provider-side redirect target (the callback URL with code+state). */
    private String browserDanceUpToCallbackUrl(UUID organizationId) {
        String authorizeLocation = client.get()
                .uri("/api/v1/auth/sso/authorize?organizationId=" + organizationId)
                .exchange((request, response) -> {
                    assertThat(response.getStatusCode().value()).isEqualTo(302);
                    return response.getHeaders().getFirst(HttpHeaders.LOCATION);
                });
        assertThat(authorizeLocation).contains("/mock-idp/authorize");
        return client.get().uri(java.net.URI.create(authorizeLocation)).exchange((request, response) -> {
            assertThat(response.getStatusCode().value()).isEqualTo(302);
            return response.getHeaders().getFirst(HttpHeaders.LOCATION);
        });
    }

    /** Follows the callback URL once; answers the console redirect. */
    private String followCallback(String callbackUrl) {
        return client.get().uri(java.net.URI.create(callbackUrl)).exchange((request, response) -> {
            assertThat(response.getStatusCode().value()).isEqualTo(302);
            return response.getHeaders().getFirst(HttpHeaders.LOCATION);
        });
    }

    private JsonNode exchange(String sessionCode, int expectedStatus) {
        return client.post()
                .uri("/api/v1/auth/sso/exchange")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("code", sessionCode))
                .exchange((request, response) -> {
                    assertThat(response.getStatusCode().value()).isEqualTo(expectedStatus);
                    return mapper.readTree(response.getBody());
                });
    }

    // ------------------------------------------------------------------
    // Helpers: world-building
    // ------------------------------------------------------------------

    private String devToken() {
        JsonNode tokens = client.post()
                .uri("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", DEV_ADMIN_EMAIL, "password", DEV_ADMIN_PASSWORD))
                .retrieve()
                .body(JsonNode.class);
        return tokens.get("accessToken").asText();
    }

    private UUID createOrganization(String token, String name) {
        JsonNode organization = client.post()
                .uri("/api/v1/organizations")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", name))
                .retrieve()
                .body(JsonNode.class);
        return UUID.fromString(organization.get("id").asText());
    }

    /**
     * Invites an address (org-scoped when organizationId given), lets the
     * invitee register and log in, and answers the new user's id -- the
     * invited-member world of the INVITED_ONLY policy.
     */
    private UUID inviteRegisterAndLogin(String devToken, UUID organizationId, String email, String role) {
        Map<String, Object> invitation = new HashMap<>();
        invitation.put("email", email);
        if (organizationId != null) {
            invitation.put("organizationId", organizationId.toString());
            invitation.put("role", role);
        }
        JsonNode created = client.post()
                .uri("/api/v1/auth/invitations")
                .header("Authorization", "Bearer " + devToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(invitation)
                .retrieve()
                .body(JsonNode.class);
        JsonNode user = client.post()
                .uri("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "invitationToken",
                        created.get("invitationToken").asText(),
                        "email",
                        email,
                        "password",
                        "correct-horse-battery-1",
                        "fullName",
                        email.substring(0, email.indexOf('@'))))
                .retrieve()
                .body(JsonNode.class);
        return UUID.fromString(user.get("id").asText());
    }

    private void putConfig(String token, UUID organizationId, Map<String, Object> overrides) {
        Map<String, Object> body = new HashMap<>();
        body.put("providerType", "OIDC");
        body.put("issuerUrl", idp.issuerFor(port));
        body.put("clientId", MockOidcProvider.CLIENT_ID);
        body.put("clientSecret", MockOidcProvider.CLIENT_SECRET);
        body.putAll(overrides);
        client.put()
                .uri("/api/v1/organizations/" + organizationId + "/idp-config")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class);
    }

    // ------------------------------------------------------------------
    // Helpers: assertions
    // ------------------------------------------------------------------

    private String failureReason() {
        List<AuditEvent> failures = audit.eventsOfType(AuditEventType.SSO_LOGIN_FAILURE);
        assertThat(failures).isNotEmpty();
        return failures.getLast().detail().get("reason");
    }

    private int identityCount(UUID organizationId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM user_idp_identities WHERE organization_id = ?", Integer.class, organizationId);
        return count == null ? 0 : count;
    }

    private String membershipRole(UUID organizationId, UUID userId) {
        return jdbc.queryForObject(
                "SELECT role::text FROM organization_members WHERE organization_id = ? AND user_id = ?",
                String.class,
                organizationId,
                userId);
    }

    private int statusOf(HttpMethod method, String uri, String token) {
        return statusOf(method, uri, token, null);
    }

    private int statusOf(HttpMethod method, String uri, String token, Object body) {
        RestClient.RequestBodySpec spec = client.method(method).uri(uri);
        if (token != null) {
            spec = spec.header("Authorization", "Bearer " + token);
        }
        if (body != null) {
            spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return spec.exchange((request, response) -> response.getStatusCode().value());
    }
}
