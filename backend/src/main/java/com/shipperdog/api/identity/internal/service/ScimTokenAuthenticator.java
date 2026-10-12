package com.shipperdog.api.identity.internal.service;

import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventPublisher;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.identity.internal.crypto.SecureTokens;
import com.shipperdog.api.identity.internal.entity.ScimConfigEntity;
import com.shipperdog.api.identity.internal.entity.ScimTokenEntity;
import com.shipperdog.api.identity.internal.repository.ScimConfigRepository;
import com.shipperdog.api.identity.internal.repository.ScimTokenRepository;
import com.shipperdog.api.identity.internal.scim.ScimException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The SCIM routes' bearer-token authority (S14-03, issue #69): the
 * per-organization connector token IS the request's authentication —
 * the webhook-receiver and download-proxy doctrine (the routes sit
 * outside {@code /api/v1} and carry no session; the token's hash
 * lookup against {@code scim_tokens} is the entire authority).
 *
 * <p>Uniform-rejection discipline: a missing, malformed, unknown,
 * rotated-away, or disabled-connector request all answer the SAME
 * 401 SCIM error — which organization refused, whether a connector
 * is configured, and which leg failed never appear in the answer.
 * The distinct reason rides the SCIM_REQUEST_REJECTED audit line
 * only, exactly like the SSO refusal discipline.
 *
 * <p>The lookup is by SHA-256 hex hash (the PAT posture: the stored
 * digest and the presented token are compared through a database
 * equality over digests, and the digest comparison itself runs in
 * {@link MessageDigest#isEqual constant time} as belt-and-braces for
 * any future replay of the lookup against a non-indexed shape).
 */
@Service
public class ScimTokenAuthenticator {

    /** The token prefix — the SCIM counterpart of the {@code sdp_} PAT convention. */
    public static final String TOKEN_PREFIX = "sds_";

    private final ScimTokenRepository tokens;
    private final ScimConfigRepository configs;
    private final AuditEventPublisher audit;
    private final Clock clock;

    ScimTokenAuthenticator(
            ScimTokenRepository tokens, ScimConfigRepository configs, AuditEventPublisher audit, Clock clock) {
        this.tokens = tokens;
        this.configs = configs;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Resolves the organization a request's bearer credential speaks
     * for, or refuses uniformly. A resolved token whose organization
     * carries no ENABLED configuration is the same refusal (the
     * provisioning freeze answers nothing).
     */
    @Transactional
    public UUID authenticate(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            throw refused("missing_token", null);
        }
        String presented = authorizationHeader.substring("Bearer ".length()).trim();
        if (presented.isEmpty()) {
            throw refused("missing_token", null);
        }
        String hash = SecureTokens.sha256Hex(presented);
        ScimTokenEntity token = tokens.findByTokenHash(hash).orElse(null);
        if (token == null
                || !MessageDigest.isEqual(
                        hash.getBytes(StandardCharsets.US_ASCII),
                        token.getTokenHash().getBytes(StandardCharsets.US_ASCII))) {
            throw refused("invalid_token", null);
        }
        ScimConfigEntity config =
                configs.findByOrganizationId(token.getOrganizationId()).orElse(null);
        if (config == null || !config.isEnabled()) {
            throw refused("connector_disabled", token.getOrganizationId());
        }
        touchLastUsed(token);
        return token.getOrganizationId();
    }

    /**
     * The one-token-per-organization rotation check a generation runs
     * before replacing the live row: answers the organization whose
     * live token row this is, or empty.
     */
    @Transactional(readOnly = true)
    public ScimTokenEntity liveTokenOf(UUID organizationId) {
        return tokens.findByOrganizationId(organizationId).orElse(null);
    }

    private void touchLastUsed(ScimTokenEntity token) {
        Instant now = Instant.now(clock);
        if (token.getLastUsedAt() == null || token.getLastUsedAt().isBefore(now.minus(Duration.ofMinutes(1)))) {
            token.markUsed(now);
            tokens.save(token);
        }
    }

    private ScimException refused(String reason, UUID organizationId) {
        audit.publish(new AuditEvent(
                AuditEventType.SCIM_REQUEST_REJECTED,
                Instant.now(clock),
                null,
                organizationId,
                null,
                false,
                Map.of("reason", reason)));
        return ScimException.unauthorized();
    }
}
