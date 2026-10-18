package com.shipperdog.api.otabundle;

import com.shipperdog.api.config.Idempotent;
import com.shipperdog.api.config.ListQuery;
import com.shipperdog.api.config.PageResponse;
import com.shipperdog.api.identity.AuthenticatedPrincipal;
import com.shipperdog.api.otabundle.internal.service.OtaAppTokenService;
import com.shipperdog.api.otabundle.internal.service.OtaAppTokenService.IssuedView;
import com.shipperdog.api.otabundle.internal.service.OtaAppTokenService.OtaAppTokenView;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The OTA app-token API (S16-04, issue #166, ADR-0010 D3): {@code POST
 * /api/v1/ota/app-tokens} mints the {@code sdc1.} device identifier
 * for an application — the value the console/CLI copies into the
 * shipped binary, returned EXACTLY ONCE (the registry stores only its
 * SHA-256) — the listing renders the registry without the token, and
 * {@code POST /api/v1/ota/app-tokens/{id}/revoke} withdraws it: the
 * authentication filter's hash lookup refuses a revoked token from
 * that moment on.
 *
 * <p>Routes are application-addressed the way the bundle routes are,
 * on the artifact family's permissions (the D5 ride-along, the
 * DEVELOPER and APP_OWNER personas it fits): {@code
 * app:artifacts:upload} for issue and revoke, {@code
 * app:artifacts:read} for the listing. Foreign or unknown ids are the
 * concealed miss; the OTA-disabled application is issue's own 409
 * (revocation and listing stay available after the opt-out — a
 * fleet's live identifiers must stay revocable).
 */
@RestController
@RequestMapping("/api/v1/ota")
public class OtaAppTokenController {

    private final OtaAppTokenService tokens;

    OtaAppTokenController(OtaAppTokenService tokens) {
        this.tokens = tokens;
    }

    /**
     * Issues one {@code sdc1.} app token for the application: the
     * authoring guard (live, OTA-enabled application) applies, and the
     * answer carries the token value beside its registry row — the
     * only time the value ever leaves the server. Honors {@code
     * Idempotency-Key}.
     */
    @PostMapping("/app-tokens")
    @ResponseStatus(HttpStatus.CREATED)
    @Idempotent
    @PreAuthorize("hasPermission(#request.applicationId(), 'application', 'app:artifacts:upload')")
    IssuedView issueAppToken(@AuthenticationPrincipal Object principal, @RequestBody IssueAppTokenRequest request) {
        return tokens.issue(
                requireUserId(principal), new OtaAppTokenService.IssueCommand(request.applicationId(), request.name()));
    }

    /**
     * Lists the application's tokens, newest first, standard
     * {@code limit}/{@code cursor} pagination. Rows carry the
     * live/revoked state and never a token value — it does not exist
     * server-side to render.
     */
    @GetMapping("/app-tokens")
    @PreAuthorize("hasPermission(#applicationId, 'application', 'app:artifacts:read')")
    PageResponse<OtaAppTokenView> listAppTokens(
            @AuthenticationPrincipal Object principal,
            @RequestParam("applicationId") UUID applicationId,
            @RequestParam(value = "limit", required = false) String limit,
            @RequestParam(value = "cursor", required = false) String cursor) {
        ListQuery query = ListQuery.of(limit, cursor);
        return tokens.list(requireUserId(principal), applicationId, query.limit(), query.offset());
    }

    /**
     * Revokes a token: stamps the registry row and answers it. A
     * revoked token stops authenticating immediately (the filter's
     * hash lookup refuses it); a replay answers the same row. Honors
     * {@code Idempotency-Key}.
     */
    @PostMapping("/app-tokens/{tokenId}/revoke")
    @Idempotent
    @PreAuthorize("hasPermission(#request.applicationId(), 'application', 'app:artifacts:upload')")
    OtaAppTokenView revokeAppToken(
            @AuthenticationPrincipal Object principal,
            @PathVariable UUID tokenId,
            @RequestBody RevokeAppTokenRequest request) {
        return tokens.revoke(requireUserId(principal), tokenId, request.applicationId());
    }

    private static UUID requireUserId(Object principal) {
        if (principal instanceof Jwt jwt) {
            return UUID.fromString(jwt.getSubject());
        }
        if (principal instanceof AuthenticatedPrincipal patPrincipal) {
            return patPrincipal.userId();
        }
        throw new OtaBundleException(OtaBundleException.Code.INVALID_REQUEST, "no authenticated principal");
    }

    /** The issue body: the owning application (the permission target) and the token's label. */
    @Schema(
            description = "The OTA app token to issue: applicationId (the permission target) and the human"
                    + " name the registry distinguishes tokens by (1-200 chars).")
    public record IssueAppTokenRequest(UUID applicationId, String name) {}

    /** The revoke body: the owning application (the permission target). */
    @Schema(description = "The application id the revoked app token belongs to.")
    public record RevokeAppTokenRequest(UUID applicationId) {}
}
