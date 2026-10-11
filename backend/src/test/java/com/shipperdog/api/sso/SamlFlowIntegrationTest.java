package com.shipperdog.api.sso;

import static org.assertj.core.api.Assertions.assertThat;

import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.identity.AuditCapture;
import com.shipperdog.api.identity.internal.saml.SamlAuthnRequest;
import com.shipperdog.ssofixture.MockSamlIdp;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 * End-to-end acceptance flow for issue #68 (S14-02) against a real
 * PostgreSQL and HTTP stack, driven by the contract-faithful mock SAML
 * IdP (ssofixture.MockSamlIdp): the SP-initiated Redirect-binding dance
 * and the IdP-initiated POST, the three provider shapes the story
 * names (Entra, Okta, Google Workspace), the shared
 * provisioning/linkage/mapping semantics, the enforce-SSO-only
 * mandate, metadata rotation, and the spec-shaped attack fixtures the
 * validator must refuse -- each pinned to its distinct audit reason
 * behind the uniform {@code error=sso} redirect.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
            "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef"
        })
@TestPropertySource(properties = "shipperdog.sso.redirect-base-uri=http://sso-test-api.local")
@Testcontainers
@Import(AuditCapture.class)
class SamlFlowIntegrationTest {

    private static final String DEV_ADMIN_EMAIL = "dev@shipperdog.local";
    private static final String DEV_ADMIN_PASSWORD = "shipperdog-dev-admin";
    private static final String CONSOLE_CALLBACK = "http://localhost:5173/sso/callback";
    private static final String REDIRECT_BASE = "http://sso-test-api.local";
    private static final Pattern REQUEST_ID = Pattern.compile("\\bID=\"([^\"]+)\"");

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

    private final MockSamlIdp idp = new MockSamlIdp();

    private RestClient client;

