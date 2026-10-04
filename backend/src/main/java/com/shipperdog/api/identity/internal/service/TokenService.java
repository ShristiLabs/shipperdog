package com.shipperdog.api.identity.internal.service;

import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventPublisher;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.identity.internal.AuthException;
import com.shipperdog.api.identity.internal.AuthenticatedPrincipal;
import com.shipperdog.api.identity.internal.config.AuthProperties;
import com.shipperdog.api.identity.internal.crypto.SecureTokens;
import com.shipperdog.api.identity.internal.entity.PersonalAccessTokenEntity;
import com.shipperdog.api.identity.internal.entity.RefreshTokenEntity;
import com.shipperdog.api.identity.internal.entity.UserEntity;
import com.shipperdog.api.identity.internal.repository.PersonalAccessTokenRepository;
import com.shipperdog.api.identity.internal.repository.RefreshTokenRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues and revokes the identity module's tokens: short-lived HS256 JWT
 * access tokens, rotating JWT refresh tokens (grouped into revocable
 * families), and prefixed opaque personal access tokens for CLI/CI.
 *
 * <p>Organization/tenant claims are attached as soon as a user's membership
 * can be resolved (S1-04/S1-05); until then tokens carry {@code sub} and
 * {@code type} only, and downstream authorization treats them as
 * context-free identities.
 */
@Service
@Transactional
public class TokenService {

    public static final String CLAIM_TYPE = "type";
    public static final String TYPE_ACCESS = "access";
    public static final String TYPE_REFRESH = "refresh";
    public static final String CLAIM_ORG = "org_id";
    public static final String CLAIM_TENANT = "tenant_id";

    private final JwtEncoder encoder;
    private final JwtDecoder decoder;
    private final AuthProperties properties;
    private final Clock clock;
    private final RefreshTokenRepository refreshTokens;
    private final PersonalAccessTokenRepository pats;
    private final AuditEventPublisher audit;

    TokenService(
            JwtEncoder encoder,
            JwtDecoder decoder,
            AuthProperties properties,
            Clock clock,
            RefreshTokenRepository refreshTokens,
            PersonalAccessTokenRepository pats,
            AuditEventPublisher audit) {
        this.encoder = encoder;
        this.decoder = decoder;
        this.properties = properties;
        this.clock = clock;
        this.refreshTokens = refreshTokens;
        this.pats = pats;
        this.audit = audit;
    }

    /** A freshly issued access/refresh pair; the refresh starts a family. */
    public IssuedTokens issueForLogin(UserEntity user) {
        Instant now = Instant.now(clock);
        String refresh = mintRefresh(user.getId(), UUID.randomUUID(), now);
        String access = mintAccess(user.getId(), null, null, now);
        audit(AuditEventType.TOKEN_ISSUED, user.getId(), true, Map.of("kind", "login"));
        return new IssuedTokens(access, refresh, properties.accessTokenTtl().toSeconds());
    }

    /** Rotates a refresh token; reuse of a rotated token kills its family. */
    public IssuedTokens refresh(String rawRefreshToken) {
        Jwt jwt = parse(rawRefreshToken);
        String hash = SecureTokens.sha256Hex(rawRefreshToken);
        RefreshTokenEntity record = refreshTokens.findByTokenHash(hash).orElseThrow(this::invalidRefresh);
        Instant now = Instant.now(clock);
        if (record.getRevokedAt() != null) {
            refreshTokens.revokeFamily(record.getFamilyId(), now);
            audit(AuditEventType.TOKEN_REUSE_DETECTED, record.getUserId(), false, Map.of("kind", "refresh"));
            throw invalidRefresh();
        }
        if (record.getExpiresAt().isBefore(now)) {
            throw invalidRefresh();
        }
        refreshTokens.revokeFamily(record.getFamilyId(), now);
        String rotated = mintRefresh(record.getUserId(), record.getFamilyId(), now);
        String access = mintAccess(record.getUserId(), null, null, now);
        audit(AuditEventType.TOKEN_REFRESHED, record.getUserId(), true, Map.of("kind", "refresh"));
        return new IssuedTokens(access, rotated, properties.accessTokenTtl().toSeconds());
    }

    /** Revokes the refresh family of the presented token (idempotent). */
    void revoke(String rawRefreshToken) {
        try {
            parse(rawRefreshToken);
            refreshTokens
                    .findByTokenHash(SecureTokens.sha256Hex(rawRefreshToken))
                    .ifPresent(record -> {
                        refreshTokens.revokeFamily(record.getFamilyId(), Instant.now(clock));
                        audit(AuditEventType.TOKEN_REVOKED, record.getUserId(), true, Map.of("kind", "refresh"));
                    });
        } catch (JwtException | AuthException e) {
            // Logout stays idempotent: an already-invalid token is simply gone.
        }
    }

    /** Revokes every refresh token of a user (password reset path). */
    void revokeAllForUser(UUID userId) {
        refreshTokens.revokeAllForUser(userId, Instant.now(clock));
    }

