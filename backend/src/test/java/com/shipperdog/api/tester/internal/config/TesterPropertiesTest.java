package com.shipperdog.api.tester.internal.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Binding defaults for the tester module's settings (issues #26 and
 * #27): the invitation TTL and portal base URL keep their S6-01
 * behavior, and the default-groups seed list defaults to PRD 8.9's
 * examples, survives a null, and drops blanks and duplicates at bind
 * time (a malformed environment value cannot multiply rows).
 */
class TesterPropertiesTest {

    @Test
    void defaultsToThePrdExampleList() {
        assertThat(new TesterProperties(null, null, null).defaultGroups())
                .containsExactly("Internal QA", "Tenant QA", "UAT", "Pilot", "Executive Beta", "External Beta");
        assertThat(new TesterProperties(null, null, null).invitationTtl()).isEqualTo(Duration.ofDays(7));
        assertThat(new TesterProperties(null, null, null).portalBaseUrl()).isEqualTo("http://localhost:5173");
    }

    @Test
    void seedListIsTrimmedDeduplicatedAndBlankFree() {
        assertThat(new TesterProperties(null, null, List.of(" Pilot ", "", "UAT", "Pilot")).defaultGroups())
                .containsExactly("Pilot", "UAT");
        assertThat(new TesterProperties(null, null, List.of()).defaultGroups())
                .as("an explicitly empty list disables seeding without failing tenant creation")
                .isEmpty();
    }
}
