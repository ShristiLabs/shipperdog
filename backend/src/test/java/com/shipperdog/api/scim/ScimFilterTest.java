package com.shipperdog.api.scim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shipperdog.api.identity.internal.scim.ScimException;
import com.shipperdog.api.identity.internal.scim.ScimFilter;
import org.junit.jupiter.api.Test;

/**
 * The SCIM filter subset parser (issue #69, S14-03): the two
 * comparisons IdPs actually send parse with either quote style and
 * either attribute casing; everything else — other attributes, other
 * operators, and/or composition, unquoted values — is refused
 * LOUDLY with the RFC's invalidFilter refusal so a mis-wired
 * connector fails visibly instead of silently matching everything.
 */
class ScimFilterTest {

    @Test
    void parsesTheSupportedComparisons() {
        ScimFilter userName = ScimFilter.parse("userName eq \"ada@company.example\"");
        assertThat(userName.attribute()).isEqualTo(ScimFilter.Attribute.USER_NAME);
        assertThat(userName.value()).isEqualTo("ada@company.example");

        assertThat(ScimFilter.parse("externalId eq \"entra-1234\"").attribute())
                .isEqualTo(ScimFilter.Attribute.EXTERNAL_ID);
        assertThat(ScimFilter.parse("externalid eq 'okta-5678'").value()).isEqualTo("okta-5678");
        assertThat(ScimFilter.parse("USERNAME eq \"Ws-9012\"").attribute()).isEqualTo(ScimFilter.Attribute.USER_NAME);
        assertThat(ScimFilter.parse(" userName eq \"  spaced  \" ").value()).isEqualTo("  spaced  ");
        assertThat(ScimFilter.parse(null)).isNull();
        assertThat(ScimFilter.parse("   ")).isNull();
    }

    @Test
    void refusesEverythingElseLoudly() {
        assertThatThrownBy(() -> ScimFilter.parse("name.givenName eq \"Ada\""))
                .isInstanceOf(ScimException.class)
                .hasMessageContaining("only userName and externalId are filterable");
        assertThatThrownBy(() -> ScimFilter.parse("userName ne \"someone\""))
                .isInstanceOf(ScimException.class)
                .hasMessageContaining("unsupported filter");
        assertThatThrownBy(() -> ScimFilter.parse("userName co \"ada\""))
                .isInstanceOf(ScimException.class)
                .hasMessageContaining("unsupported filter");
        assertThatThrownBy(() -> ScimFilter.parse("userName eq \"a\" and externalId eq \"b\""))
                .isInstanceOf(ScimException.class)
                .hasMessageContaining("unsupported filter");
        assertThatThrownBy(() -> ScimFilter.parse("userName eq ada"))
                .isInstanceOf(ScimException.class)
                .hasMessageContaining("unsupported filter");
        assertThatThrownBy(() -> ScimFilter.parse("userName eq \"a\" trailing"))
                .isInstanceOf(ScimException.class)
                .hasMessageContaining("unsupported filter");
        assertThatThrownBy(() -> ScimFilter.parse("userName eq \"\""))
                .isInstanceOf(ScimException.class)
                .hasMessageContaining("non-empty");
    }
}
