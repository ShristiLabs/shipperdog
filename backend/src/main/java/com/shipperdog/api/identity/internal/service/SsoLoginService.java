package com.shipperdog.api.identity.internal.service;

import com.nimbusds.jose.jwk.JWKSet;
import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventPublisher;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.distribution.ChannelSecrets;
import com.shipperdog.api.distribution.SecretMaterial;
import com.shipperdog.api.identity.internal.AuthException;
import com.shipperdog.api.identity.internal.config.SsoProperties;
import com.shipperdog.api.identity.internal.crypto.SecureTokens;
import com.shipperdog.api.identity.internal.entity.OrganizationIdpConfigEntity;
import com.shipperdog.api.identity.internal.entity.SsoAuthorizationRequestEntity;
import com.shipperdog.api.identity.internal.entity.SsoSessionCodeEntity;
import com.shipperdog.api.identity.internal.entity.UserEntity;
import com.shipperdog.api.identity.internal.oidc.OidcIdTokenVerifier;
import com.shipperdog.api.identity.internal.oidc.OidcProviderClient;
import com.shipperdog.api.identity.internal.repository.OrganizationIdpConfigRepository;
import com.shipperdog.api.identity.internal.repository.SsoAuthorizationRequestRepository;
import com.shipperdog.api.identity.internal.repository.SsoSessionCodeRepository;
import com.shipperdog.api.identity.internal.repository.UserIdpIdentityRepository;
import com.shipperdog.api.identity.internal.repository.UserRepository;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The OIDC single sign-on flow (S14-01, issue #67): backend-driven
 * authorization-code + PKCE redirects, ID-token verification, and the
 * one-time session code that hands the console its tokens. Since
 * S14-02 the identity resolution -- linking, provisioning, group-claim
 * mapping, IdP-inactive lockout -- lives in the provider-neutral
 * {@link SsoAccountResolver} shared with the SAML flow; this service
 * is the OIDC frontend alone. Sessions mint through
 * {@link TokenService#issueForLogin} -- an SSO login IS a local
 * session (same claims, same refresh rotation, same revocation
 * surfaces), never a parallel token shape.
 *
 * <p>Every leg audits: SSO_LOGIN_SUCCESS / SSO_LOGIN_FAILURE carry the
 * reason that the uniform browser redirect never reveals;
 * SSO_IDENTITY_LINKED, SSO_USER_JIT_PROVISIONED, SSO_ROLE_MAPPED,
 * SSO_ACCOUNT_LOCKED, and SSO_LOGOUT record the state changes.
 */
@Service
public class SsoLoginService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SsoLoginService.class);

    private final SsoConfigService configService;
    private final SsoAccountResolver resolver;
    private final OrganizationIdpConfigRepository configs;
    private final SsoAuthorizationRequestRepository authorizationRequests;
    private final SsoSessionCodeRepository sessionCodes;
    private final UserIdpIdentityRepository identities;
    private final UserRepository users;
    private final OidcProviderClient provider;
    private final OidcIdTokenVerifier verifier;
    private final ChannelSecrets secrets;
    private final TokenService tokens;
    private final AuditEventPublisher audit;
    private final SsoProperties properties;
    private final Clock clock;

    SsoLoginService(
            SsoConfigService configService,
            SsoAccountResolver resolver,
            OrganizationIdpConfigRepository configs,
            SsoAuthorizationRequestRepository authorizationRequests,
            SsoSessionCodeRepository sessionCodes,
            UserIdpIdentityRepository identities,
            UserRepository users,
            OidcProviderClient provider,
            OidcIdTokenVerifier verifier,
            ChannelSecrets secrets,
            TokenService tokens,
            AuditEventPublisher audit,
            SsoProperties properties,
            Clock clock) {
        this.configService = configService;
        this.resolver = resolver;
        this.configs = configs;
        this.authorizationRequests = authorizationRequests;
        this.sessionCodes = sessionCodes;
        this.identities = identities;
        this.users = users;
        this.provider = provider;
        this.verifier = verifier;
        this.secrets = secrets;
        this.tokens = tokens;
        this.audit = audit;
        this.properties = properties;
        this.clock = clock;
    }

    // ------------------------------------------------------------------
    // Login start: the options probe and the authorization redirect
    // ------------------------------------------------------------------

    /**
     * Whether the organization offers SSO (the console login's probe).
     * Uniform miss for unknown, unconfigured, and disabled alike: the
     * anonymous caller learns nothing beyond "no". Any provider kind
     * counts (OIDC since S14-01, SAML since S14-02).
     */
    @Transactional(readOnly = true)
    public boolean ssoOffered(UUID organizationId) {
        return configService.enabledProviderConfig(organizationId).isPresent();
    }

    /** The provider kind the organization's enabled SSO configuration uses. */
    @Transactional(readOnly = true)
    public Optional<String> providerTypeOf(UUID organizationId) {
        return configService.enabledProviderConfig(organizationId).map(OrganizationIdpConfigEntity::getProviderType);
    }

    /** The redirect URI registered with every identity provider. */
    public String callbackUri() {
        return URI.create(properties.redirectBaseUri() + "/api/v1/auth/sso/callback")
                .normalize()
                .toString();
    }

    /**
     * Starts the authorization-code flow: persists state/nonce/PKCE
     * verifier and answers the provider's authorization endpoint URL
     * (with {@code code_challenge_method=S256}).
     */
    @Transactional
    public String beginAuthorization(UUID organizationId) {
        OrganizationIdpConfigEntity config =
                configService.enabledOidcConfig(organizationId).orElseThrow(this::ssoOff);
        OidcProviderClient.OidcEndpoints endpoints;
        try {
            endpoints = provider.endpointsFor(config.getIssuerUrl());
        } catch (RuntimeException e) {
            // Discovery unreachable/incomplete: a problem+json refusal,
            // never a 5xx from a third party's outage.
            throw new AuthException(AuthException.Code.INVALID_REQUEST, "identity provider discovery failed");
        }
        // Opportunistic sweep of the flow-state tables (review finding:
        // expired rows must not accumulate; every login start makes the
        // cheap delete a bounded, self-maintaining hygiene step).
        Instant now = Instant.now(clock);
        authorizationRequests.deleteByExpiresAtBefore(now);
        sessionCodes.deleteByExpiresAtBefore(now);
        String state = SecureTokens.randomToken();
        String nonce = SecureTokens.randomToken();
        String codeVerifier = SecureTokens.randomToken();
        authorizationRequests.save(new SsoAuthorizationRequestEntity(
                organizationId,
                SecureTokens.sha256Hex(state),
                SecureTokens.sha256Hex(nonce),
                codeVerifier,
                now.plus(properties.authorizationRequestTtl())));
        StringBuilder url = new StringBuilder(endpoints.authorizationEndpoint());
        url.append(endpoints.authorizationEndpoint().contains("?") ? '&' : '?');
        url.append("client_id=").append(urlEncode(config.getClientId()));
        url.append("&redirect_uri=").append(urlEncode(callbackUri()));
        url.append("&response_type=code");
        url.append("&scope=").append(urlEncode(config.getScopes()));
        url.append("&state=").append(urlEncode(state));
        url.append("&nonce=").append(urlEncode(nonce));
        url.append("&code_challenge=").append(urlEncode(pkceChallenge(codeVerifier)));
        url.append("&code_challenge_method=S256");
        return url.toString();
    }

    // ------------------------------------------------------------------
    // Callback: exchange, verify, resolve the account, hand the code
    // ------------------------------------------------------------------

    /**
     * Handles the provider's redirect back. Every failure audits
     * SSO_LOGIN_FAILURE with the distinct reason and answers the SAME
     * uniform console redirect with {@code error=sso} -- the browser
     * never learns which leg refused. Success redirects with the
     * one-time session code.
     *
     * <p>Transactional end to end (every path RETURNS -- no rollback can
     * swallow a failure audit): the state consume, the account
     * resolution, and the session-code mint commit atomically.
     */
    @Transactional
    public String handleCallback(String code, String state, String error) {
        try {
            if (error != null && !error.isBlank()) {
                throw oidcRefusal(null, null, "provider_error");
            }
            if (code == null || code.isBlank() || state == null || state.isBlank()) {
                throw oidcRefusal(null, null, "invalid_callback");
            }
            SsoAuthorizationRequestEntity request = authorizationRequests
                    .findByStateHash(SecureTokens.sha256Hex(state))
                    .orElse(null);
            if (request == null) {
                throw oidcRefusal(null, null, "unknown_state");
            }
            // Atomic single-use consume: a racing replay loses here. A
            // lost update re-reads the row to keep the audit's reason
            // honest -- already consumed is a replay, otherwise expired.
            if (authorizationRequests.consumeByStateHash(request.getStateHash(), Instant.now(clock)) == 0) {
                boolean replayed = authorizationRequests
                        .findByStateHash(request.getStateHash())
                        .map(consumed -> consumed.getConsumedAt() != null)
                        .orElse(true);
                throw oidcRefusal(null, request.getOrganizationId(), replayed ? "unknown_state" : "expired_state");
            }
            UUID organizationId = request.getOrganizationId();
            OrganizationIdpConfigEntity config = configService
                    .enabledOidcConfig(organizationId)
                    .orElseThrow(() -> oidcRefusal(null, organizationId, "sso_disabled"));
            String clientSecret = secrets.resolve(config.getClientSecretRef())
                    .filter(SecretMaterial::present)
                    .map(SecretMaterial::value)
                    .orElseThrow(() -> oidcRefusal(null, organizationId, "secret_unresolvable"));
            OidcProviderClient.OidcEndpoints endpoints = provider.endpointsFor(config.getIssuerUrl());
            OidcProviderClient.TokenResponse exchanged = provider.exchangeCode(
                    endpoints, config.getClientId(), clientSecret, code, request.getCodeVerifier(), callbackUri());
            JWKSet keySet = provider.keySetFor(endpoints.jwksUri());
            OidcIdTokenVerifier.VerifiedClaims claims = verifier.verify(
                    exchanged.idToken(),
                    config.getIssuerUrl(),
                    config.getClientId(),
                    request.getNonceHash(),
                    keySet,
                    config.getGroupClaimName(),
                    config.getInactiveClaimName());
            if (claims == null) {
                throw oidcRefusal(null, organizationId, "token_verification");
            }
            UserEntity user = resolver.resolve(
                    organizationId,
                    config,
                    new SsoAccountResolver.ExternalIdentity(
                            claims.subject(),
                            claims.email(),
                            claims.emailVerified(),
                            claims.name(),
                            claims.groups(),
                            claims.inactive()),
                    config.getIssuerUrl());
            String sessionCode = resolver.issueSessionCode(user, organizationId);
            resolver.auditLoginSuccess(user, organizationId, config, config.getIssuerUrl(), claims.subject());
            return consoleRedirect("code=" + urlEncode(sessionCode));
        } catch (SsoRefusalException refusal) {
            // The resolver already published the audit line with the
            // distinct reason; the browser gets the uniform redirect.
            return consoleRedirect("error=sso");
        } catch (CallbackRefusal refusal) {
            return consoleRedirect("error=sso");
        } catch (RuntimeException e) {
            // The outbound legs (discovery cache, exchange, JWKS) throw
            // on a misbehaving provider; the browser gets the same
            // uniform failure redirect, the audit line carries the
            // reason. The exception's class reaches the log (never the
            // token material) so an internal fault is diagnosable
            // beside its provider_exchange label.
            log.warn("the SSO callback's provider leg failed: {}", e.getClass().getSimpleName());
            audit.publish(new AuditEvent(
                    AuditEventType.SSO_LOGIN_FAILURE,
                    Instant.now(clock),
                    null,
                    null,
                    null,
                    false,
                    Map.of("reason", "provider_exchange")));
            return consoleRedirect("error=sso");
        }
    }

    /** The console landing redirect with one query parameter appended. */
    private String consoleRedirect(String parameter) {
        String base = properties.consoleCallbackUri().toString();
        return base + (base.contains("?") ? '&' : '?') + parameter;
    }

    /**
     * Exchanges the one-time session code for the session pair -- the
     * same issuance a local login receives. Single-use: a replay
     * answers the uniform INVALID_SSO_SESSION_CODE.
     */
    @Transactional
    public TokenService.IssuedTokens exchangeSessionCode(String rawCode) {
        if (rawCode == null || rawCode.isBlank()) {
            throw invalidSessionCode();
        }
        SsoSessionCodeEntity sessionCode =
                sessionCodes.findByCodeHash(SecureTokens.sha256Hex(rawCode)).orElseThrow(this::invalidSessionCode);
        // Atomic single-use consume: an expired or already-consumed code
        // (and any racing replay) answers the uniform refusal.
        if (sessionCodes.consumeByCodeHash(sessionCode.getCodeHash(), Instant.now(clock)) == 0) {
            throw invalidSessionCode();
        }
        UserEntity user = users.findById(sessionCode.getUserId()).orElseThrow(this::invalidSessionCode);
        return tokens.issueForLogin(user);
    }

    // ------------------------------------------------------------------
    // Back-channel logout: the IdP ends the local session
    // ------------------------------------------------------------------

    /**
     * OIDC Back-Channel Logout (S14-01): verifies the logout token
     * with the same pinned-algorithm JWKS machinery as ID tokens (no
     * nonce; the {@code events} claim names the spec's logout event),
     * resolves the linked identity, and revokes the user's refresh
     * families. IdP-initiated logout and IdP session expiry land here
     * alike -- both are the provider saying the session is over.
     * Unknown subjects answer 200 per the spec (nothing to end).
     */
    public void backChannelLogout(String logoutToken) {
        if (logoutToken == null || logoutToken.isBlank()) {
            throw invalidRequest("logout_token is required");
        }
        com.nimbusds.jwt.SignedJWT jwt;
        String issuer;
        String subject;
        try {
            jwt = com.nimbusds.jwt.SignedJWT.parse(logoutToken);
            issuer = jwt.getJWTClaimsSet().getIssuer();
            subject = jwt.getJWTClaimsSet().getSubject();
        } catch (java.text.ParseException e) {
            throw invalidRequest("logout token is malformed");
        }
        if (!OidcIdTokenVerifier.ACCEPTED_ALGORITHMS.contains(jwt.getHeader().getAlgorithm())) {
            throw invalidRequest("logout token algorithm is not accepted");
        }
        if (issuer == null || subject == null) {
            throw invalidRequest("logout token carries no issuer or subject");
        }
        Optional<OrganizationIdpConfigEntity> config =
                configs.findFirstByIssuerUrlAndProviderTypeAndEnabledTrue(issuer, "OIDC");
        if (config.isEmpty()) {
            // Not one of our providers: the spec's 200 says "done" --
            // the audit line records what arrived, nothing is ended.
            audit.publish(new AuditEvent(
                    AuditEventType.SSO_LOGOUT,
                    Instant.now(clock),
                    null,
                    null,
                    null,
                    false,
                    Map.of("issuer", issuer, "outcome", "unknown_issuer")));
            return;
        }
        OidcProviderClient.OidcEndpoints endpoints = provider.endpointsFor(issuer);
        JWKSet keySet = provider.keySetFor(endpoints.jwksUri());
        OidcIdTokenVerifier.VerifiedClaims claims =
                verifier.verify(logoutToken, issuer, config.get().getClientId(), null, keySet, null, null);
        if (claims == null) {
            throw invalidRequest("logout token failed verification");
        }
        // The spec's shape: a logout token carries the backchannel-logout
        // events claim and never a nonce -- a replayed ID token is not a
        // logout token (review finding).
        try {
            java.util.Map<String, Object> events = jwt.getJWTClaimsSet().getJSONObjectClaim("events");
            if (events == null || !events.containsKey("http://schemas.openid.net/event/backchannel-logout")) {
                throw invalidRequest("logout token carries no backchannel-logout event");
            }
            if (jwt.getJWTClaimsSet().getStringClaim("nonce") != null) {
                throw invalidRequest("logout token must not carry a nonce");
            }
        } catch (java.text.ParseException e) {
            throw invalidRequest("logout token is malformed");
        }
        Optional<com.shipperdog.api.identity.internal.entity.UserIdpIdentityEntity> link =
                identities.findByOrganizationIdAndSubject(config.get().getOrganizationId(), subject);
        if (link.isEmpty()) {
            return;
        }
        UserEntity user = users.findById(link.get().getUserId()).orElse(null);
        if (user == null) {
            return;
        }
        tokens.revokeRefreshTokens(user.getId(), "idp_logout");
        audit.publish(new AuditEvent(
                AuditEventType.SSO_LOGOUT,
                Instant.now(clock),
                user.getId(),
                config.get().getOrganizationId(),
                null,
                true,
                Map.of("issuer", issuer, "subject", subject)));
    }

    // ------------------------------------------------------------------
    // Small helpers
    // ------------------------------------------------------------------

    private static String pkceChallenge(String codeVerifier) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(digest.digest(codeVerifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private AuthException ssoOff() {
        return new AuthException(
                AuthException.Code.NOT_FOUND, "single sign-on is not configured for this organization");
    }

    private AuthException invalidSessionCode() {
        return new AuthException(AuthException.Code.INVALID_SSO_SESSION_CODE, "session code is invalid or expired");
    }

    private AuthException invalidRequest(String message) {
        return new AuthException(AuthException.Code.INVALID_REQUEST, message);
    }

    /**
     * The callback's OIDC-local refusal: audits SSO_LOGIN_FAILURE with
     * the distinct reason (the browser gets the bare {@code error=sso}
     * redirect) and unwinds as the redirect carrier.
     */
    private CallbackRefusal oidcRefusal(UUID userId, UUID organizationId, String reason) {
        audit.publish(new AuditEvent(
                AuditEventType.SSO_LOGIN_FAILURE,
                Instant.now(clock),
                userId,
                organizationId,
                null,
                false,
                Map.of("reason", reason)));
        return new CallbackRefusal();
    }

    /** Control-flow carrier for the callback's uniform failure redirect. */
    private static final class CallbackRefusal extends RuntimeException {
        CallbackRefusal() {
            super(null, null, false, false);
        }
    }
}
