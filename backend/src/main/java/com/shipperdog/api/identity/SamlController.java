package com.shipperdog.api.identity;

import com.shipperdog.api.identity.internal.service.SamlLoginService;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The SAML SP's anonymous endpoints (S14-02, issue #68): the per-
 * organization SP metadata document an administrator registers with
 * the IdP, and the POST-binding Assertion Consumer Service both flows
 * land on. Matched by the identity security chain's permitAll and
 * carrying no method security by design -- the one-time request state
 * and the response's XML-DSig verification against pinned metadata
 * certificates ARE the authority (EndpointSecurityAnnotationTest's
 * anonymous allowlist).
 *
 * <p>The uniform-failure doctrine matches the OIDC flow exactly: every
 * ACS refusal answers the SAME 302 to the console with
 * {@code error=sso}; the distinct {@code saml_*} reason lives on the
 * audit line and the WARN log only.
 */
@RestController
@RequestMapping("/api/v1/auth/sso/saml/{organizationId}")
public class SamlController {

    private final SamlLoginService saml;

    SamlController(SamlLoginService saml) {
        this.saml = saml;
    }

    /**
     * The SP metadata document (its own URL is the entityID -- a
     * stable per-organization identifier; the ACS and, when the
     * deployment carries SP credentials, the signing certificate ride
     * along). Uniform 404 when the organization has no enabled SAML
     * configuration.
     */
    @GetMapping(path = "/metadata", produces = "application/samlmetadata+xml")
    ResponseEntity<String> metadata(@PathVariable UUID organizationId) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/samlmetadata+xml"))
                .body(saml.spMetadataXml(organizationId));
    }

    /**
     * The Assertion Consumer Service: the IdP POSTs the base64
     * SAMLResponse (plus the RelayState on SP-initiated flows) here.
     * Always a 302 to the console -- the one-time session code on
     * success, the uniform {@code error=sso} on every failure.
     */
    @PostMapping("/acs")
    ResponseEntity<Void> acs(
            @PathVariable UUID organizationId,
            @RequestParam(name = "SAMLResponse", required = false) String samlResponse,
            @RequestParam(name = "RelayState", required = false) String relayState) {
        String location = saml.handleAcs(organizationId, samlResponse, relayState);
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(location))
                .build();
    }
}
