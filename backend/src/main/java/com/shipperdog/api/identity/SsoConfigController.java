package com.shipperdog.api.identity;

import com.shipperdog.api.identity.internal.AuthException;
import com.shipperdog.api.identity.internal.service.SsoConfigService;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The organization's OIDC relying-party configuration (S14-01, issue
 * #67): the {@code /api/v1/organizations/{id}/idp-config} route the
 * S1-04 placeholder introduced, owned by the identity module since the
 * configuration became real authentication surface. Same path, same
 * {@code org:idp-configs:read/write} permissions -- only the payload
 * grew up (typed OIDC fields; the secret arrives as one-time material
 * or a pre-provisioned reference and is echoed as the reference
 * alone).
 */
@RestController
@RequestMapping("/api/v1/organizations/{id}/idp-config")
public class SsoConfigController {

    private final SsoConfigService configs;

    SsoConfigController(SsoConfigService configs) {
        this.configs = configs;
    }

    @GetMapping
    @PreAuthorize("hasPermission(#id, 'organization', 'org:idp-configs:read')")
    SsoConfigService.SsoConfig view(@PathVariable UUID id) {
        return configs.view(id);
    }

    @PutMapping
    @PreAuthorize("hasPermission(#id, 'organization', 'org:idp-configs:write')")
    SsoConfigService.SsoConfig update(
            @AuthenticationPrincipal Object principal,
            @PathVariable UUID id,
            @RequestBody SsoConfigService.UpdateRequest request) {
        return configs.update(requireUserId(principal), id, request);
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
