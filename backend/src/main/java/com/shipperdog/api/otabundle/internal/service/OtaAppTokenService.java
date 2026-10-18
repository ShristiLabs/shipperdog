package com.shipperdog.api.otabundle.internal.service;

import com.shipperdog.api.application.ApplicationAccess;
import com.shipperdog.api.application.ApplicationAccess.ApplicationProfile;
import com.shipperdog.api.config.CursorPageable;
import com.shipperdog.api.config.PageResponse;
import com.shipperdog.api.otabundle.OtaAppTokenCodec;
import com.shipperdog.api.otabundle.OtaAppTokenPrincipal;
import com.shipperdog.api.otabundle.OtaBundleException;
import com.shipperdog.api.otabundle.internal.entity.OtaAppTokenEntity;
import com.shipperdog.api.otabundle.internal.repository.OtaAppTokenRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The {@code sdc1.} app-token registry's orchestration (S16-04,
 * issue #166, ADR-0010 D3): issue mints through the codec and lands
 * ONLY the SHA-256 of the token material (write-only registry — the
 * token is returned exactly once, at issue), revoke stamps {@code
 * revoked_at} idempotently, and the listing renders the registry
 * without the token (it does not exist server-side to render).
 *
 * <p>Resolution is the device-side authentication: codec parse, then
 * the hashed-registry lookup on EVERY request — revocation is a row
 * state, so a revoked token stops authenticating the moment its row
 * is stamped (the immediate-revocation contract). The registry row
 * must agree with the token's claims on every id (a token cannot be
 * re-pointed at another row or application; the MAC already fixed the
 * payload, the row check fixes the world), and the application must
 * still resolve — the principal then carries org/tenant/app ids, the
 * unambiguous resolution bundleId cannot give (D3). Whether an
 * OTA-disabled application still serves checks is the check
 * resolution's question (S16-07 consults {@code ota_enabled}); this
 * layer resolves identity, never policy.
 *
 * <p>Console writes do not gate revocation or listing on the OTA
 * opt-in: an owner who disables OTA must still see and retire the
 * fleet's live identifiers (hygiene, not authoring) — only ISSUE is
 * an authoring act and carries the full authoring guard.
 */
@Service
public class OtaAppTokenService {

    /** The listing order: newest token first, the id breaking ties. */
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final OtaAppTokenCodec codec;
    private final OtaAppTokenRepository tokens;
    private final ApplicationAccess applications;

    OtaAppTokenService(OtaAppTokenCodec codec, OtaAppTokenRepository tokens, ApplicationAccess applications) {
        this.codec = codec;
        this.tokens = tokens;
        this.applications = applications;
    }

    // -------------------------------------------------------------------------
    // Issue: guard + mint + hash-only persist
    // -------------------------------------------------------------------------