    /** Creates a PAT and returns the raw value exactly once. */
    public CreatedPat createPat(UUID userId, String name, Duration expiresIn) {
        Instant now = Instant.now(clock);
        Instant expiresAt = expiresIn == null ? null : now.plus(expiresIn);
        String raw = SecureTokens.personalAccessToken();
        PersonalAccessTokenEntity entity = new PersonalAccessTokenEntity(
                userId, name.trim(), raw.substring(0, 12), SecureTokens.sha256Hex(raw), expiresAt);
        pats.save(entity);
        audit(AuditEventType.PAT_CREATED, userId, true, Map.of("name", name.trim()));
        return new CreatedPat(entity.getId(), name.trim(), entity.getTokenPrefix(), raw, expiresAt);
    }

    public List<PersonalAccessTokenEntity> listPats(UUID userId) {
        return pats.findByUserIdOrderByCreatedAtDesc(userId);
    }

    public void revokePat(UUID userId, UUID patId) {
        PersonalAccessTokenEntity pat = pats.findByIdAndUserId(patId, userId)
                .orElseThrow(() -> new AuthException(AuthException.Code.NOT_FOUND, "personal access token not found"));
        pat.setRevokedAt(Instant.now(clock));
        pats.save(pat);
        audit(AuditEventType.PAT_REVOKED, userId, true, Map.of("patId", patId.toString()));
    }

    /**
     * Authenticates a raw PAT bearer credential, or returns null when it is
     * unknown, revoked, or expired. Used by the PAT authentication filter;
     * never throws for a bad token. The {@code last_used_at} bookkeeping
     * write is throttled to at most one per minute so authenticated
     * requests do not turn into per-request database writes.
     */
    public Authentication authenticatePat(String rawToken) {
        return pats.findByTokenHash(SecureTokens.sha256Hex(rawToken))
                .filter(pat -> pat.getRevokedAt() == null)
                .filter(pat -> pat.getExpiresAt() == null || pat.getExpiresAt().isAfter(Instant.now(clock)))
                .map(pat -> {
                    Instant now = Instant.now(clock);
                    if (pat.getLastUsedAt() == null || pat.getLastUsedAt().isBefore(now.minus(Duration.ofMinutes(1)))) {
                        pat.setLastUsedAt(now);
                        pats.save(pat);
                    }
                    return (Authentication) new UsernamePasswordAuthenticationToken(
                            new AuthenticatedPrincipal(pat.getUserId(), pat.getId()), null, List.of());
                })
                .orElse(null);
    }

    private String mintAccess(UUID userId, UUID orgId, UUID tenantId, Instant now) {
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .subject(userId.toString())
                .issuedAt(now)
                .expiresAt(now.plus(properties.accessTokenTtl()))
                .id(UUID.randomUUID().toString())
                .claim(CLAIM_TYPE, TYPE_ACCESS);
        // org_id/tenant_id are attached only once resolvable (S1-04/S1-05);
        // absent claims mean "no organization context yet", never a default.
        if (orgId != null) {
            claims.claim(CLAIM_ORG, orgId.toString());
        }
        if (tenantId != null) {
            claims.claim(CLAIM_TENANT, tenantId.toString());
        }
        return encode(claims.build());
    }

    private String mintRefresh(UUID userId, UUID familyId, Instant now) {
        String raw = encode(JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .subject(userId.toString())
                .issuedAt(now)
                .expiresAt(now.plus(properties.refreshTokenTtl()))
                .id(UUID.randomUUID().toString())
                .claim(CLAIM_TYPE, TYPE_REFRESH)
                .build());
        refreshTokens.save(new RefreshTokenEntity(
                userId, familyId, SecureTokens.sha256Hex(raw), now.plus(properties.refreshTokenTtl())));
        return raw;
    }

    private String encode(JwtClaimsSet claims) {
        return encoder.encode(JwtEncoderParameters.from(
                        JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }

    private Jwt parse(String rawToken) {
        try {
            Jwt jwt = decoder.decode(rawToken);
            if (!TYPE_REFRESH.equals(jwt.getClaimAsString(CLAIM_TYPE))) {
                throw invalidRefresh();
            }
            return jwt;
        } catch (JwtException e) {
            throw invalidRefresh();
        }
    }

    private AuthException invalidRefresh() {
        return new AuthException(AuthException.Code.INVALID_REFRESH_TOKEN, "refresh token is invalid");
    }

    private void audit(AuditEventType type, UUID userId, boolean success, Map<String, String> detail) {
        audit.publish(new AuditEvent(type, Instant.now(clock), userId, null, null, success, detail));
    }

    /** Access/refresh pair handed to the client. */
    public record IssuedTokens(String accessToken, String refreshToken, long expiresInSeconds) {}

    /** PAT creation result; {@code rawToken} is shown exactly once. */
    public record CreatedPat(UUID id, String name, String tokenPrefix, String rawToken, Instant expiresAt) {}
}
