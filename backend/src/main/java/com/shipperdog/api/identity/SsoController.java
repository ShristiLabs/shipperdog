package com.shipperdog.api.identity;

import com.shipperdog.api.identity.internal.AuthException;
import com.shipperdog.api.identity.internal.service.SamlLoginService;
import com.shipperdog.api.identity.internal.service.SsoLoginService;
import com.shipperdog.api.identity.internal.service.TokenService;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The SSO flow's anonymous endpoints (S14-01 issue #67, SAML joined
 * S14-02 issue #68): the console's SSO probe, the backend-driven
 * authorization redirect (dispatched by the organization's configured
 * provider kind -- OIDC authorization-code or SAML AuthnRequest), the
 * OIDC provider callback, the one-time session-code exchange shared
 * by every provider kind, and the OIDC Back-Channel Logout receiver.
 * All are matched by the identity security chain's permitAll and
 * carry no method security by design -- the flows' own one-time state
 * and cryptographic verification ARE the authority
 * (EndpointSecurityAnnotationTest's anonymous allowlist).
 *
 * <p>The SAML-specific routes (SP metadata, the ACS) live on
 * {@link SamlController}; the configuration surface lives on
 * {@link SsoConfigController}.
 */
@RestController
@RequestMapping("/api/v1/auth/sso")
public class SsoController {

    private final SsoLoginService sso;
    private final SamlLoginService saml;

    SsoController(SsoLoginService sso, SamlLoginService saml) {
        this.sso = sso;
        this.saml = saml;
    }

    /**
     * Whether the organization offers SSO (the console login's probe).
     * Uniform 404 for unknown, unconfigured, and disabled alike.
     */
    @GetMapping("/options")
    SsoOptionsResponse options(@RequestParam UUID organizationId) {
        String providerType = sso.providerTypeOf(organizationId)
                .orElseThrow(() -> new AuthException(
                        AuthException.Code.NOT_FOUND, "single sign-on is not configured for this organization"));
        return new SsoOptionsResponse(organizationId, providerType);
    }

    /**
     * Starts the flow: 302 to the provider's authorization surface --
     * the OIDC provider's authorization endpoint (state, nonce, S256
     * PKCE challenge) or the SAML IdP's Redirect-binding SSO location
     * (DEFLATE+b64 AuthnRequest, RelayState, optional SP signature),
     * whichever kind the organization configured.
     */
    @GetMapping("/authorize")
    ResponseEntity<Void> authorize(@RequestParam UUID organizationId) {
        String location = "SAML".equals(sso.providerTypeOf(organizationId).orElse(null))
                ? saml.beginAuthorization(organizationId)
                : sso.beginAuthorization(organizationId);
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(location))
                .build();
    }

    /**
     * The OIDC provider's redirect back. Always a 302 to the console: the
     * one-time session code on success, the uniform {@code error=sso}
     * on every failure (the distinct reason lives on the audit line).
     */
    @GetMapping("/callback")
    ResponseEntity<Void> callback(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String error) {
        String location = sso.handleCallback(code, state, error);
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(location))
                .build();
    }

    /**
     * Exchanges the one-time session code for the session pair -- the
     * same shape {@code POST /api/v1/auth/login} answers with, for
     * every provider kind.
     */
    @PostMapping("/exchange")
    TokenResponse exchange(@RequestBody ExchangeRequest request) {
        TokenService.IssuedTokens issued = sso.exchangeSessionCode(request.code());
        return new TokenResponse(issued.accessToken(), issued.refreshToken(), "Bearer", issued.expiresInSeconds());
    }

    /**
     * OIDC Back-Channel Logout receiver: the provider's signed logout
     * token ends the linked account's local sessions. Form-encoded per
     * the spec ({@code logout_token=...}); 204 on acceptance and on
     * unknown subjects alike, 400 for a token that fails verification.
     */
    @PostMapping("/logout")
    ResponseEntity<Void> backChannelLogout(@RequestParam(name = "logout_token", required = false) String logoutToken) {
        sso.backChannelLogout(logoutToken);
        return ResponseEntity.noContent().build();
    }

    /** The console probe's answer: this organization's SSO provider kind. */
    public record SsoOptionsResponse(UUID organizationId, String providerType) {}

    /** Session-code exchange request. */
    public record ExchangeRequest(String code) {}

    /** The same token-pair shape the local login answers with. */
    public record TokenResponse(String accessToken, String refreshToken, String tokenType, long expiresInSeconds) {}
}
