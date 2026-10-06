package com.shipperdog.api.tester;

import static org.assertj.core.api.Assertions.assertThat;

import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.identity.AuditCapture;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
 * End-to-end acceptance flow for issue #26 (S6-01) against a real
 * PostgreSQL and HTTP stack: idempotent tester creation with a
 * single-use invitation token (shown once to the inviting admin, and
 * delivered through the email seam with the accept link), acceptance
 * through the REUSED platform registration endpoint, token issuance via
 * the REUSED S1-03 login, the INVITED/ACTIVE/SUSPENDED lifecycle with
 * login/refresh/PAT suspension enforcement, re-invite revocation
 * semantics, concealed cross-organization access, audited invitation
 * events, and organization-scoped paginated listing.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
            "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef"
        })
@Testcontainers
@Import({AuditCapture.class, CapturingEmailSender.class})
class TesterOnboardingIntegrationTest {

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
    private CapturingEmailSender email;

    @Autowired
    private ObjectMapper mapper;

    private RestClient client;

    @BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
        audit.reset();
        email.reset();
    }

    /** AC1 + AC2: creation, once-shown token, dev-visible accept link, idempotent duplicate. */
    @Test
    void createIsIdempotentAndShowsTheTokenExactlyOnce() {
        String admin = loginOfOrgAdmin("t6-admin@example.com", "Tester Six Corp");
        UUID orgId = organizationIdByName("Tester Six Corp");

        JsonNode created = post(
                admin,
                "/api/v1/testers",
                Map.of(
                        "organizationId",
                        orgId.toString(),
                        "email",
                        "Ana.Tester@Example.COM ",
                        "fullName",
                        "Ana Tester"),
                201);
        UUID testerId = UUID.fromString(created.get("id").asText());
        assertThat(created.get("status").asText())
                .as("testers are born INVITED (AC1)")
                .isEqualTo("INVITED");
        assertThat(created.get("userId").isNull()).as("no account exists yet").isTrue();
        assertThat(created.get("email").asText())
                .as("email is normalized (lower-cased, trimmed)")
                .isEqualTo("ana.tester@example.com");
        assertThat(created.get("organizationId").asText()).isEqualTo(orgId.toString());

        JsonNode invitation = created.get("invitation");
        assertThat(invitation)
                .as("the creation response carries the invitation block")
                .isNotNull();
        String token = invitation.get("invitationToken").asText();
        assertThat(token).isNotBlank();
        assertThat(invitation.get("acceptUrl").asText())
                .as("the accept link is dev-mode visible to the invoker (AC2)")
                .contains("accept-invitation?token=" + token);
        assertThat(Instant.parse(invitation.get("expiresAt").asText()))
                .as("invitation TTL defaults to 7 days (AC2)")
                .isAfter(Instant.now().plus(6, ChronoUnit.DAYS))
                .isBefore(Instant.now().plus(8, ChronoUnit.DAYS));

        assertThat(email.sent())
                .as("the invitation email is delivered through the seam (AC2)")
                .hasSize(1);
        CapturingEmailSender.Sent sent = email.sent().getFirst();
        assertThat(sent.to()).isEqualTo("ana.tester@example.com");
        assertThat(sent.body()).contains("accept-invitation?token=" + token);

        JsonNode duplicate = post(
                admin,
                "/api/v1/testers",
                Map.of(
                        "organizationId",
                        orgId.toString(),
                        "email",
                        "ana.tester@example.com",
                        "fullName",
                        "Someone Else"),
                200);
        assertThat(duplicate.get("id").asText())
                .as("duplicate pending invite returns the existing tester (AC1)")
                .isEqualTo(testerId.toString());
        assertThat(duplicate.get("fullName").asText())
                .as("the existing record is returned unchanged")
                .isEqualTo("Ana Tester");
        assertThat(duplicate.get("invitation").has("invitationToken"))
                .as("a duplicate never re-shows or re-mints the token")
                .isFalse();
        assertThat(duplicate.get("invitation").get("status").asText()).isEqualTo("PENDING");
        assertThat(email.sent())
                .as("no second email for the idempotent duplicate")
                .hasSize(1);

        assertThat(audit.eventsOfType(AuditEventType.TESTER_INVITED))
                .as("invitation events are audited (AC5)")
                .hasSize(1);
        AuditEvent invited = audit.eventsOfType(AuditEventType.TESTER_INVITED).getFirst();
        assertThat(invited.orgId()).isEqualTo(orgId);
        assertThat(invited.detail()).containsEntry("testerId", testerId.toString());
        AuditEvent invitationCreated = audit.eventsOfType(AuditEventType.INVITATION_CREATED).stream()
                .filter(event -> "tester".equals(event.detail().get("flavor")))
                .findFirst()
                .orElseThrow();
        assertThat(invitationCreated.detail())
                .as("the reused invitation machinery records the tester flavor")
                .containsEntry("testerId", testerId.toString())
                .containsEntry("flavor", "tester");
    }

    /** AC1 negative space: validation and concealment on creation. */
    @Test
    void createValidatesInputAndConcealsForeignOrganizations() {
        String admin = loginOfOrgAdmin("t6-vadmin@example.com", "Tester Validation Corp");
        UUID orgId = organizationIdByName("Tester Validation Corp");
        String foreignAdmin = loginOfOrgAdmin("t6-foreign@example.com", "Tester Foreign Corp");
        UUID foreignOrgId = organizationIdByName("Tester Foreign Corp");

        assertThat(post(
                                "/api/v1/testers",
                                admin,
                                Map.of("organizationId", orgId.toString(), "email", "not-an-email", "fullName", "X"))
                        .status)
                .isEqualTo(400);
        assertThat(post("/api/v1/testers", admin, Map.of("organizationId", orgId.toString(), "email", "x@example.com"))
                        .status)
                .as("fullName is required")
                .isEqualTo(400);
        assertThat(post(
                                "/api/v1/testers",
                                admin,
                                Map.of(
                                        "organizationId",
                                        UUID.randomUUID().toString(),
                                        "email",
                                        "x@example.com",
                                        "fullName",
                                        "X"))
                        .status)
                .as("unknown organization is concealed")
                .isEqualTo(404);
        Recording foreign = post(
                "/api/v1/testers",
                foreignAdmin,
                Map.of("organizationId", orgId.toString(), "email", "spy@example.com", "fullName", "Spy"));
        assertThat(foreign.status)
                .as("inviting into another organization is a concealed denial")
                .isEqualTo(404);
        assertThat(foreign.contentType).startsWith("application/problem+json");
        assertThat(post(
                                "/api/v1/testers",
                                null,
                                Map.of("organizationId", orgId.toString(), "email", "x@example.com", "fullName", "X"))
                        .status)
                .as("creation requires authentication")
                .isEqualTo(401);
    }

    /** PRD 8.8: one person can be a tester of two organizations. */
    @Test
    void sameAddressAcrossOrganizationsCreatesDistinctTesters() {
        String firstAdmin = loginOfOrgAdmin("t6-alpha@example.com", "Tester Alpha Corp");
        String secondAdmin = loginOfOrgAdmin("t6-beta@example.com", "Tester Beta Corp");

        JsonNode first = post(
                firstAdmin,
                "/api/v1/testers",
                Map.of(
                        "organizationId",
                        organizationIdByName("Tester Alpha Corp").toString(),
                        "email",
                        "shared@example.com",
                        "fullName",
                        "Shared Person"),
                201);
        JsonNode second = post(
                secondAdmin,
                "/api/v1/testers",
                Map.of(
                        "organizationId",
                        organizationIdByName("Tester Beta Corp").toString(),
                        "email",
                        "shared@example.com",
                        "fullName",
                        "Shared Person"),
                201);
        assertThat(first.get("id").asText())
                .as("each organization keeps its own tester record")
                .isNotEqualTo(second.get("id").asText());
    }

    /** AC3: accept sets the password, activates the tester, and login issues tokens. */
    @Test
    void acceptanceActivatesTheTesterAndLoginIssuesTokens() {
        String admin = loginOfOrgAdmin("t6-accept@example.com", "Tester Accept Corp");
        UUID orgId = organizationIdByName("Tester Accept Corp");
        JsonNode created = post(
                admin,
                "/api/v1/testers",
                Map.of("organizationId", orgId.toString(), "email", "walker@example.com", "fullName", "Walker Test"),
                201);
        UUID testerId = UUID.fromString(created.get("id").asText());
        String token = created.get("invitation").get("invitationToken").asText();

        JsonNode user = post(
                null,
                "/api/v1/auth/register",
                Map.of(
                        "invitationToken",
                        token,
                        "email",
                        "walker@example.com",
                        "password",
                        PASSWORD,
                        "fullName",
                        "Walker Test"),
                201);
        assertThat(user.get("status").asText()).isEqualTo("ACTIVE");
        UUID userId = UUID.fromString(user.get("id").asText());

        JsonNode after = get(admin, "/api/v1/testers/" + testerId, 200);
        assertThat(after.get("status").asText())
                .as("acceptance flips INVITED -> ACTIVE (AC3)")
                .isEqualTo("ACTIVE");
        assertThat(after.get("userId").asText())
                .as("the created account is linked")
                .isEqualTo(userId.toString());

        JsonNode login =
                post(null, "/api/v1/auth/login", Map.of("email", "walker@example.com", "password", PASSWORD), 200);
        assertThat(login.get("accessToken").asText())
                .as("S1-03 login issues tokens after activation (AC3)")
                .isNotBlank();

        assertThat(audit.eventsOfType(AuditEventType.USER_REGISTERED))
                .as("the accepted account registers through the reused flow")
                .anySatisfy(event -> assertThat(event.userId()).isEqualTo(userId));
        AuditEvent activation =
                audit.eventsOfType(AuditEventType.TESTER_ACTIVATED).getFirst();
        assertThat(activation.detail()).containsEntry("testerId", testerId.toString());

        Map<String, Object> invitationRow =
                jdbc.queryForMap("SELECT status FROM user_invitations WHERE tester_id = ?", testerId);
        assertThat(invitationRow.get("status")).as("the invitation is consumed").isEqualTo("ACCEPTED");
    }

    /** AC2: the token is single-use. */
    @Test
    void invitationTokenIsSingleUse() {
        String admin = loginOfOrgAdmin("t6-single@example.com", "Tester Single Corp");
        UUID orgId = organizationIdByName("Tester Single Corp");
        JsonNode created = post(
                admin,
                "/api/v1/testers",
                Map.of("organizationId", orgId.toString(), "email", "once@example.com", "fullName", "Once Only"),
                201);
        String token = created.get("invitation").get("invitationToken").asText();

        post(
                null,
                "/api/v1/auth/register",
                Map.of(
                        "invitationToken",
                        token,
                        "email",
                        "once@example.com",
                        "password",
                        PASSWORD,
                        "fullName",
                        "Once Only"),
                201);
        Recording second = post(
                "/api/v1/auth/register",
                null,
                Map.of(
                        "invitationToken",
                        token,
                        "email",
                        "once@example.com",
                        "password",
                        PASSWORD,
                        "fullName",
                        "Once Only"));
        assertThat(second.status).as("a used token is dead (AC2)").isEqualTo(400);
        assertThat(second.body.get("code").asText()).isEqualTo("INVALID_INVITATION");
    }

    /** Re-invite revokes the live token and mints a fresh one. */
    @Test
    void reinviteRevokesTheOldTokenAndMintsAFreshOne() {
        String admin = loginOfOrgAdmin("t6-reinvite@example.com", "Tester Reinvite Corp");
        UUID orgId = organizationIdByName("Tester Reinvite Corp");
        JsonNode created = post(
                admin,
                "/api/v1/testers",
                Map.of("organizationId", orgId.toString(), "email", "again@example.com", "fullName", "Again Tester"),
                201);
        UUID testerId = UUID.fromString(created.get("id").asText());
        String oldToken = created.get("invitation").get("invitationToken").asText();

        JsonNode reinvited = post(admin, "/api/v1/testers/" + testerId + "/invitations", Map.of(), 201);
        String newToken = reinvited.get("invitationToken").asText();
        assertThat(newToken).isNotBlank().isNotEqualTo(oldToken);
        assertThat(email.sent()).as("the re-invitation is delivered too").hasSize(2);
        assertThat(audit.eventsOfType(AuditEventType.TESTER_INVITED))
                .as("re-invite audits TESTER_INVITED again")
                .hasSize(2);

        Recording oldAccept = post(
                "/api/v1/auth/register",
                null,
                Map.of(
                        "invitationToken",
                        oldToken,
                        "email",
                        "again@example.com",
                        "password",
                        PASSWORD,
                        "fullName",
                        "Again Tester"));
        assertThat(oldAccept.status).as("the revoked token is dead").isEqualTo(400);

        post(
                null,
                "/api/v1/auth/register",
                Map.of(
                        "invitationToken",
                        newToken,
                        "email",
                        "again@example.com",
                        "password",
                        PASSWORD,
                        "fullName",
                        "Again Tester"),
                201);
    }

    /** AC4: lifecycle enforcement, suspension blocks login/refresh/PAT, reactivation restores. */
    @Test
    void suspensionBlocksEveryCredentialPathUntilReactivated() {
        String admin = loginOfOrgAdmin("t6-suspend@example.com", "Tester Suspend Corp");
        UUID orgId = organizationIdByName("Tester Suspend Corp");

        JsonNode created = post(
                admin,
                "/api/v1/testers",
                Map.of("organizationId", orgId.toString(), "email", "sleepy@example.com", "fullName", "Sleepy Tester"),
                201);
        UUID testerId = UUID.fromString(created.get("id").asText());

        Recording suspendInvited = put("/api/v1/testers/" + testerId + "/status", admin, Map.of("status", "SUSPENDED"));
        assertThat(suspendInvited.status)
                .as("INVITED -> SUSPENDED via the manual surface is refused")
                .isEqualTo(409);
        assertThat(suspendInvited.body.get("code").asText()).isEqualTo("TESTER_INVALID_TRANSITION");
        Recording manualActivate = put("/api/v1/testers/" + testerId + "/status", admin, Map.of("status", "ACTIVE"));
        assertThat(manualActivate.status)
                .as("INVITED -> ACTIVE belongs to the accept flow alone")
                .isEqualTo(409);

        post(
                null,
                "/api/v1/auth/register",
                Map.of(
                        "invitationToken",
                        created.get("invitation").get("invitationToken").asText(),
                        "email",
                        "sleepy@example.com",
                        "password",
                        PASSWORD,
                        "fullName",
                        "Sleepy Tester"),
                201);

        // Credentials must exist BEFORE suspension to prove it outlives them.
        String refreshToken = refreshOf("sleepy@example.com");
        String pat = patOf("sleepy@example.com");
        assertThat(post(null, "/api/v1/auth/login", Map.of("email", "sleepy@example.com", "password", PASSWORD), 200)
                        .get("accessToken")
                        .asText())
                .as("the ACTIVE tester logs in")
                .isNotBlank();

        JsonNode suspended = put(admin, "/api/v1/testers/" + testerId + "/status", Map.of("status", "SUSPENDED"), 200);
        assertThat(suspended.get("status").asText()).isEqualTo("SUSPENDED");

        assertThat(post("/api/v1/auth/login", null, Map.of("email", "sleepy@example.com", "password", PASSWORD)).status)
                .as("suspended testers cannot log in (AC4)")
                .isEqualTo(401);
        Recording refresh = post("/api/v1/auth/refresh", null, Map.of("refreshToken", refreshToken));
        assertThat(refresh.status)
                .as("suspension outlives issued refresh tokens")
                .isEqualTo(401);
        Recording patUse = get("/api/v1/auth/me", pat);
        assertThat(patUse.status)
                .as("suspension blocks personal access tokens too")
                .isEqualTo(401);

        AuditEvent change =
                audit.eventsOfType(AuditEventType.TESTER_STATUS_CHANGED).getFirst();
        assertThat(change.detail()).containsEntry("from", "ACTIVE").containsEntry("to", "SUSPENDED");

        JsonNode reactivated = put(admin, "/api/v1/testers/" + testerId + "/status", Map.of("status", "ACTIVE"), 200);
        assertThat(reactivated.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(post(null, "/api/v1/auth/login", Map.of("email", "sleepy@example.com", "password", PASSWORD), 200)
                        .get("accessToken")
                        .asText())
                .as("reactivation restores login")
                .isNotBlank();
    }

    /** AC4 negative space: accepted testers are not re-invitable. */
    @Test
    void reinviteRejectsTestersThatAlreadyAccepted() {
        String admin = loginOfOrgAdmin("t6-active@example.com", "Tester Active Corp");
        UUID orgId = organizationIdByName("Tester Active Corp");
        UUID testerId = onboardedTester(admin, orgId, "settled@example.com", "Settled Tester");
        Recording reinvite = post("/api/v1/testers/" + testerId + "/invitations", admin, Map.of());
        assertThat(reinvite.status).isEqualTo(409);
        assertThat(reinvite.body.get("code").asText()).isEqualTo("TESTER_ALREADY_ACTIVE");
    }

    /** INV-001 shape: another organization's tester is indistinguishable from a missing one. */
    @Test
    void crossOrganizationAccessIsConcealed() {
        String admin = loginOfOrgAdmin("t6-owner@example.com", "Tester Owner Corp");
        UUID orgId = organizationIdByName("Tester Owner Corp");
        UUID testerId = UUID.fromString(post(
                        admin,
                        "/api/v1/testers",
                        Map.of(
                                "organizationId",
                                orgId.toString(),
                                "email",
                                "hidden@example.com",
                                "fullName",
                                "Hidden Tester"),
                        201)
                .get("id")
                .asText());
        String stranger = loginOfOrgAdmin("t6-stranger@example.com", "Tester Stranger Corp");

        assertThat(get("/api/v1/testers/" + testerId, stranger).status).isEqualTo(404);
        assertThat(post("/api/v1/testers/" + testerId + "/invitations", stranger, Map.of()).status)
                .isEqualTo(404);
        assertThat(put("/api/v1/testers/" + testerId + "/status", stranger, Map.of("status", "SUSPENDED")).status)
                .isEqualTo(404);
        assertThat(get("/api/v1/testers?organizationId=" + orgId, stranger).status)
                .as("a foreign organization's listing is concealed as well")
                .isEqualTo(404);
        assertThat(get("/api/v1/testers/" + UUID.randomUUID(), admin).status)
                .as("a genuine miss renders the same 404")
                .isEqualTo(404);

        assertThat(audit.eventsOfType(AuditEventType.ACCESS_DENIED))
                .as("the concealed denials are audited (PRD 12: the trail, not the response, carries the who/what)")
                .isNotEmpty();
    }

    /** FR-010 listing: organization-scoped, filterable, cursor-paginated. */
    @Test
    void listingIsScopedFilterableAndPaginated() {
        String admin = loginOfOrgAdmin("t6-list@example.com", "Tester List Corp");
        UUID orgId = organizationIdByName("Tester List Corp");
        post(
                admin,
                "/api/v1/testers",
                Map.of("organizationId", orgId.toString(), "email", "b@example.com", "fullName", "B"),
                201);
        post(
                admin,
                "/api/v1/testers",
                Map.of("organizationId", orgId.toString(), "email", "c@example.com", "fullName", "C"),
                201);
        onboardedTester(admin, orgId, "a@example.com", "A");

        JsonNode all = get(admin, "/api/v1/testers?organizationId=" + orgId, 200);
        assertThat(all.get("items")).hasSize(3);
        assertThat(all.get("items").get(0).get("email").asText())
                .as("listing is email-ordered")
                .isEqualTo("a@example.com");
        assertThat(all.get("items").get(0).get("userId").asText())
                .as("the accepted tester shows its linked account")
                .isNotEmpty();

        JsonNode invitedOnly = get(admin, "/api/v1/testers?organizationId=" + orgId + "&status=INVITED", 200);
        assertThat(invitedOnly.get("items"))
                .as("status filter narrows the listing")
                .hasSize(2);

        JsonNode firstPage = get(admin, "/api/v1/testers?organizationId=" + orgId + "&limit=2", 200);
        assertThat(firstPage.get("items")).hasSize(2);
        String cursor = firstPage.get("next_cursor").asText();
        assertThat(cursor).isNotEmpty();
        JsonNode secondPage = get(admin, "/api/v1/testers?organizationId=" + orgId + "&limit=2&cursor=" + cursor, 200);
        assertThat(secondPage.get("items")).hasSize(1);
        assertThat(secondPage.get("items").get(0).get("email").asText()).isEqualTo("c@example.com");

        String otherAdmin = loginOfOrgAdmin("t6-list2@example.com", "Tester List Two Corp");
        UUID otherOrgId = organizationIdByName("Tester List Two Corp");
        post(
                otherAdmin,
                "/api/v1/testers",
                Map.of("organizationId", otherOrgId.toString(), "email", "zz@example.com", "fullName", "ZZ"),
                201);
        assertThat(get(admin, "/api/v1/testers?organizationId=" + orgId, 200).get("items"))
                .as("the listing never leaks another organization's testers")
                .hasSize(3);
    }

    /** AC2 edge: a mismatched address cannot ride an invitation. */
    @Test
    void acceptanceRequiresTheInvitedAddress() {
        String admin = loginOfOrgAdmin("t6-mismatch@example.com", "Tester Mismatch Corp");
        UUID orgId = organizationIdByName("Tester Mismatch Corp");
        JsonNode created = post(
                admin,
                "/api/v1/testers",
                Map.of("organizationId", orgId.toString(), "email", "right@example.com", "fullName", "Right Person"),
                201);
        Recording wrong = post(
                "/api/v1/auth/register",
                null,
                Map.of(
                        "invitationToken",
                        created.get("invitation").get("invitationToken").asText(),
                        "email",
                        "wrong@example.com",
                        "password",
                        PASSWORD,
                        "fullName",
                        "Wrong Person"));
        assertThat(wrong.status).isEqualTo(400);
        assertThat(wrong.body.get("code").asText()).isEqualTo("INVALID_INVITATION");
    }

    /** An address that already owns an account cannot be claimed by tester onboarding. */
    @Test
    void acceptanceRefusesAlreadyRegisteredAddresses() {
        String admin = loginOfOrgAdmin("t6-taken@example.com", "Tester Taken Corp");
        UUID orgId = organizationIdByName("Tester Taken Corp");
        registerOrglessUser("existing@example.com");
        JsonNode created = post(
                admin,
                "/api/v1/testers",
                Map.of(
                        "organizationId",
                        orgId.toString(),
                        "email",
                        "existing@example.com",
                        "fullName",
                        "Existing Person"),
                201);
        Recording accept = post(
                "/api/v1/auth/register",
                null,
                Map.of(
                        "invitationToken",
                        created.get("invitation").get("invitationToken").asText(),
                        "email",
                        "existing@example.com",
                        "password",
                        PASSWORD,
                        "fullName",
                        "Existing Person"));
        assertThat(accept.status)
                .as("no account takeover through tester onboarding")
                .isEqualTo(409);
        assertThat(accept.body.get("code").asText()).isEqualTo("EMAIL_TAKEN");
    }

    /** S1-08 registry membership: POST /testers honors Idempotency-Key replay. */
    @Test
    void creationHonorsIdempotencyKeyReplay() {
        String admin = loginOfOrgAdmin("t6-idem@example.com", "Tester Idem Corp");
        UUID orgId = organizationIdByName("Tester Idem Corp");
        String key = UUID.randomUUID().toString();

        Recording first = exchangeWithIdempotencyKey(
                "/api/v1/testers",
                admin,
                Map.of("organizationId", orgId.toString(), "email", "keyed@example.com", "fullName", "Keyed Tester"),
                key);
        assertThat(first.status).isEqualTo(201);
        Recording replay = exchangeWithIdempotencyKey(
                "/api/v1/testers",
                admin,
                Map.of("organizationId", orgId.toString(), "email", "keyed@example.com", "fullName", "Changed Mind"),
                key);
        assertThat(replay.status)
                .as("the retry replays the stored 201 verbatim")
                .isEqualTo(201);
        assertThat(replay.body.get("id").asText())
                .isEqualTo(first.body.get("id").asText());
        assertThat(replay.replayed)
                .as("the replay marker distinguishes storage from execution")
                .isTrue();
        assertThat(email.sent()).as("one email despite the retry").hasSize(1);
    }

    /**
     * Suspension is reversible and revokes nothing: after reactivation the
     * pre-suspension refresh token rotates again (contrast: account
     * disable revokes families).
     */
    @Test
    void reactivationRestoresTheSuspendedSessionFamily() {
        String admin = loginOfOrgAdmin("t6-family@example.com", "Tester Family Corp");
        UUID orgId = organizationIdByName("Tester Family Corp");
        JsonNode created = post(
                admin,
                "/api/v1/testers",
                Map.of("organizationId", orgId.toString(), "email", "family@example.com", "fullName", "Family Tester"),
                201);
        UUID testerId = UUID.fromString(created.get("id").asText());
        post(
                null,
                "/api/v1/auth/register",
                Map.of(
                        "invitationToken",
                        created.get("invitation").get("invitationToken").asText(),
                        "email",
                        "family@example.com",
                        "password",
                        PASSWORD,
                        "fullName",
                        "Family Tester"),
                201);
        String refreshToken = refreshOf("family@example.com");

        put(admin, "/api/v1/testers/" + testerId + "/status", Map.of("status", "SUSPENDED"), 200);
        assertThat(post("/api/v1/auth/refresh", null, Map.of("refreshToken", refreshToken)).status)
                .as("suspension refuses refresh")
                .isEqualTo(401);

        put(admin, "/api/v1/testers/" + testerId + "/status", Map.of("status", "ACTIVE"), 200);
        assertThat(post(null, "/api/v1/auth/refresh", Map.of("refreshToken", refreshToken), 200)
                        .get("refreshToken")
                        .asText())
                .as("reactivation restores the family; nothing was revoked")
                .isNotBlank();
    }

    /** The accept flow inherits the platform password policy. */
    @Test
    void acceptanceEnforcesThePasswordPolicy() {
        String admin = loginOfOrgAdmin("t6-weakpw@example.com", "Tester Weak Corp");
        UUID orgId = organizationIdByName("Tester Weak Corp");
        JsonNode created = post(
                admin,
                "/api/v1/testers",
                Map.of("organizationId", orgId.toString(), "email", "short@example.com", "fullName", "Short Password"),
                201);
        Recording weak = post(
                "/api/v1/auth/register",
                null,
                Map.of(
                        "invitationToken",
                        created.get("invitation").get("invitationToken").asText(),
                        "email",
                        "short@example.com",
                        "password",
                        "short",
                        "fullName",
                        "Short Password"));
        assertThat(weak.status).isEqualTo(400);
        assertThat(weak.body.get("code").asText()).isEqualTo("WEAK_PASSWORD");
    }

    /** Convenience: invite + accept, returning the ACTIVE tester id. */
    private UUID onboardedTester(String admin, UUID orgId, String email, String fullName) {
        JsonNode created = post(
                admin,
                "/api/v1/testers",
                Map.of("organizationId", orgId.toString(), "email", email, "fullName", fullName),
                201);
        post(
                null,
                "/api/v1/auth/register",
                Map.of(
                        "invitationToken",
                        created.get("invitation").get("invitationToken").asText(),
                        "email",
                        email,
                        "password",
                        PASSWORD,
                        "fullName",
                        fullName),
                201);
        return UUID.fromString(created.get("id").asText());
    }

    private String refreshOf(String email) {
        String tokensJson = client.post()
                .uri("/api/v1/auth/login")
                .body(Map.of("email", email, "password", PASSWORD))
                .retrieve()
                .body(String.class);
        try {
            return mapper.readTree(tokensJson).get("refreshToken").asText();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String patOf(String email) {
        String accessToken = accessToken(login(email, PASSWORD));
        JsonNode pat = post(accessToken, "/api/v1/auth/tokens", Map.of("name", "s6-smoke"), 201);
        return pat.get("token").asText();
    }

    private String loginOfOrgAdmin(String email, String orgName) {
        String devToken = accessToken(login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD));
        UUID orgId = createOrganization(devToken, orgName);
        JsonNode invitation = post(
                devToken,
                "/api/v1/auth/invitations",
                Map.of("email", email, "organizationId", orgId.toString(), "role", "ORG_ADMIN"),
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
                        "Org Admin"))
                .retrieve()
                .body(String.class);
        return accessToken(login(email, PASSWORD));
    }

    /** Registers an orgless user through a plain invitation. */
    private void registerOrglessUser(String email) {
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
                        "Existing Person"))
                .retrieve()
                .body(String.class);
    }

    private UUID createOrganization(String bearer, String name) {
        JsonNode created = post(bearer, "/api/v1/organizations", Map.of("name", name), 201);
        return UUID.fromString(created.get("id").asText());
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

    private UUID organizationIdByName(String name) {
        return jdbc.queryForObject("SELECT id FROM organizations WHERE name = ?", UUID.class, name);
    }

    private JsonNode post(String bearer, String uri, Map<String, Object> body, int expectedStatus) {
        Recording response = exchange(uri, bearer, "POST", body);
        assertThat(response.status).as("POST %s", uri).isEqualTo(expectedStatus);
        return response.body;
    }

    private JsonNode put(String bearer, String uri, Map<String, Object> body, int expectedStatus) {
        Recording response = exchange(uri, bearer, "PUT", body);
        assertThat(response.status).as("PUT %s", uri).isEqualTo(expectedStatus);
        return response.body;
    }

    private Recording post(String uri, String bearer, Map<String, Object> body) {
        return exchange(uri, bearer, "POST", body);
    }

    private Recording put(String uri, String bearer, Map<String, Object> body) {
        return exchange(uri, bearer, "PUT", body);
    }

    private JsonNode get(String bearer, String uri, int expectedStatus) {
        Recording response = exchange(uri, bearer, "GET", null);
        assertThat(response.status).as("GET %s", uri).isEqualTo(expectedStatus);
        return response.body;
    }

    private Recording get(String uri, String bearer) {
        return exchange(uri, bearer, "GET", null);
    }

    private Recording exchange(String uri, String bearer, String method, Map<String, Object> body) {
        return exchange(uri, bearer, method, body, null);
    }

    private Recording exchangeWithIdempotencyKey(
            String uri, String bearer, Map<String, Object> body, String idempotencyKey) {
        return exchange(uri, bearer, "POST", body, idempotencyKey);
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
                    bytes.length == 0 ? mapper.createObjectNode() : mapper.readTree(bytes),
                    Boolean.parseBoolean(response.getHeaders().getFirst("Idempotency-Replayed")));
        });
    }

    private record Recording(int status, String contentType, JsonNode body, boolean replayed) {}
}