    @BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
        audit.reset();
        idp.reset();
    }

    // ------------------------------------------------------------------
    // The happy paths: SP-initiated, IdP-initiated, provider shapes
    // ------------------------------------------------------------------

    @Test
    void spInitiatedFlowProvisionsJitAccountAndIssuesLocalSession() throws Exception {
        String devToken = devToken();
        UUID orgId = createOrganization(devToken, "Acme SAML");
        putSamlConfig(devToken, orgId, Map.of());

        idp.behave().shape = MockSamlIdp.Shape.OKTA;
        idp.behave().email = "okta.user@acme.example";
        String callback = completeSpFlow(orgId);
        assertThat(callback).startsWith(CONSOLE_CALLBACK + "?code=");

        JsonNode tokens = exchange(callback.substring(callback.indexOf("code=") + 5), 200);
        JsonNode me = client.get()
                .uri("/api/v1/auth/me")
                .header("Authorization", "Bearer " + tokens.get("accessToken").asText())
                .retrieve()
                .body(JsonNode.class);
        assertThat(me.get("authenticatedVia").asText()).isEqualTo("jwt");

        Map<String, Object> user =
                jdbc.queryForMap("SELECT password_hash, status FROM users WHERE email = ?", "okta.user@acme.example");
        assertThat(user.get("password_hash"))
                .as("JIT provisions a password-less account")
                .isNull();
        assertThat(user.get("status")).isEqualTo("ACTIVE");

        List<AuditEvent> successes = audit.eventsOfType(AuditEventType.SSO_LOGIN_SUCCESS);
        assertThat(successes).hasSize(1);
        assertThat(successes.getFirst().detail())
                .containsEntry("provisioningMode", "JIT")
                .containsEntry("issuer", entityIdOf(idp.metadataXml()));
        assertThat(audit.eventsOfType(AuditEventType.SSO_USER_JIT_PROVISIONED)).hasSize(1);
        assertThat(audit.eventsOfType(AuditEventType.SSO_IDENTITY_LINKED)).hasSize(1);
    }

    @Test
    void idpInitiatedFlowLinksInvitedMemberByEmail() throws Exception {
        String devToken = devToken();
        UUID orgId = createOrganization(devToken, "Beta SAML");
        UUID aliceId = inviteRegisterAndLogin(devToken, orgId, "alice@beta.example", "ORG_MEMBER");
        putSamlConfig(devToken, orgId, Map.of("provisioningMode", "INVITED_ONLY"));

        idp.behave().email = "alice@beta.example";
        String callback = idpInitiated(orgId);
        assertThat(callback).startsWith(CONSOLE_CALLBACK + "?code=");

        Map<String, Object> link =
                jdbc.queryForMap("SELECT user_id, link_kind FROM user_idp_identities WHERE organization_id = ?", orgId);
        assertThat(link.get("user_id")).isEqualTo(aliceId);
        assertThat(link.get("link_kind")).isEqualTo("EMAIL");
    }

    @Test
    void entraShapeMapsUriNamedRoleAttribute() throws Exception {
        String devToken = devToken();
        UUID orgId = createOrganization(devToken, "Gamma SAML");
        putSamlConfig(
                devToken,
                orgId,
                Map.of(
                        "groupClaimName",
                        "http://schemas.microsoft.com/ws/2008/06/identity/claims/roles",
                        "roleMappings",
                        Map.of("Eng-Devs", "DEVELOPER")));

        idp.behave().shape = MockSamlIdp.Shape.ENTRA;
        idp.behave().email = "entra.user@gamma.example";
        idp.behave().groups = List.of("Everyone", "Eng-Devs");
        assertThat(idpInitiated(orgId)).startsWith(CONSOLE_CALLBACK + "?code=");

        UUID userId = linkedUserId(orgId);
        assertThat(membershipRole(orgId, userId))
                .as("the URI-named Entra role attribute drives the shared mapping")
                .isEqualTo("DEVELOPER");
    }

    @Test
    void workspaceShapeAcceptsAResponseSignedAssertionUnsignedPayload() throws Exception {
        String devToken = devToken();
        UUID orgId = createOrganization(devToken, "Delta SAML");
        putSamlConfig(devToken, orgId, Map.of("wantAssertionsSigned", false, "wantResponseSigned", true));

        idp.behave().shape = MockSamlIdp.Shape.WORKSPACE;
        idp.behave().signAssertion = false;
        idp.behave().signResponse = true;
        idp.behave().email = "gsuite.user@delta.example";

        String callback = idpInitiated(orgId);
        assertThat(callback).startsWith(CONSOLE_CALLBACK + "?code=");
        assertThat(exchange(callback.substring(callback.indexOf("code=") + 5), 200)
                        .get("accessToken")
                        .asText())
                .isNotBlank();
    }

    @Test
    void responseSignaturePolicyRefusesUnsignedAndForeignSignedResponses() throws Exception {
        String devToken = devToken();
        UUID orgId = createOrganization(devToken, "Sigma SAML");
        putSamlConfig(devToken, orgId, Map.of("wantAssertionsSigned", false, "wantResponseSigned", true));

        idp.behave().shape = MockSamlIdp.Shape.WORKSPACE;
        idp.behave().signAssertion = false;
        idp.behave().signResponse = false;
        assertThat(idpInitiated(orgId))
                .as("a response-signed policy refuses the unsigned response")
                .isEqualTo(CONSOLE_CALLBACK + "?error=sso");
        assertThat(failureReason()).isEqualTo("saml_response_unsigned");

        audit.reset();
        idp.reset();
        idp.behave().shape = MockSamlIdp.Shape.WORKSPACE;
        idp.behave().signAssertion = false;
        idp.behave().signResponse = true;
        idp.behave().useForeignSigningCert = true;
        assertThat(idpInitiated(orgId))
                .as("a response signed by a key the metadata never pinned does not verify")
                .isEqualTo(CONSOLE_CALLBACK + "?error=sso");
        assertThat(failureReason())
                .as("the audit line names the RESPONSE leg's failed signature")
                .isEqualTo("saml_response_signature");
    }

    // ------------------------------------------------------------------
    // The SP surface: metadata document and the AuthnRequest redirect
    // ------------------------------------------------------------------

    @Test
    void spMetadataServesTheEntityDescriptorAndConcealsTheUnconfigured() throws Exception {
        String devToken = devToken();
        UUID orgId = createOrganization(devToken, "Epsilon SAML");
        UUID unconfigured = createOrganization(devToken, "Epsilon No SAML");
        putSamlConfig(devToken, orgId, Map.of());

        String metadata = client.get()
                .uri("/api/v1/auth/sso/saml/" + orgId + "/metadata")
                .exchange((request, response) -> {
                    assertThat(response.getStatusCode().value()).isEqualTo(200);
                    assertThat(response.getHeaders().getContentType().toString())
                            .isEqualTo("application/samlmetadata+xml");
                    return new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
                });
        assertThat(metadata)
                .contains("entityID=\"" + spEntityId(orgId) + "\"")
                .contains("Location=\"" + acsUrl(orgId) + "\"")
                .contains("urn:oasis:names:tc:SAML:2.0:bindings:HTTP-POST")
                .as("no SP signing certificate is configured, so no signing KeyDescriptor appears")
                .doesNotContain("KeyDescriptor");

        assertThat(statusOf(HttpMethod.GET, "/api/v1/auth/sso/saml/" + unconfigured + "/metadata", null))
                .as("an organization without SAML answers the uniform miss")
                .isEqualTo(404);
        assertThat(statusOf(HttpMethod.GET, "/api/v1/auth/sso/saml/" + UUID.randomUUID() + "/metadata", null))
                .isEqualTo(404);
    }

    @Test
    void authorizeBuildsTheRedirectBindingAuthnRequest() throws Exception {
        String devToken = devToken();
        UUID orgId = createOrganization(devToken, "Zeta SAML");
        putSamlConfig(devToken, orgId, Map.of());

        SpStart start = startSpFlow(orgId);
        assertThat(start.redirectLocation()).startsWith(MockSamlIdp.SSO_LOCATION + "?SAMLRequest=");
        assertThat(start.redirectLocation()).contains("RelayState=");
        assertThat(start.redirectLocation())
                .as("the unsigned posture carries no Redirect-binding signature")
                .doesNotContain("SigAlg=");

        String requestXml = SamlAuthnRequest.decodeFromRedirect(start.samlRequest());
        assertThat(requestXml)
                .contains("Version=\"2.0\"")
                .contains("Destination=\"" + MockSamlIdp.SSO_LOCATION + "\"")
                .contains("AssertionConsumerServiceURL=\"" + acsUrl(orgId) + "\"")
                .contains("<saml:Issuer>" + spEntityId(orgId) + "</saml:Issuer>")
                .contains("urn:oasis:names:tc:SAML:2.0:nameid-format:persistent");
        assertThat(start.requestId()).isNotBlank();
    }

    // ------------------------------------------------------------------
    // The attack fixtures (ADR-0009): each refusal is uniform to the
    // browser and distinct on the audit line
    // ------------------------------------------------------------------

    @Test
    void attackFixturesAreRefusedUniformly() throws Exception {
        String devToken = devToken();
        UUID orgId = createOrganization(devToken, "Eta SAML");
        putSamlConfig(devToken, orgId, Map.of());

        List<AttackCase> attacks = List.of(
                new AttackCase("unsigned assertion", b -> b.unsignedAssertionAttack = true, "saml_assertion_unsigned"),
                new AttackCase(
                        "foreign signing certificate", b -> b.useForeignSigningCert = true, "saml_assertion_signature"),
                new AttackCase("wrong audience", b -> b.wrongAudience = true, "saml_audience"),
                new AttackCase("expired assertion", b -> b.expired = true, "saml_expired"),
                new AttackCase("not-yet-valid assertion", b -> b.notYetValid = true, "saml_not_yet_valid"),
                new AttackCase("wrong recipient", b -> b.wrongRecipient = true, "saml_subject"),
                new AttackCase("wrong Response Destination", b -> b.wrongDestination = true, "saml_recipient"),
                new AttackCase("foreign Issuer", b -> b.wrongIssuer = true, "saml_issuer"),
                new AttackCase("non-success status", b -> b.successStatus = false, "saml_status"),
                new AttackCase("SHA-1 signature downgrade", b -> b.sha1SignatureAttack = true, "saml_algorithm"),
                new AttackCase("XPath transform", b -> b.xpathTransformAttack = true, "saml_algorithm"),
                new AttackCase("signature wrapping", b -> b.wrapAttack = true, "saml_wrap"),
                new AttackCase("a second, differently-IDed assertion", b -> b.extraAssertion = true, "saml_wrap"),
                new AttackCase("EncryptedAssertion wrapper", b -> b.encryptedAssertion = true, "saml_encrypted"),
                new AttackCase(
                        "internally inconsistent InResponseTo",
                        b -> {
                            b.inResponseTo = "_the-response-request";
                            b.subjectInResponseTo = "_a-different-request";
                        },
                        "saml_request_mismatch"),
                new AttackCase(
                        "stripped Response InResponseTo",
                        b -> {
                            b.inResponseTo = "_the-stolen-request";
                            b.responseInResponseToSuppressed = true;
                        },
                        "saml_request_mismatch"),
                new AttackCase("XXE payload", b -> b.xxePayload = true, "saml_malformed"),
                new AttackCase("transient NameID", b -> b.transientNameId = true, "saml_nameid"),
                new AttackCase(
                        "unknown InResponseTo",
                        b -> b.inResponseTo = "_definitely-not-a-known-request",
                        "unknown_request"));
        for (AttackCase attack : attacks) {
            idp.reset();
            audit.reset();
            attack.behavior().accept(idp.behave());
            assertThat(idpInitiated(orgId))
                    .as("the %s payload is refused", attack.name())
                    .isEqualTo(CONSOLE_CALLBACK + "?error=sso");
            assertThat(failureReason())
                    .as("the %s refusal carries its distinct audit reason", attack.name())
                    .isEqualTo(attack.reason());
        }
        assertThat(identityCount(orgId)).as("no attack minted an identity").isZero();
        assertThat(audit.eventsOfType(AuditEventType.SSO_LOGIN_SUCCESS)).isEmpty();
    }

    @Test
    void malformedBase64ResponseIsRefusedUniformly() throws Exception {
        String devToken = devToken();
        UUID orgId = createOrganization(devToken, "Theta SAML");
        putSamlConfig(devToken, orgId, Map.of());

        assertThat(postAcs(orgId, "!!!not-base64!!!", null)).isEqualTo(CONSOLE_CALLBACK + "?error=sso");
        assertThat(failureReason()).isEqualTo("saml_malformed");
    }

    // ------------------------------------------------------------------
    // One-time flow state and RelayState binding
    // ------------------------------------------------------------------

    @Test
    void requestStateIsSingleUseAndExpiryAndRelayStateAreEnforced() throws Exception {
        String devToken = devToken();
        UUID orgId = createOrganization(devToken, "Iota SAML");
        putSamlConfig(devToken, orgId, Map.of());
        idp.behave().email = "state.user@iota.example";

        // An expired request row is refused with its own reason.
        SpStart expired = startSpFlow(orgId);
        jdbc.update("UPDATE saml_authorization_requests SET expires_at = now() - INTERVAL '1 minute'");
        idp.behave().inResponseTo = expired.requestId();
        assertThat(postAcs(orgId, idp.signedResponseFor(acsUrl(orgId), spEntityId(orgId)), expired.relayState()))
                .isEqualTo(CONSOLE_CALLBACK + "?error=sso");
        assertThat(failureReason()).isEqualTo("expired_request");

        // The RelayState must hash-match what went out.
        audit.reset();
        SpStart mismatched = startSpFlow(orgId);
        idp.behave().inResponseTo = mismatched.requestId();
        assertThat(postAcs(orgId, idp.signedResponseFor(acsUrl(orgId), spEntityId(orgId)), "attacker-relay-state"))
                .isEqualTo(CONSOLE_CALLBACK + "?error=sso");
        assertThat(failureReason()).isEqualTo("relay_mismatch");

        // A successful consume burns the request: the same response
        // replayed (or raced) loses.
        audit.reset();
        SpStart consumed = startSpFlow(orgId);
        idp.behave().inResponseTo = consumed.requestId();
        String response = idp.signedResponseFor(acsUrl(orgId), spEntityId(orgId));
        assertThat(postAcs(orgId, response, consumed.relayState())).startsWith(CONSOLE_CALLBACK + "?code=");
        audit.reset();
        assertThat(postAcs(orgId, response, consumed.relayState())).isEqualTo(CONSOLE_CALLBACK + "?error=sso");
        assertThat(failureReason()).isEqualTo("replayed_request");
    }

    // ------------------------------------------------------------------
    // Metadata rotation (the acceptance criterion's alerting surface)
    // ------------------------------------------------------------------

    @Test
    void metadataRotationSwapsThePinnedTrustAnchor() throws Exception {
        String devToken = devToken();
        UUID orgId = createOrganization(devToken, "Kappa SAML");
        putSamlConfig(devToken, orgId, Map.of());
        idp.behave().email = "rotated.user@kappa.example";
        assertThat(idpInitiated(orgId)).startsWith(CONSOLE_CALLBACK + "?code=");

        // Rotation: the IdP keeps its entityID and rotates its signing
        // certificate; the administrator re-uploads the metadata. The
        // old anchor must stop verifying before the new one accepts.
        MockSamlIdp rotated = new MockSamlIdp(entityIdOf(idp.metadataXml()));
        putSamlConfig(devToken, orgId, Map.of("idpMetadataXml", rotated.metadataXml()));

        audit.reset();
        idp.behave().email = "rotated.user@kappa.example";
        assertThat(idpInitiated(orgId))
                .as("the pre-rotation certificate no longer verifies")
                .isEqualTo(CONSOLE_CALLBACK + "?error=sso");
        assertThat(failureReason()).isEqualTo("saml_assertion_signature");

        audit.reset();
        rotated.behave().email = "rotated.user@kappa.example";
        assertThat(postAcs(orgId, rotated.signedResponseFor(acsUrl(orgId), spEntityId(orgId)), null))
                .as("the new metadata's certificate pins and verifies")
                .startsWith(CONSOLE_CALLBACK + "?code=");
    }

    @Test
    void rotationOverlapAcceptsBothPinnedCertificates() throws Exception {
        String devToken = devToken();
        UUID orgId = createOrganization(devToken, "Omicron SAML");
        putSamlConfig(devToken, orgId, Map.of());

        // The rotation window: the re-uploaded metadata carries BOTH
        // the old and the new signing certificate under the same
        // entityID -- both verify until the old one is dropped.
        MockSamlIdp nextCertificate = new MockSamlIdp(entityIdOf(idp.metadataXml()));
        putSamlConfig(
                devToken,
                orgId,
                Map.of(
                        "idpMetadataXml",
                        idp.metadataXml()
                                .replace(
                                        "</md:KeyDescriptor>",
                                        "</md:KeyDescriptor>\n    "
                                                + keyDescriptorBlockOf(nextCertificate.metadataXml()))));

        idp.behave().email = "overlap.old@omicron.example";
        assertThat(idpInitiated(orgId))
                .as("the still-pinned old certificate verifies")
                .startsWith(CONSOLE_CALLBACK + "?code=");
        nextCertificate.behave().email = "overlap.new@omicron.example";
        assertThat(postAcs(orgId, nextCertificate.signedResponseFor(acsUrl(orgId), spEntityId(orgId)), null))
                .as("the newly pinned certificate verifies in the same window")
                .startsWith(CONSOLE_CALLBACK + "?code=");
    }

    // ------------------------------------------------------------------
    // Shared provisioning semantics on the SAML leg (further pins)
    // ------------------------------------------------------------------

    @Test
    void persistentNameIdWithoutAnEmailAttributeIsRefused() throws Exception {
        String devToken = devToken();
        UUID orgId = createOrganization(devToken, "Pi SAML");
        putSamlConfig(devToken, orgId, Map.of());

        idp.behave().shape = MockSamlIdp.Shape.ENTRA;
        idp.behave().email = null;
        assertThat(idpInitiated(orgId))
                .as("a persistent NameID with no email attribute gives JIT nothing to provision against")
                .isEqualTo(CONSOLE_CALLBACK + "?error=sso");
        assertThat(failureReason()).isEqualTo("no_email");
        assertThat(identityCount(orgId)).isZero();
    }

    @Test
    void idpInitiatedFlowIgnoresAnUnsolicitedRelayState() throws Exception {
        String devToken = devToken();
        UUID orgId = createOrganization(devToken, "Rho SAML");
        putSamlConfig(devToken, orgId, Map.of());
        idp.behave().email = "unsolicited@rho.example";

        assertThat(postAcs(orgId, idp.signedResponseFor(acsUrl(orgId), spEntityId(orgId)), "attacker-relay-state"))
                .as("the RelayState binds only SP-initiated state; an IdP-initiated response ignores it")
                .startsWith(CONSOLE_CALLBACK + "?code=");
    }

    // ------------------------------------------------------------------
    // The enforce-SSO-only mandate
    // ------------------------------------------------------------------

    @Test
    void enforceSsoLoginDisablesPasswordLoginForMembersButNotPlatformAdmins() throws Exception {
        String devToken = devToken();
        UUID orgId = createOrganization(devToken, "Lambda SAML");
        inviteRegisterAndLogin(devToken, orgId, "mandated.user@lambda.example", "ORG_MEMBER");

        putSamlConfig(devToken, orgId, Map.of("enforceSsoLogin", true));
        assertThat(statusOf(
                        HttpMethod.POST,
                        "/api/v1/auth/login",
                        null,
                        Map.of(
                                "email", "mandated.user@lambda.example",
                                "password", "correct-horse-battery-1")))
                .as("the SAML mandate refuses the member's password login")
                .isEqualTo(401);
        assertThat(audit.eventsOfType(AuditEventType.LOGIN_FAILURE))
                .singleElement()
                .extracting(event -> event.detail().get("reason"))
                .isEqualTo("sso_enforced");

        assertThat(statusOf(
                        HttpMethod.POST,
                        "/api/v1/auth/login",
                        null,
                        Map.of("email", DEV_ADMIN_EMAIL, "password", DEV_ADMIN_PASSWORD)))
                .as("the platform administrator's break-glass local login is untouched")
                .isEqualTo(200);

        putSamlConfig(devToken, orgId, Map.of("enforceSsoLogin", false));
        assertThat(statusOf(
                        HttpMethod.POST,
                        "/api/v1/auth/login",
                        null,
                        Map.of(
                                "email", "mandated.user@lambda.example",
                                "password", "correct-horse-battery-1")))
                .as("lifting the mandate restores the password credential")
                .isEqualTo(200);
    }

    // ------------------------------------------------------------------
    // Shared provisioning semantics on the SAML leg
    // ------------------------------------------------------------------

    @Test
    void invitedOnlyRefusesUnknownSamlSubjects() throws Exception {
        String devToken = devToken();
        UUID orgId = createOrganization(devToken, "Mu SAML");
        putSamlConfig(devToken, orgId, Map.of("provisioningMode", "INVITED_ONLY"));

        idp.behave().email = "stranger@mu.example";
        assertThat(idpInitiated(orgId)).isEqualTo(CONSOLE_CALLBACK + "?error=sso");
        assertThat(failureReason()).isEqualTo("not_invited");
        assertThat(identityCount(orgId)).isZero();
    }

    @Test
    void samlDisabledOrganizationsAnswerTheUniformMiss() throws Exception {
        String devToken = devToken();
        UUID orgId = createOrganization(devToken, "Nu SAML");

        assertThat(statusOf(HttpMethod.GET, "/api/v1/auth/sso/options?organizationId=" + orgId, null))
                .isEqualTo(404);
        assertThat(statusOf(HttpMethod.GET, "/api/v1/auth/sso/authorize?organizationId=" + orgId, null))
                .isEqualTo(404);
        assertThat(postAcs(orgId, idp.signedResponseFor(acsUrl(orgId), spEntityId(orgId)), null))
                .as("the ACS for an organization without SAML answers the uniform redirect, never a leak")
                .isEqualTo(CONSOLE_CALLBACK + "?error=sso");
        assertThat(failureReason()).isEqualTo("sso_disabled");
    }

    // ------------------------------------------------------------------
    // Helpers: the two browser dances
    // ------------------------------------------------------------------

    /** One started SP-initiated flow: the redirect and its two parameters. */
    private record SpStart(String redirectLocation, String samlRequest, String relayState, String requestId) {}

    private String completeSpFlow(UUID organizationId) {
        SpStart start = startSpFlow(organizationId);
        idp.behave().inResponseTo = start.requestId();
        return postAcs(
                organizationId,
                idp.signedResponseFor(acsUrl(organizationId), spEntityId(organizationId)),
                start.relayState());
    }

    /** Starts the flow at /authorize and reads the IdP-bound redirect. */
    private SpStart startSpFlow(UUID organizationId) {
        String location = client.get()
                .uri("/api/v1/auth/sso/authorize?organizationId=" + organizationId)
                .exchange((request, response) -> {
                    assertThat(response.getStatusCode().value()).isEqualTo(302);
                    return response.getHeaders().getFirst(HttpHeaders.LOCATION);
                });
        assertThat(location).startsWith(MockSamlIdp.SSO_LOCATION);
        Map<String, String> params = queryParams(location);
        String requestXml = SamlAuthnRequest.decodeFromRedirect(params.get("SAMLRequest"));
        Matcher id = REQUEST_ID.matcher(requestXml);
        assertThat(id.find()).as("the AuthnRequest carries its ID").isTrue();
        return new SpStart(location, params.get("SAMLRequest"), params.get("RelayState"), id.group(1));
    }

    /**
     * The IdP-initiated leg: a response with no InResponseTo and no
     * RelayState (whatever the behavior currently asserts -- the
     * default is a clean IdP-initiated shape).
     */
    private String idpInitiated(UUID organizationId) {
        return postAcs(organizationId, idp.signedResponseFor(acsUrl(organizationId), spEntityId(organizationId)), null);
    }

    /** POSTs the SAMLResponse (plus RelayState) to the ACS; answers the console redirect. */
    private String postAcs(UUID organizationId, String samlResponse, String relayState) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("SAMLResponse", samlResponse);
        if (relayState != null) {
            form.add("RelayState", relayState);
        }
        return client.post()
                .uri("/api/v1/auth/sso/saml/" + organizationId + "/acs")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .exchange((request, response) -> {
                    assertThat(response.getStatusCode().value()).isEqualTo(302);
                    return response.getHeaders().getFirst(HttpHeaders.LOCATION);
                });
    }

    private static String spEntityId(UUID organizationId) {
        return REDIRECT_BASE + "/api/v1/auth/sso/saml/" + organizationId + "/metadata";
    }

    private static String acsUrl(UUID organizationId) {
        return REDIRECT_BASE + "/api/v1/auth/sso/saml/" + organizationId + "/acs";
    }

    private static Map<String, String> queryParams(String url) {
        Map<String, String> params = new LinkedHashMap<>();
        for (String pair : url.substring(url.indexOf('?') + 1).split("&")) {
            int equals = pair.indexOf('=');
            params.put(
                    pair.substring(0, equals), URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8));
        }
        return params;
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

    private void putSamlConfig(String token, UUID organizationId, Map<String, Object> overrides) {
        Map<String, Object> body = new HashMap<>();
        body.put("providerType", "SAML");
        body.put("idpMetadataXml", idp.metadataXml());
        body.put("provisioningMode", "JIT");
        body.put("enabled", true);
        body.putAll(overrides);
        client.put()
                .uri("/api/v1/organizations/" + organizationId + "/idp-config")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class);
    }

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

    /** Invites an org-scoped member, lets them register and log in, answers their id. */
    private UUID inviteRegisterAndLogin(String devToken, UUID organizationId, String email, String role) {
        JsonNode invitation = client.post()
                .uri("/api/v1/auth/invitations")
                .header("Authorization", "Bearer " + devToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", email, "organizationId", organizationId.toString(), "role", role))
                .retrieve()
                .body(JsonNode.class);
        JsonNode user = client.post()
                .uri("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "invitationToken",
                        invitation.get("invitationToken").asText(),
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

    private UUID linkedUserId(UUID organizationId) {
        return jdbc.queryForObject(
                "SELECT user_id FROM user_idp_identities WHERE organization_id = ?", UUID.class, organizationId);
    }

    private String membershipRole(UUID organizationId, UUID userId) {
        return jdbc.queryForObject(
                "SELECT role::text FROM organization_members WHERE organization_id = ? AND user_id = ?",
                String.class,
                organizationId,
                userId);
    }

    private static String entityIdOf(String metadataXml) {
        int start = metadataXml.indexOf("entityID=\"") + "entityID=\"".length();
        return metadataXml.substring(start, metadataXml.indexOf('"', start));
    }

    /** The metadata's whole first signing KeyDescriptor block (splice helper). */
    private static String keyDescriptorBlockOf(String metadataXml) {
        int start = metadataXml.indexOf("<md:KeyDescriptor");
        int end = metadataXml.indexOf("</md:KeyDescriptor>") + "</md:KeyDescriptor>".length();
        return metadataXml.substring(start, end);
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

    /** One attack fixture and the audit reason its refusal must carry. */
    private record AttackCase(String name, Consumer<MockSamlIdp.Behavior> behavior, String reason) {}
}
