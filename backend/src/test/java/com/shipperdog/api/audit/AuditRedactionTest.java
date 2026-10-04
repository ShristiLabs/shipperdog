package com.shipperdog.api.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The secret scrubber of the audit framework (issue #6 technical notes:
 * never log secrets or full tokens). Key-based redaction must catch the
 * marker vocabulary in any casing or embedding, leave ordinary values
 * untouched, and handle JSON snapshots defensively — nested secrets are
 * scrubbed, and content that is not JSON never reaches the store verbatim.
 */
class AuditRedactionTest {

    @Test
    void detailValuesUnderSecretKeysAreRedacted() {
        Map<String, String> redacted = AuditRedaction.redactDetail(Map.of(
                "password", "hunter2",
                "newPassword", "hunter3",
                "apiKey", "sk-123",
                "AUTHORIZATION", "Bearer abc",
                "client-secret", "top",
                "refreshTokenValue", "rt",
                "cookieValue", "session",
                "privateKeyPem", "-----BEGIN",
                "reason", "role_demoted",
                "role.after", "DEVELOPER"));

        assertThat(redacted)
                .containsEntry("password", AuditRedaction.REDACTED)
                .containsEntry("newPassword", AuditRedaction.REDACTED)
                .containsEntry("apiKey", AuditRedaction.REDACTED)
                .containsEntry("AUTHORIZATION", AuditRedaction.REDACTED)
                .containsEntry("client-secret", AuditRedaction.REDACTED)
                .containsEntry("refreshTokenValue", AuditRedaction.REDACTED)
                .containsEntry("cookieValue", AuditRedaction.REDACTED)
                .containsEntry("privateKeyPem", AuditRedaction.REDACTED)
                .containsEntry("reason", "role_demoted")
                .containsEntry("role.after", "DEVELOPER");
    }

    @Test
    void nullDetailYieldsAnEmptyMap() {
        assertThat(AuditRedaction.redactDetail(null)).isEmpty();
    }

    @Test
    void snapshotSecretsAreRedactedAtAnyDepth() {
        String snapshot = """
                {"name":"banking","status":"ACTIVE","credentials":{"password":"hunter2","otp":"123456"},
                 "owners":[{"name":"a","token":"t-1"},{"name":"b"}],"notes":"keep"}""";

        String redacted = AuditRedaction.redactSnapshot(snapshot);

        assertThat(redacted)
                .as("a secret-bearing object key redacts its whole subtree")
                .contains("\"credentials\":\"" + AuditRedaction.REDACTED + "\"")
                .contains("\"token\":\"" + AuditRedaction.REDACTED + "\"")
                .contains("\"name\":\"banking\"")
                .contains("\"notes\":\"keep\"")
                .doesNotContain("hunter2")
                .doesNotContain("123456")
                .doesNotContain("t-1");
    }

    @Test
    void snapshotSecretKeysAreCaughtInAnyCaseOrSeparatorStyle() {
        String snapshot =
                "{\"Api-Key\":\"k1\",\"PRIVATE_KEY\":\"k2\",\"authTokenNested\":\"k3\",\"displayName\":\"ops\"}";

        JsonNode redacted = new ObjectMapper().readTree(AuditRedaction.redactSnapshot(snapshot));

        assertThat(redacted.get("Api-Key").asText())
                .as("PascalCase with a separator still carries the marker")
                .isEqualTo(AuditRedaction.REDACTED);
        assertThat(redacted.get("PRIVATE_KEY").asText())
                .as("shouting snake case still carries the marker")
                .isEqualTo(AuditRedaction.REDACTED);
        assertThat(redacted.get("authTokenNested").asText())
                .as("camelCase embedding still carries the marker")
                .isEqualTo(AuditRedaction.REDACTED);
        assertThat(redacted.get("displayName").asText()).isEqualTo("ops");
        assertThat(redacted.toString())
                .doesNotContain("k1")
                .doesNotContain("k2")
                .doesNotContain("k3");
    }

    @Test
    void unparseableSnapshotsAreReplacedWholesale() {
        assertThat(AuditRedaction.redactSnapshot("not json at all"))
                .isEqualTo("{\"redacted\":\"unparseable-snapshot\"}");
        assertThat(AuditRedaction.redactSnapshot("<script>password=x</script>"))
                .isEqualTo("{\"redacted\":\"unparseable-snapshot\"}");
    }

    @Test
    void nullAndBlankSnapshotsStayUntouched() {
        assertThat(AuditRedaction.redactSnapshot(null)).isNull();
        assertThat(AuditRedaction.redactSnapshot("  ")).isEqualTo("  ");
    }

    @Test
    void plainJsonSnapshotsPassThroughSemanticallyUnchanged() {
        String snapshot = "{\"name\":\"acme\",\"externalId\":\"crm-42\",\"members\":3}";

        assertThat(AuditRedaction.redactSnapshot(snapshot))
                .contains("acme")
                .contains("crm-42")
                .contains("3");
    }
}
