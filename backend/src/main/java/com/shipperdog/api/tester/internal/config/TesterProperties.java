package com.shipperdog.api.tester.internal.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tester-module settings (prefix {@code shipperdog.tester}, issue #26).
 * All values come from environment variables via application.yml.
 *
 * @param invitationTtl  lifetime of a tester onboarding invitation —
 *                       single use, default 7 days per the issue's
 *                       acceptance criteria (distinct from the platform
 *                       invitation TTL the identity module owns)
 * @param portalBaseUrl  base URL of the tester-facing accept page the
 *                       invitation email links to; the portal experience
 *                       itself arrives with E8, so the default points at
 *                       the local console origin
 */
@ConfigurationProperties(prefix = "shipperdog.tester")
public record TesterProperties(Duration invitationTtl, String portalBaseUrl) {

    static final Duration DEFAULT_INVITATION_TTL = Duration.ofDays(7);
    static final String DEFAULT_PORTAL_BASE_URL = "http://localhost:5173";

    public TesterProperties {
        invitationTtl = invitationTtl == null ? DEFAULT_INVITATION_TTL : invitationTtl;
        portalBaseUrl = portalBaseUrl == null || portalBaseUrl.isBlank() ? DEFAULT_PORTAL_BASE_URL : portalBaseUrl;
    }
}
