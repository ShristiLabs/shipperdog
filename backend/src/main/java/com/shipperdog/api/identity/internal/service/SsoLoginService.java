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
import com.shipperdog.api.identity.internal.entity.UserIdpIdentityEntity;
import com.shipperdog.api.identity.internal.oidc.OidcIdTokenVerifier;
import com.shipperdog.api.identity.internal.oidc.OidcProviderClient;
import com.shipperdog.api.identity.internal.repository.OrganizationIdpConfigRepository;
import com.shipperdog.api.identity.internal.repository.SsoAuthorizationRequestRepository;
import com.shipperdog.api.identity.internal.repository.SsoSessionCodeRepository;
import com.shipperdog.api.identity.internal.repository.UserIdpIdentityRepository;
import com.shipperdog.api.identity.internal.repository.UserRepository;
import com.shipperdog.api.organization.OrgRole;
import com.shipperdog.api.organization.OrganizationAccess;
import com.shipperdog.api.organization.OrganizationException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The OIDC single sign-on flow (S14-01, issue #67): backend-driven
 * authorization-code + PKCE redirects, ID-token verification, identity
 * linking, provisioning, group-claim role mapping, IdP-inactive
 * lockout, and the one-time session code that hands the console its
 * tokens. Sessions mint through {@link TokenService#issueForLogin} --
 * an SSO login IS a local session (same claims, same refresh rotation,
 * same revocation surfaces), never a parallel token shape.
 *
 * <p>Provisioning policy: INVITED_ONLY links only subjects whose
 * verified email matches an existing member; JIT additionally
 * provisions a password-less account (least-privilege default role)
 * and admits existing non-member accounts with that same role.
 * Platform administrators never authenticate through SSO -- local
 * login is their break-glass (the story's never-lock-operators-out
 * rule).
 *
 * <p>Every leg audits: SSO_LOGIN_SUCCESS / SSO_LOGIN_FAILURE carry the
 * reason that the uniform browser redirect never reveals;
 * SSO_IDENTITY_LINKED, SSO_USER_JIT_PROVISIONED, SSO_ROLE_MAPPED,
 * SSO_ACCOUNT_LOCKED, and SSO_LOGOUT record the state changes.
 */
@Service
public class SsoLoginService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SsoLoginService.class);

    /**
     * Role privilege rank for mapping resolution: when several mapped
     * groups match, the most privileged wins (an explicit escalation
     * beats an implicit floor). ORG_ADMIN tops; the tail order among
     * the specialist roles is arbitrary but fixed.
     */
    private static final List<OrgRole> ROLE_RANK = List.of(
            OrgRole.VIEWER,
            OrgRole.ORG_MEMBER,
            OrgRole.DEVELOPER,
            OrgRole.QA_ENGINEER,
            OrgRole.APP_OWNER,
            OrgRole.RELEASE_MANAGER,
            OrgRole.SECURITY_REVIEWER,
            OrgRole.COMPLIANCE_REVIEWER,
            OrgRole.ORG_ADMIN);

    private final SsoConfigService configService;
    private final OrganizationIdpConfigRepository configs;
    private final SsoAuthorizationRequestRepository authorizationRequests;
    private final SsoSessionCodeRepository sessionCodes;
    private final UserIdpIdentityRepository identities;
    private final UserRepository users;
    private final OrganizationAccess organizations;
    private final OidcProviderClient provider;
    private final OidcIdTokenVerifier verifier;
    private final ChannelSecrets secrets;
    private final TokenService tokens;
    private final AuditEventPublisher audit;
    private final SsoProperties properties;
    private final Clock clock;

    SsoLoginService(
            SsoConfigService configService,
            OrganizationIdpConfigRepository configs,
            SsoAuthorizationRequestRepository authorizationRequests,
            SsoSessionCodeRepository sessionCodes,
            UserIdpIdentityRepository identities,
            UserRepository users,
            OrganizationAccess organizations,
            OidcProviderClient provider,
            OidcIdTokenVerifier verifier,
            ChannelSecrets secrets,
            TokenService tokens,
            AuditEventPublisher audit,
            SsoProperties properties,
            Clock clock) {
        this.configService = configService;
        this.configs = configs;
        this.authorizationRequests = authorizationRequests;
        this.sessionCodes = sessionCodes;
        this.identities = identities;
        this.users = users;
        this.organizations = organizations;
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
     * anonymous caller learns nothing beyond "no".
     */
    @Transactional(readOnly = true)
    public boolean ssoOffered(UUID organizationId) {
        return configService.enabledConfig(organizationId).isPresent();
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
                configService.enabledConfig(organizationId).orElseThrow(this::ssoOff);
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
                throw refused(null, null, "provider_error");
            }
            if (code == null || code.isBlank() || state == null || state.isBlank()) {
                throw refused(null, null, "invalid_callback");
            }
            SsoAuthorizationRequestEntity request = authorizationRequests
                    .findByStateHash(SecureTokens.sha256Hex(state))
                    .orElse(null);
            if (request == null) {
                throw refused(null, null, "unknown_state");
            }
            // Atomic single-use consume: a racing replay loses here. A
            // lost update re-reads the row to keep the audit's reason
            // honest -- already consumed is a replay, otherwise expired.
            if (authorizationRequests.consumeByStateHash(request.getStateHash(), Instant.now(clock)) == 0) {
                boolean replayed = authorizationRequests
                        .findByStateHash(request.getStateHash())
                        .map(consumed -> consumed.getConsumedAt() != null)
                        .orElse(true);
                throw refused(null, request.getOrganizationId(), replayed ? "unknown_state" : "expired_state");
            }
            UUID organizationId = request.getOrganizationId();
            OrganizationIdpConfigEntity config = configService
                    .enabledConfig(organizationId)
                    .orElseThrow(() -> refused(null, organizationId, "sso_disabled"));
            String clientSecret = secrets.resolve(config.getClientSecretRef())
                    .filter(SecretMaterial::present)
                    .map(SecretMaterial::value)
                    .orElseThrow(() -> refused(null, organizationId, "secret_unresolvable"));
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
                throw refused(null, organizationId, "token_verification");
            }
            UserEntity user = resolveAccount(organizationId, config, claims);
            String sessionCode = SecureTokens.randomToken();
            sessionCodes.save(new SsoSessionCodeEntity(
                    SecureTokens.sha256Hex(sessionCode),
                    user.getId(),
                    organizationId,
                    Instant.now(clock).plus(properties.sessionCodeTtl())));
            audit.publish(new AuditEvent(
                    AuditEventType.SSO_LOGIN_SUCCESS,
                    Instant.now(clock),
                    user.getId(),
                    organizationId,
                    null,
                    true,
                    Map.of(
                            "issuer", config.getIssuerUrl(),
                            "subject", claims.subject(),
                            "provisioningMode", config.getProvisioningMode().name())));
            return consoleRedirect("code=" + urlEncode(sessionCode));
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
        Optional<UserIdpIdentityEntity> link =
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
    // Identity resolution: linking, provisioning, mapping, lockout
    // ------------------------------------------------------------------

    /**
     * Resolves the ShipperDog account for a verified corporate
     * assertion, applying the organization's provisioning policy,
     * group-claim role mapping, and the IdP-inactive lockout.
     */
    private UserEntity resolveAccount(
            UUID organizationId, OrganizationIdpConfigEntity config, OidcIdTokenVerifier.VerifiedClaims claims) {
        String deactivated = organizations
                .statusOf(organizationId)
                .filter("DEACTIVATED"::equals)
                .orElse(null);
        if (deactivated != null) {
            throw refused(null, organizationId, "organization_deactivated");
        }
        if (claims.inactive()) {
            lockForInactivity(organizationId, config, claims);
            throw refused(null, organizationId, "idp_inactive");
        }
        Optional<UserIdpIdentityEntity> existingLink =
                identities.findByOrganizationIdAndSubject(organizationId, claims.subject());
        if (existingLink.isPresent()) {
            UserEntity linked = users.findById(existingLink.get().getUserId()).orElse(null);
            if (linked == null) {
                throw refused(null, organizationId, "linked_user_gone");
            }
            requireLoginAllowed(linked, organizationId);
            applyRoleMapping(organizationId, config, linked, claims);
            return linked;
        }
        if (claims.email() == null || claims.email().isBlank()) {
            throw refused(null, organizationId, "no_email");
        }
        if (config.isRequireVerifiedEmail() && !Boolean.TRUE.equals(claims.emailVerified())) {
            throw refused(null, organizationId, "unverified_email");
        }
        String email = AuthService.normalizeEmail(claims.email());
        Optional<UserEntity> byEmail = users.findByEmail(email);
        if (byEmail.isPresent()) {
            UserEntity user = byEmail.get();
            if (user.isPlatformAdmin()) {
                // Break-glass: platform administrators authenticate
                // locally, never through an organization's provider.
                throw refused(user.getId(), organizationId, "platform_admin_breakglass");
            }
            requireLoginAllowed(user, organizationId);
            boolean member = organizations.activeOrganizationRoles(user.getId()).containsKey(organizationId);
            if (!member && config.getProvisioningMode() == OrganizationIdpConfigEntity.ProvisioningMode.INVITED_ONLY) {
                throw refused(user.getId(), organizationId, "not_invited");
            }
            if (!member) {
                try {
                    organizations.addMember(
                            organizationId,
                            user.getId(),
                            mappedOrDefault(organizationId, config, claims),
                            user.getId());
                } catch (OrganizationException e) {
                    throw refused(user.getId(), organizationId, "provisioning_refused");
                }
            }
            applyRoleMapping(organizationId, config, user, claims);
            link(organizationId, config, user, claims, UserIdpIdentityEntity.LinkKind.EMAIL);
            return user;
        }
        if (config.getProvisioningMode() == OrganizationIdpConfigEntity.ProvisioningMode.INVITED_ONLY) {
            throw refused(null, organizationId, "not_invited");
        }
        int atSign = email.indexOf('@');
        UserEntity provisioned = new UserEntity(
                email,
                null,
                claims.name() == null || claims.name().isBlank()
                        ? email.substring(0, atSign > 0 ? atSign : email.length())
                        : claims.name());
        users.save(provisioned);
        try {
            organizations.addMember(
                    organizationId,
                    provisioned.getId(),
                    mappedOrDefault(organizationId, config, claims),
                    provisioned.getId());
        } catch (OrganizationException e) {
            throw refused(provisioned.getId(), organizationId, "provisioning_refused");
        }
        link(organizationId, config, provisioned, claims, UserIdpIdentityEntity.LinkKind.JIT);
        audit.publish(new AuditEvent(
                AuditEventType.SSO_USER_JIT_PROVISIONED,
                Instant.now(clock),
                provisioned.getId(),
                organizationId,
                null,
                true,
                Map.of(
                        "subject", claims.subject(),
                        "issuer", config.getIssuerUrl(),
                        "role", mappedOrDefault(organizationId, config, claims).name())));
        applyRoleMapping(organizationId, config, provisioned, claims);
        return provisioned;
    }

    /** Status gates every resolved account passes (uniform refusal). */
    private void requireLoginAllowed(UserEntity user, UUID organizationId) {
        if (user.getStatus() == UserEntity.Status.DISABLED) {
            throw refused(user.getId(), organizationId, "account_disabled");
        }
        if (user.getStatus() == UserEntity.Status.LOCKED) {
            throw refused(user.getId(), organizationId, "account_locked");
        }
    }

    /**
     * The IdP-inactive lockout (AC: offboarding follows the IdP): the
     * account crosses to LOCKED and every refresh family is revoked
     * (reason {@code idp_inactive}) -- pre-existing sessions die at
     * their next stateful touch, exactly like a tester deactivation.
     */
    private void lockForInactivity(
            UUID organizationId, OrganizationIdpConfigEntity config, OidcIdTokenVerifier.VerifiedClaims claims) {
        identities
                .findByOrganizationIdAndSubject(organizationId, claims.subject())
                .ifPresent(link -> users.findById(link.getUserId()).ifPresent(user -> {
                    user.setStatus(UserEntity.Status.LOCKED);
                    users.save(user);
                    tokens.revokeRefreshTokens(user.getId(), "idp_inactive");
                    audit.publish(new AuditEvent(
                            AuditEventType.SSO_ACCOUNT_LOCKED,
                            Instant.now(clock),
                            user.getId(),
                            organizationId,
                            null,
                            true,
                            Map.of("issuer", config.getIssuerUrl(), "subject", claims.subject())));
                }));
    }

    /** Records the subject<->user link and its audit line. */
    private void link(
            UUID organizationId,
            OrganizationIdpConfigEntity config,
            UserEntity user,
            OidcIdTokenVerifier.VerifiedClaims claims,
            UserIdpIdentityEntity.LinkKind kind) {
        identities.save(new UserIdpIdentityEntity(
                user.getId(),
                organizationId,
                config.getIssuerUrl(),
                claims.subject(),
                AuthService.normalizeEmail(claims.email()),
                kind));
        audit.publish(new AuditEvent(
                AuditEventType.SSO_IDENTITY_LINKED,
                Instant.now(clock),
                user.getId(),
                organizationId,
                null,
                true,
                Map.of("subject", claims.subject(), "linkKind", kind.name(), "issuer", config.getIssuerUrl())));
    }

    /**
     * Group-claim mapping: the most privileged mapped group the token
     * carries wins; no mapped group leaves the membership untouched
     * (absence never demotes). A refused apply (the last-ORG_ADMIN
     * guard) skips the change and records the refusal -- a mapping
     * never blocks a login.
     */
    private void applyRoleMapping(
            UUID organizationId,
            OrganizationIdpConfigEntity config,
            UserEntity user,
            OidcIdTokenVerifier.VerifiedClaims claims) {
        if (config.getGroupClaimName() == null || claims.groups().isEmpty()) {
            return;
        }
        Map<String, OrgRole> mappings = configService.mappingsOf(config);
        OrgRole mapped = null;
        for (String group : claims.groups()) {
            OrgRole candidate = mappings.get(group);
            if (candidate != null && (mapped == null || rank(candidate) > rank(mapped))) {
                mapped = candidate;
            }
        }
        if (mapped == null) {
            return;
        }
        OrgRole current = organizations.activeOrganizationRoles(user.getId()).get(organizationId);
        if (current == mapped) {
            return;
        }
        try {
            organizations.updateMemberRole(organizationId, user.getId(), mapped, user.getId());
            audit.publish(new AuditEvent(
                    AuditEventType.SSO_ROLE_MAPPED,
                    Instant.now(clock),
                    user.getId(),
                    organizationId,
                    null,
                    true,
                    Map.of(
                            "group", firstMatchedGroup(mappings, claims.groups()),
                            "role.before", current == null ? "" : current.name(),
                            "role.after", mapped.name())));
        } catch (OrganizationException e) {
            audit.publish(new AuditEvent(
                    AuditEventType.SSO_ROLE_MAPPED,
                    Instant.now(clock),
                    user.getId(),
                    organizationId,
                    null,
                    false,
                    Map.of("group", firstMatchedGroup(mappings, claims.groups()), "reason", "role_change_refused")));
        }
    }

    /** The JIT admission role: the mapped role when present, else the configured default. */
    private OrgRole mappedOrDefault(
            UUID organizationId, OrganizationIdpConfigEntity config, OidcIdTokenVerifier.VerifiedClaims claims) {
        Map<String, OrgRole> mappings = configService.mappingsOf(config);
        OrgRole mapped = null;
        for (String group : claims.groups()) {
            OrgRole candidate = mappings.get(group);
            if (candidate != null && (mapped == null || rank(candidate) > rank(mapped))) {
                mapped = candidate;
            }
        }
        if (mapped != null) {
            return mapped;
        }
        return config.getJitDefaultRole() == null ? OrgRole.VIEWER : config.getJitDefaultRole();
    }

    private static String firstMatchedGroup(Map<String, OrgRole> mappings, List<String> groups) {
        List<String> matched = new ArrayList<>(groups);
        matched.retainAll(mappings.keySet());
        return matched.isEmpty() ? "" : matched.getFirst();
    }

    private static int rank(OrgRole role) {
        int index = ROLE_RANK.indexOf(role);
        return index < 0 ? 0 : index;
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

    private static String urlEncode(String value) {
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
     * The callback's uniform refusal: audits SSO_LOGIN_FAILURE with the
     * distinct reason (the only place it exists -- the browser gets the
     * bare {@code error=sso} redirect) and unwinds as the redirect.
     */
    private CallbackRefusal refused(UUID userId, UUID organizationId, String reason) {
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
