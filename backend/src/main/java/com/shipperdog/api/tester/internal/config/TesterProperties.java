package com.shipperdog.api.tester.internal.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tester-module settings (prefix {@code shipperdog.tester}, issues #26
 * and #27). All values come from environment variables via
 * application.yml.
 *
 * @param invitationTtl  lifetime of a tester onboarding invitation —
 *                       single use, default 7 days per the issue's
 *                       acceptance criteria (distinct from the platform
 *                       invitation TTL the identity module owns)
 * @param portalBaseUrl  base URL of the tester-facing accept page the
 *                       invitation email links to; the portal experience
 *                       itself arrives with E8, so the default points at
 *                       the local console origin
 * @param defaultGroups  names seeded as tester groups on every tenant
 *                       creation (issue #27, PRD 8.9's examples); the
 *                       seeded rows are ordinary editable groups. An
 *                       empty list skips seeding; duplicates and blanks
 *                       are dropped at bind time
 */
@ConfigurationProperties(prefix = "shipperdog.tester")
public record TesterProperties(Duration invitationTtl, String portalBaseUrl, List<String> defaultGroups) {

    static final Duration DEFAULT_INVITATION_TTL = Duration.ofDays(7);
    static final String DEFAULT_PORTAL_BASE_URL = "http://localhost:5173";

    /** PRD 8.9's example groups, the story's configurable seed list. */
    static final List<String> DEFAULT_GROUPS =
            List.of("Internal QA", "Tenant QA", "UAT", "Pilot", "Executive Beta", "External Beta");

    public TesterProperties {
        invitationTtl = invitationTtl == null ? DEFAULT_INVITATION_TTL : invitationTtl;
        portalBaseUrl = portalBaseUrl == null || portalBaseUrl.isBlank() ? DEFAULT_PORTAL_BASE_URL : portalBaseUrl;
        defaultGroups = defaultGroups == null
                ? DEFAULT_GROUPS
                : defaultGroups.stream()
                        .filter(name -> name != null && !name.isBlank())
                        .map(String::trim)
                        .distinct()
                        .toList();
    }
}
