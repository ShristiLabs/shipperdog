package com.shipperdog.api.identity;

import com.shipperdog.api.identity.internal.AuthException;
import com.shipperdog.api.identity.internal.service.ScimConfigService;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The organization's SCIM provisioning configuration (S14-03, issue
 * #69): the {@code /api/v1/organizations/{id}/scim-config} surface —
 * settings, mapping rules, and the connector token's lifecycle.
 * Same {@code org:idp-configs:read/write} permissions as the SSO
 * configuration (the identity-provider configuration family), with
 * the same concealed-404 cross-organization doctrine.
 */
@RestController
@RequestMapping("/api/v1/organizations/{id}/scim-config")
public class ScimConfigController {

    private final ScimConfigService configs;

    ScimConfigController(ScimConfigService configs) {
        this.configs = configs;
    }

    @GetMapping
    @PreAuthorize("hasPermission(#id, 'organization', 'org:idp-configs:read')")
    ScimConfigService.ScimConfig view(@PathVariable UUID id) {
        return configs.view(id);
    }

    @PutMapping
    @PreAuthorize("hasPermission(#id, 'organization', 'org:idp-configs:write')")
    ScimConfigService.ScimConfig update(
            @AuthenticationPrincipal Object principal,
            @PathVariable UUID id,
            @RequestBody ScimConfigService.UpdateRequest request) {
        return configs.update(requireUserId(principal), id, request);
    }

    /** Generates (or rotates) the connector token; the plaintext appears exactly once, here. */
    @PostMapping("/token")
    @PreAuthorize("hasPermission(#id, 'organization', 'org:idp-configs:write')")
    ScimConfigService.TokenView generateToken(
            @AuthenticationPrincipal Object principal,
            @PathVariable UUID id,
            @RequestBody(required = false) ScimConfigService.TokenRequest request) {
        return configs.generateToken(requireUserId(principal), id, request);
    }

    /** Revokes the live token; SCIM freezes answering until a new one is generated. */
    @DeleteMapping("/token")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasPermission(#id, 'organization', 'org:idp-configs:write')")
    void revokeToken(@AuthenticationPrincipal Object principal, @PathVariable UUID id) {
        configs.revokeToken(requireUserId(principal), id);
    }

    private static UUID requireUserId(Object principal) {
        if (principal instanceof Jwt jwt) {
            return UUID.fromString(jwt.getSubject());
        }
        if (principal instanceof AuthenticatedPrincipal patPrincipal) {
            return patPrincipal.userId();
        }
        throw new AuthException(AuthException.Code.INVALID_CREDENTIALS, "no authenticated principal");
    }
}