    /**
     * Issues one app token: validates the authoring surface (the same
     * guard as bundle authoring — a live, OTA-enabled application),
     * mints the {@code sdc1.} identifier under a fresh registry-row id
     * (the id rides the payload, so every issue is distinct material),
     * and lands ONLY the SHA-256 hex of the token string. The answer
     * carries the token value — the only time it ever leaves the
     * server. Honors {@code Idempotency-Key} at the route.
     */
    @Transactional
    public IssuedView issue(UUID actorId, IssueCommand command) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        String name = requireName(command.name(), fieldErrors);
        if (!fieldErrors.isEmpty()) {
            throw invalid("the app-token request is invalid", fieldErrors);
        }
        ApplicationProfile application =
                applications.profileOf(command.applicationId()).orElseThrow(OtaAppTokenService::applicationMissing);
        requireIssuable(application);
        UUID tokenId = UUID.randomUUID();
        String token = codec.mint(application.organizationId(), application.applicationId(), tokenId);
        OtaAppTokenEntity saved = tokens.save(new OtaAppTokenEntity(
                tokenId, application.organizationId(), application.applicationId(), name, hashOf(token), actorId));
        return new IssuedView(viewOf(saved), token);
    }

    // -------------------------------------------------------------------------
    // Resolution: the device-side authentication (codec + registry)
    // -------------------------------------------------------------------------

    /**
     * Resolves a presented token to its principal: the MAC must verify
     * (codec), the registry must hold the hash, the row must be live
     * and coherent with the token's claims, and the application must
     * still resolve. Empty on ANY failure — the filter renders the
     * uniform 401, never a reason.
     */
    @Transactional(readOnly = true)
    public Optional<OtaAppTokenPrincipal> resolve(String token) {
        OtaAppTokenCodec.Claims claims = codec.parse(token).orElse(null);
        if (claims == null) {
            return Optional.empty();
        }
        OtaAppTokenEntity row = tokens.findByTokenHash(hashOf(token)).orElse(null);
        if (row == null
                || !row.isLive()
                || !row.getId().equals(claims.tokenId())
                || !row.getApplicationId().equals(claims.applicationId())
                || !row.getOrganizationId().equals(claims.organizationId())) {
            return Optional.empty();
        }
        return applications
                .profileOf(claims.applicationId())
                .filter(profile -> profile.organizationId().equals(claims.organizationId()))
                .map(profile -> new OtaAppTokenPrincipal(
                        claims.organizationId(), profile.tenantId(), claims.applicationId(), claims.tokenId()));
    }

    // -------------------------------------------------------------------------
    // Revoke: the registry's idempotent withdrawal
    // -------------------------------------------------------------------------

    /**
     * Revokes a token: stamps {@code revoked_at} (a live row only — a
     * replay of an already-revoked token answers the same row), and
     * the filter's lookup refuses it from that moment on. A foreign or
     * unknown token id is the concealed miss.
     */
    @Transactional
    public OtaAppTokenView revoke(UUID actorId, UUID tokenId, UUID applicationId) {
        ApplicationProfile application =
                applications.profileOf(applicationId).orElseThrow(OtaAppTokenService::applicationMissing);
        OtaAppTokenEntity token = tokens.findByIdAndApplicationIdAndOrganizationId(
                        tokenId, applicationId, application.organizationId())
                .orElseThrow(OtaAppTokenService::tokenMissing);
        token.revoke();
        return viewOf(tokens.save(token));
    }

    // -------------------------------------------------------------------------
    // List: the registry without the token (it does not exist to render)
    // -------------------------------------------------------------------------

    /**
     * Lists the application's tokens, newest first, standard
     * limit/cursor pagination through {@code CursorCodec}; every row
     * renders its live/revoked state and never a token value.
     */
    @Transactional(readOnly = true)
    public PageResponse<OtaAppTokenView> list(UUID actorId, UUID applicationId, int limit, long offset) {
        ApplicationProfile application =
                applications.profileOf(applicationId).orElseThrow(OtaAppTokenService::applicationMissing);
        Page<OtaAppTokenEntity> page = tokens.findByApplicationIdAndOrganizationId(
                applicationId, application.organizationId(), new CursorPageable(offset, limit, NEWEST_FIRST));
        List<OtaAppTokenView> items =
                page.getContent().stream().map(OtaAppTokenService::viewOf).toList();
        return PageResponse.of(items, page.hasNext(), offset + limit);
    }

    // -------------------------------------------------------------------------
    // Views and guards
    // -------------------------------------------------------------------------

    /** The issue request as the controller parsed it. */
    public record IssueCommand(UUID applicationId, String name) {}

    /** The issue answer: the registry row plus the token, exactly once. */
    public record IssuedView(OtaAppTokenView token, String value) {}

    /** One registry row's rendered state — never the token value. */
    public record OtaAppTokenView(
            UUID id, UUID applicationId, String name, UUID createdBy, boolean revoked, java.time.Instant revokedAt) {}

    private static OtaAppTokenView viewOf(OtaAppTokenEntity token) {
        return new OtaAppTokenView(
                token.getId(),
                token.getApplicationId(),
                token.getName(),
                token.getCreatedBy(),
                !token.isLive(),
                token.getRevokedAt());
    }

    /**
     * The registry's hashing rule (V55): the SHA-256 hex of the FULL
     * token string, prefix included — the exact bytes the device
     * presents.
     */
    private static String hashOf(String token) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable: " + e.getMessage(), e);
        }
    }

    /** V55's shape: 1..200 characters once trimmed. */
    private static String requireName(String value, Map<String, String> errors) {
        if (value == null) {
            errors.put("name", "name is required");
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.length() > 200) {
            errors.put("name", "name is 1 to 200 characters");
            return null;
        }
        return trimmed;
    }

    /**
     * The issue guard: issuing an app token is an OTA authoring act —
     * the same guard as bundle authoring (a live, OTA-enabled
     * application). Revocation and listing deliberately skip the
     * opt-in check: a fleet's live identifiers stay visible and
     * revocable after the owner disables OTA.
     */
    private static void requireIssuable(ApplicationProfile application) {
        if ("ARCHIVED".equals(application.status())) {
            throw new OtaBundleException(
                    OtaBundleException.Code.APPLICATION_NOT_FOUND, "application is archived; no new app tokens");
        }
        if (!application.otaEnabled()) {
            throw new OtaBundleException(
                    OtaBundleException.Code.APPLICATION_NOT_OTA_ENABLED,
                    "the application has not enabled OTA bundle updates");
        }
    }

    private static OtaBundleException applicationMissing() {
        return new OtaBundleException(OtaBundleException.Code.APPLICATION_NOT_FOUND, "application not found");
    }

    private static OtaBundleException tokenMissing() {
        return new OtaBundleException(OtaBundleException.Code.TOKEN_NOT_FOUND, "app token not found");
    }

    private static OtaBundleException invalid(String message, Map<String, String> fieldErrors) {
        return new OtaBundleException(OtaBundleException.Code.INVALID_REQUEST, message, fieldErrors);
    }
}
