package com.shipperdog.api.identity.internal.service;

import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventPublisher;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.identity.internal.AuthException;
import com.shipperdog.api.identity.internal.config.SsoProperties;
import com.shipperdog.api.identity.internal.crypto.SecureTokens;
import com.shipperdog.api.identity.internal.entity.OrganizationIdpConfigEntity;
import com.shipperdog.api.identity.internal.entity.SamlAuthorizationRequestEntity;
import com.shipperdog.api.identity.internal.entity.UserEntity;
import com.shipperdog.api.identity.internal.repository.SamlAuthorizationRequestRepository;
import com.shipperdog.api.identity.internal.saml.SamlAuthnRequest;
import com.shipperdog.api.identity.internal.saml.SamlIdpMetadata;
import com.shipperdog.api.identity.internal.saml.SamlMetadataParser;
import com.shipperdog.api.identity.internal.saml.SamlResponseValidator;
import com.shipperdog.api.identity.internal.saml.SamlSpCredentials;
import com.shipperdog.api.identity.internal.saml.SamlValidationException;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The SAML SP flow (S14-02, issue #68): SP metadata generation, the
 * SP-initiated Redirect-binding AuthnRequest, and the POST-binding
 * Assertion Consumer Service -- SP-initiated AND IdP-initiated, which
 * land on the same ACS (the request's presence distinguishes them).
 *
 * <p>The heavy lifting is deliberately NOT here: signature/audience/
 * timestamp/subject validation lives in {@link SamlResponseValidator}
 * (the critical surface), and identity linking, provisioning, and
 * role mapping live in the shared {@link SsoAccountResolver} -- SAML
 * is the thin second frontend over the S14-01 semantics the story
 * demands. Sessions mint through the same one-time session code the
 * OIDC flow uses; the console's exchange is provider-blind.
 *
 * <p>Every ACS failure audits {@code SSO_LOGIN_FAILURE} with a
 * distinct {@code saml_*} reason and answers the SAME uniform console
 * redirect as the OIDC flow ({@code error=sso}) -- the browser never
 * learns which leg refused. Validation failures also log WARN: the
 * alerting surface for a rotated certificate or a hostile payload
 * (the metadata-rotation acceptance criterion).
 */
@Service
public class SamlLoginService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SamlLoginService.class);

    /** The ceiling on a base64 SAMLResponse body (the XML cap applies after decode). */
    private static final int MAX_RESPONSE_CHARS = 512 * 1024;

    private final SsoConfigService configService;
    private final SsoAccountResolver resolver;
    private final SamlAuthorizationRequestRepository authorizationRequests;
    private final AuditEventPublisher audit;
    private final SsoProperties properties;
    private final Clock clock;
    private final SamlSpCredentials spCredentials;

    SamlLoginService(
            SsoConfigService configService,
            SsoAccountResolver resolver,
            SamlAuthorizationRequestRepository authorizationRequests,
            AuditEventPublisher audit,
            SsoProperties properties,
            Clock clock) {
        this.configService = configService;
        this.resolver = resolver;
        this.authorizationRequests = authorizationRequests;
        this.audit = audit;
        this.properties = properties;
        this.clock = clock;
        // Fail fast at startup on an unparseable or mismatched pair;
        // empty configuration yields the unsigned posture.
        this.spCredentials = SamlSpCredentials.parse(properties.samlSpSigningKey(), properties.samlSpSigningCert());
    }

    // ------------------------------------------------------------------
    // SP identity: entityID, ACS, metadata
    // ------------------------------------------------------------------

    /** This organization's SP entityID: its own metadata route URL (stable per organization). */
    public String spEntityId(UUID organizationId) {
        return URI.create(properties.redirectBaseUri() + "/api/v1/auth/sso/saml/" + organizationId + "/metadata")
                .normalize()
                .toString();
    }

    /** This deployment's ACS for the organization (Recipient/Destination checks pin on it). */
    public String acsUrl(UUID organizationId) {
        return URI.create(properties.redirectBaseUri() + "/api/v1/auth/sso/saml/" + organizationId + "/acs")
                .normalize()
                .toString();
    }

    /**
     * The SP metadata XML an administrator registers with the IdP:
     * entityID, POST-binding ACS, and the signing KeyDescriptor only
     * when the deployment carries SP credentials.
     *
     * @throws AuthException NOT_FOUND when the organization has no
     *         enabled SAML configuration (uniform miss, like options)
     */
    @Transactional(readOnly = true)
    public String spMetadataXml(UUID organizationId) {
        configService.enabledSamlConfig(organizationId).orElseThrow(this::samlOff);
        return SamlMetadataParser.spMetadataXml(
                spEntityId(organizationId),
                acsUrl(organizationId),
                spCredentials == null ? null : spCredentials.certificate());
    }

    // ------------------------------------------------------------------
    // SP-initiated: the AuthnRequest redirect
    // ------------------------------------------------------------------

    /**
     * Starts the SP-initiated flow: persists the AuthnRequest ID and
     * RelayState (both hashed) and answers the IdP's HTTP-Redirect
     * binding SSO location with the DEFLATE+b64 AuthnRequest, the
     * RelayState, and -- when the deployment configured SP credentials
     * -- the Redirect-binding RSA-SHA256 signature.
     */
    @Transactional
    public String beginAuthorization(UUID organizationId) {
        OrganizationIdpConfigEntity config =
                configService.enabledSamlConfig(organizationId).orElseThrow(this::samlOff);
        SamlIdpMetadata metadata = SamlMetadataParser.parseIdpMetadata(config.getSamlMetadataXml());
        // The same opportunistic hygiene as the OIDC start: expired
        // SAML flow state and session codes never accumulate.
        Instant now = Instant.now(clock);
        authorizationRequests.deleteByExpiresAtBefore(now);
        String requestId = SamlAuthnRequest.newRequestId();
        String relayState = SecureTokens.randomToken();
        authorizationRequests.save(new SamlAuthorizationRequestEntity(
                organizationId,
                SecureTokens.sha256Hex(requestId),
                SecureTokens.sha256Hex(relayState),
                now.plus(properties.authorizationRequestTtl())));
        String encodedRequest = SsoLoginService.urlEncode(SamlAuthnRequest.encodeForRedirect(SamlAuthnRequest.xml(
                spEntityId(organizationId), acsUrl(organizationId), metadata.ssoRedirectLocation(), requestId)));
        String encodedRelayState = SsoLoginService.urlEncode(relayState);
        StringBuilder url = new StringBuilder(metadata.ssoRedirectLocation());
        url.append(metadata.ssoRedirectLocation().contains("?") ? '&' : '?');
        url.append("SAMLRequest=").append(encodedRequest);
        url.append("&RelayState=").append(encodedRelayState);
        if (spCredentials != null) {
            url.append("&SigAlg=").append(SsoLoginService.urlEncode(SamlSpCredentials.SIGNATURE_ALGORITHM_URI));
            url.append("&Signature=")
                    .append(SsoLoginService.urlEncode(Base64.getEncoder()
                            .encodeToString(spCredentials.signRedirectBinding(encodedRequest, encodedRelayState))));
        }
        return url.toString();
    }

    // ------------------------------------------------------------------
    // The ACS: both flows land here
    // ------------------------------------------------------------------

    /**
     * Consumes a POST-binding SAMLResponse. Validation runs fully
     * (signature, conditions, subject) before any flow state is
     * consumed; an SP-initiated response must repeat an unconsumed
     * request's ID (consumed atomically -- a racing replay loses) and
     * the browser's RelayState must hash-match the stored one. An
     * IdP-initiated response carries no InResponseTo and no state is
     * consumed. Every failure answers the uniform console redirect;
     * the audit line alone carries the reason.
     */
    @Transactional
    public String handleAcs(UUID organizationId, String samlResponse, String relayState) {
        try {
            OrganizationIdpConfigEntity config = configService
                    .enabledSamlConfig(organizationId)
                    .orElseThrow(() -> acsRefusal(null, organizationId, "sso_disabled"));
            if (samlResponse == null || samlResponse.isBlank() || samlResponse.length() > MAX_RESPONSE_CHARS) {
                throw acsRefusal(null, organizationId, SamlResponseValidator.REASON_MALFORMED);
            }
            byte[] responseXml;
            try {
                responseXml = Base64.getMimeDecoder().decode(samlResponse.trim());
            } catch (RuntimeException e) {
                throw acsRefusal(null, organizationId, SamlResponseValidator.REASON_MALFORMED);
            }
            SamlIdpMetadata metadata = SamlMetadataParser.parseIdpMetadata(config.getSamlMetadataXml());
            SamlResponseValidator.ValidatedAssertion assertion = SamlResponseValidator.validate(
                    responseXml,
                    new SamlResponseValidator.Requirements(
                            config.getSamlIdpEntityId(),
                            spEntityId(organizationId),
                            acsUrl(organizationId),
                            config.isSamlWantResponseSigned(),
                            config.isSamlWantAssertionsSigned(),
                            config.getGroupClaimName(),
                            metadata.signingCertificates()),
                    Instant.now(clock),
                    properties.clockSkew());
            if (assertion.inResponseTo() != null) {
                requireMatchingRequest(organizationId, assertion.inResponseTo(), relayState);
            }
            UserEntity user = resolver.resolve(
                    organizationId,
                    config,
                    new SsoAccountResolver.ExternalIdentity(
                            assertion.nameId(),
                            assertion.email(),
                            Boolean.TRUE,
                            assertion.displayName(),
                            assertion.groups(),
                            false),
                    config.getSamlIdpEntityId());
            String sessionCode = resolver.issueSessionCode(user, organizationId);
            resolver.auditLoginSuccess(user, organizationId, config, config.getSamlIdpEntityId(), assertion.nameId());
            return consoleRedirect("code=" + SsoLoginService.urlEncode(sessionCode));
        } catch (SamlValidationException validation) {
            // The critical surface refused the payload: the reason
            // reaches the audit line and the WARN log (the rotation
            // alerting surface), the browser gets the uniform miss.
            log.warn("a SAML response failed validation: {}", validation.reason());
            audit.publish(new AuditEvent(
                    AuditEventType.SSO_LOGIN_FAILURE,
                    Instant.now(clock),
                    null,
                    organizationId,
                    null,
                    false,
                    Map.of("reason", validation.reason())));
            return consoleRedirect("error=sso");
        } catch (SsoRefusalException refusal) {
            // The resolver already published the audit line with the
            // distinct reason; the browser gets the uniform redirect.
            return consoleRedirect("error=sso");
        } catch (AcsRefusal refusal) {
            // The flow-state legs (disabled config, malformed body,
            // unknown/replayed request, RelayState mismatch) audited
            // their reason inside acsRefusal; render the same uniform
            // redirect.
            return consoleRedirect("error=sso");
        } catch (RuntimeException e) {
            // An unexpected internal fault (never the payload's
            // content -- those are SamlValidationException) answers
            // the SAME uniform redirect the OIDC callback answers, so
            // the failure surface never leaks which leg broke. The
            // audit line carries the internal label; the log carries
            // the exception's class only -- never payload material.
            log.warn("the SAML ACS failed internally: {}", e.getClass().getSimpleName());
            audit.publish(new AuditEvent(
                    AuditEventType.SSO_LOGIN_FAILURE,
                    Instant.now(clock),
                    null,
                    organizationId,
                    null,
                    false,
                    Map.of("reason", "saml_internal")));
            return consoleRedirect("error=sso");
        }
    }

    /**
     * The SP-initiated state check: the repeated AuthnRequest ID must
     * resolve to this organization's request, still unconsumed and
     * unexpired, the consume must win atomically (a racing replay
     * loses), and the RelayState the browser returns must hash-match
     * what went out.
     */
    private void requireMatchingRequest(UUID organizationId, String inResponseTo, String relayState) {
        String requestIdHash = SecureTokens.sha256Hex(inResponseTo);
        SamlAuthorizationRequestEntity request =
                authorizationRequests.findByRequestIdHash(requestIdHash).orElse(null);
        if (request == null || !request.getOrganizationId().equals(organizationId)) {
            throw acsRefusal(null, organizationId, "unknown_request");
        }
        if (request.getConsumedAt() != null) {
            throw acsRefusal(null, organizationId, "replayed_request");
        }
        if (!request.getExpiresAt().isAfter(Instant.now(clock))) {
            throw acsRefusal(null, organizationId, "expired_request");
        }
        if (authorizationRequests.consumeByRequestIdHash(requestIdHash, Instant.now(clock)) == 0) {
            // Another callback consumed it between the read and the
            // consume -- the racing replay loses with the same reason.
            throw acsRefusal(null, organizationId, "replayed_request");
        }
        if (relayState == null
                || relayState.isBlank()
                || !SecureTokens.sha256Hex(relayState).equals(request.getRelayStateHash())) {
            throw acsRefusal(null, organizationId, "relay_mismatch");
        }
    }

    /** The console landing redirect with one query parameter appended. */
    private String consoleRedirect(String parameter) {
        String base = properties.consoleCallbackUri().toString();
        return base + (base.contains("?") ? '&' : '?') + parameter;
    }

    /**
     * The ACS's refusal: audits SSO_LOGIN_FAILURE with the distinct
     * reason and unwinds as the carrier the caller renders uniformly.
     */
    private AcsRefusal acsRefusal(UUID userId, UUID organizationId, String reason) {
        audit.publish(new AuditEvent(
                AuditEventType.SSO_LOGIN_FAILURE,
                Instant.now(clock),
                userId,
                organizationId,
                null,
                false,
                Map.of("reason", reason)));
        return new AcsRefusal();
    }

    /** Control-flow carrier for the ACS's uniform failure redirect. */
    private static final class AcsRefusal extends RuntimeException {
        AcsRefusal() {
            super(null, null, false, false);
        }
    }

    private AuthException samlOff() {
        return new AuthException(
                AuthException.Code.NOT_FOUND, "single sign-on is not configured for this organization");
    }
}
